package io.github.urionsisdi.nfcintime

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.lifecycleScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import io.github.urionsisdi.nfcintime.auth.DevLogin
import io.github.urionsisdi.nfcintime.auth.Login
import io.github.urionsisdi.nfcintime.auth.TelegramAuth
import io.github.urionsisdi.nfcintime.nfc.ReaderLink
import io.github.urionsisdi.nfcintime.ui.Root
import io.github.urionsisdi.nfcintime.ui.theme.NfcInTimeTheme

/**
 * The only activity. It also receives the Telegram redirect, which is why it is
 * `singleTask`: the login comes back into the running task rather than starting
 * a second copy of the game.
 */
class MainActivity : ComponentActivity() {
    private val game: Game by lazy { (application as App).game }
    private lateinit var reader: ReaderLink

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // A contact is played with both hands and no touches, and the screen going
        // dark mid-struggle reads as the app having quit.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        reader = ReaderLink(this, game.contact, lifecycleScope)
        TelegramAuth.init(BuildConfig.TELEGRAM_CLIENT_ID, BuildConfig.TELEGRAM_REDIRECT_URI)
        intent?.data?.let(TelegramAuth::onRedirect)

        setContent {
            NfcInTimeTheme {
                var nfcReady by remember { mutableStateOf(reader.enabled) }
                Root(
                    game = game,
                    nfcReady = nfcReady,
                    onSignIn = {
                        nfcReady = reader.enabled
                        game.signIn(this, login(), name = "")
                    },
                )
            }
        }
    }

    /**
     * A debug build talks to a server started with `NFCIT_AUTH_MODE=dev`, which
     * takes `tg_id[:name]` in place of a Telegram token. It keeps the game
     * playable on two phones before the bot is registered anywhere.
     */
    @SuppressLint("HardwareIds")
    private fun login(): Login = if (BuildConfig.DEV_AUTH) {
        val id = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID).take(10)
        DevLogin(id, Build.MODEL)
    } else {
        TelegramAuth
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.data?.let(TelegramAuth::onRedirect)
    }

    override fun onResume() {
        super.onResume()
        game.resume()
        reader.start()
    }

    override fun onPause() {
        reader.stop()
        game.pause()
        super.onPause()
    }
}
