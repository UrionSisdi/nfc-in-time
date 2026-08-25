package io.github.urionsisdi.nfcintime.crypto

import android.util.Base64
import java.security.GeneralSecurityException
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import org.json.JSONObject

/**
 * One settled contact. Both players sign the same bytes, so neither can report a
 * transfer the other did not agree to.
 */
data class Transfer(
    val nonce: String,
    val from: String,
    val to: String,
    val amount: Long,
    val prevHash: String,
    val signedAt: Long,
    val fromSig: String = "",
    val toSig: String = "",
) {
    fun json(): JSONObject = unsigned()
        .put("from_sig", fromSig)
        .put("to_sig", toSig)

    /** The record as it travels between the phones, before either has signed it. */
    fun unsigned(): JSONObject = JSONObject()
        .put("nonce", nonce)
        .put("from", from)
        .put("to", to)
        .put("amount", amount)
        .put("prev_hash", prevHash)
        .put("signed_at", signedAt)

    companion object {
        fun of(json: JSONObject) = Transfer(
            nonce = json.getString("nonce"),
            from = json.getString("from"),
            to = json.getString("to"),
            amount = json.getLong("amount"),
            prevHash = json.getString("prev_hash"),
            signedAt = json.getLong("signed_at"),
            fromSig = json.optString("from_sig"),
            toSig = json.optString("to_sig"),
        )
    }
}

/** The first link of a chain, before this installation has settled anything. */
const val GENESIS_HASH = "genesis"

/**
 * The exact bytes both parties sign. The server builds the same string in
 * `web/server/internal/api/protocol.go`; changing one side without the other
 * invalidates every transfer in flight.
 *
 *     nfcit/transfer/v1\n<nonce>\n<from>\n<to>\n<amount>\n<signed_at>\n<prev_hash>
 */
fun transferMessage(t: Transfer): ByteArray = buildString {
    append("nfcit/transfer/v1\n")
    append(t.nonce).append('\n')
    append(t.from).append('\n')
    append(t.to).append('\n')
    append(t.amount).append('\n')
    append(t.signedAt).append('\n')
    append(t.prevHash)
}.toByteArray(Charsets.UTF_8)

/** Names the transfer for the next link of the chain. */
fun transferHash(t: Transfer): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(transferMessage(t))
    return Base64.encodeToString(digest, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
}

fun newNonce(): String {
    val bytes = ByteArray(16).also { SecureRandom().nextBytes(it) }
    return Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
}

/**
 * Checks a signature made by the other side of a contact. The peer hands over its
 * public key in the opening frame; a record it refuses to sign properly is worth
 * nothing at `/v1/sync`, so it is better to find that out while still in contact.
 */
fun verifySignature(publicKeyDer: ByteArray, message: ByteArray, signatureBase64: String): Boolean =
    try {
        val key = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(publicKeyDer))
        Signature.getInstance("SHA256withECDSA").run {
            initVerify(key)
            update(message)
            verify(Base64.decode(signatureBase64, Base64.NO_WRAP))
        }
    } catch (e: GeneralSecurityException) {
        false
    } catch (e: IllegalArgumentException) {
        false
    }
