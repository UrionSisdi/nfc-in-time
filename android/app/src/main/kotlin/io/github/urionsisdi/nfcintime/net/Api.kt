package io.github.urionsisdi.nfcintime.net

import android.util.Base64
import io.github.urionsisdi.nfcintime.crypto.DeviceKey
import io.github.urionsisdi.nfcintime.crypto.Transfer
import io.github.urionsisdi.nfcintime.data.PlayerState
import io.github.urionsisdi.nfcintime.time.Clock
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONArray
import org.json.JSONObject

class ApiException(val status: Int, val code: String, message: String) : IOException(message)

/**
 * The server saying this installation's key will not be accepted again — it was
 * revoked by a sign-in elsewhere, or it no longer matches what is registered.
 * Nothing else the server refuses is worth giving up the registration over.
 */
const val CODE_KEY_UNKNOWN = "key_unknown"

/** The build `/v1/version` names as current. A zero code announces nothing. */
data class Release(val versionCode: Long, val versionName: String, val url: String)

/**
 * The handful of endpoints the app needs, over `HttpURLConnection`. A client this small
 * does not earn a networking library, and the body of `/v1/sync` has to be signed
 * as the exact bytes that go out — easier to guarantee when we write them.
 */
class Api(private val baseUrl: String, private val clock: Clock) {

    /** The nonce that goes into the attestation certificate. */
    fun challenge(): ByteArray {
        val json = post("/v1/auth/challenge", "{}".toByteArray(), signed = false)
        return Base64.decode(json.getString("challenge"), Base64.DEFAULT)
    }

    fun authTelegram(
        idToken: String,
        publicKey: String,
        attestation: List<String>,
        name: String,
        listed: Boolean,
    ): PlayerState {
        val body = JSONObject()
            .put("id_token", idToken)
            .put("public_key", publicKey)
            .put("attestation", JSONArray(attestation))
            .put("name", name)
            .put("listed", listed)
        return PlayerState.of(post("/v1/auth/telegram", body.toString().toByteArray(), signed = false))
    }

    fun release(): Release {
        val json = get("/v1/version")
        return Release(
            versionCode = json.getLong("version_code"),
            versionName = json.getString("version_name"),
            url = json.getString("url"),
        )
    }

    fun sync(
        keyId: String,
        name: String,
        listed: Boolean,
        transfers: List<Transfer>,
    ): PlayerState {
        val body = JSONObject()
            .put("issued_at", clock.unixSeconds())
            .put("name", name)
            .put("listed", listed)
            .put("transfers", JSONArray(transfers.map { it.json() }))
            .toString()
            .toByteArray()
        return PlayerState.of(post("/v1/sync", body, signed = true, keyId = keyId))
    }

    private fun get(path: String): JSONObject {
        val connection = (URL(baseUrl + path).openConnection() as HttpURLConnection).apply {
            connectTimeout = TIMEOUT_MILLIS
            readTimeout = TIMEOUT_MILLIS
        }
        try {
            return JSONObject(read(connection))
        } finally {
            connection.disconnect()
        }
    }

    private fun post(
        path: String,
        body: ByteArray,
        signed: Boolean,
        keyId: String = "",
    ): JSONObject {
        val connection = (URL(baseUrl + path).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = TIMEOUT_MILLIS
            readTimeout = TIMEOUT_MILLIS
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            if (signed) {
                setRequestProperty("X-NFCIT-Key", keyId)
                setRequestProperty("X-NFCIT-Signature", DeviceKey.sign(body))
            }
        }
        try {
            connection.outputStream.use { it.write(body) }
            return JSONObject(read(connection))
        } finally {
            connection.disconnect()
        }
    }

    private fun read(connection: HttpURLConnection): String {
        val status = connection.responseCode
        val text = (if (status < 400) connection.inputStream else connection.errorStream)
            ?.use { it.readBytes().toString(Charsets.UTF_8) }
            .orEmpty()
        if (status >= 400) throw failure(text, status)
        return text
    }

    private fun failure(text: String, status: Int): ApiException = try {
        val json = JSONObject(text)
        ApiException(status, json.optString("code"), json.optString("error").ifEmpty { "HTTP $status" })
    } catch (e: org.json.JSONException) {
        ApiException(status, "", "HTTP $status")
    }

    private companion object {
        const val TIMEOUT_MILLIS = 15_000
    }
}
