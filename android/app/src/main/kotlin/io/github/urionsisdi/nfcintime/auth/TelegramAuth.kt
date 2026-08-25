package io.github.urionsisdi.nfcintime.auth

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.telegram.login.TelegramLogin

/** Telegram is not on the phone, so there is nothing for the `tg://` link to open. */
class TelegramMissing : IOException("telegram is not installed")

/** Telegram answers nowhere the phone can reach, so the deep link cannot be minted. */
class TelegramUnreachable : IOException("telegram is unreachable")

/**
 * Telegram's own sign-in, wrapped into a suspend call. The SDK hands the app a
 * finished `id_token` — there is no code exchange and no client secret on this
 * side, which is exactly why a native app may do this at all.
 *
 * The sign-in happens inside the installed Telegram, reached through a `tg://`
 * link; the SDK falls back to a browser tab when nothing answers that scheme.
 * Either way the answer comes back on the App Link in [init]'s redirect, so that
 * host's assetlinks.json has to name this build's package — otherwise the browser
 * keeps the token and the login never returns.
 *
 * The token means nothing until the server checks its signature against
 * Telegram's JWKS; see `web/server/internal/telegram`.
 */
object TelegramAuth : Login {
    private var pending: CompletableDeferred<String>? = null

    fun init(clientId: String, redirectUri: String) {
        TelegramLogin.init(
            clientId = clientId,
            redirectUri = redirectUri,
            scopes = listOf("profile"),
        )
    }

    override suspend fun idToken(activity: Activity): String {
        // Without Telegram the SDK silently opens a browser tab instead, which
        // looks to the player like the app gave up. Say what is missing.
        if (!installed(activity)) throw TelegramMissing()
        if (!reachable()) throw TelegramUnreachable()
        val waiting = CompletableDeferred<String>()
        pending?.cancel()
        pending = waiting
        TelegramLogin.startLogin(activity)
        return waiting.await()
    }

    /**
     * The deep link that carries the login is minted by Telegram, so the SDK asks
     * for it over the network first and quietly opens a browser tab when that call
     * fails. A tab is a dead end here — it is outside whatever route reaches
     * Telegram — so the reachability is checked up front and said out loud.
     */
    private suspend fun reachable(): Boolean = withContext(Dispatchers.IO) {
        try {
            val connection = URL(ORIGIN).openConnection() as HttpURLConnection
            connection.requestMethod = "HEAD"
            connection.connectTimeout = PROBE_TIMEOUT_MILLIS
            connection.readTimeout = PROBE_TIMEOUT_MILLIS
            try {
                connection.responseCode > 0
            } finally {
                connection.disconnect()
            }
        } catch (e: IOException) {
            false
        }
    }

    private fun installed(activity: Activity): Boolean =
        activity.packageManager.resolveActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse("tg://resolve?domain=telegram")),
            PackageManager.MATCH_DEFAULT_ONLY,
        ) != null

    /** Called from the activity the redirect lands on. */
    fun onRedirect(uri: Uri) {
        val waiting = pending ?: return
        pending = null
        TelegramLogin.handleLoginResponse(
            uri,
            onSuccess = { data -> waiting.complete(data.idToken) },
            onError = { error -> waiting.completeExceptionally(IOException(error.toString())) },
        )
    }

    private const val ORIGIN = "https://oauth.telegram.org"
    private const val PROBE_TIMEOUT_MILLIS = 5_000
}
