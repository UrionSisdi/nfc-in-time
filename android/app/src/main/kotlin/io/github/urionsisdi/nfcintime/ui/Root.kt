package io.github.urionsisdi.nfcintime.ui

import android.os.SystemClock
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.urionsisdi.nfcintime.Game
import io.github.urionsisdi.nfcintime.Status
import kotlinx.coroutines.delay

/**
 * The whole app is four states and no navigation stack: signed out, dead,
 * in contact, or counting down. Settings sit over the counter, and death sits
 * over all of it.
 */
@Composable
fun Root(game: Game, nfcReady: Boolean, onSignIn: () -> Unit) {
    val profile by game.profile.collectAsStateWithLifecycle()
    val status by game.status.collectAsStateWithLifecycle()
    val contact by game.contact.state.collectAsStateWithLifecycle()
    val update by game.updater.state.collectAsStateWithLifecycle()

    var settings by remember { mutableStateOf(false) }

    var seconds by remember { mutableLongStateOf(0L) }
    LaunchedEffect(profile.ledger) {
        while (true) {
            seconds = profile.ledger.secondsAt(SystemClock.elapsedRealtime())
            delay(TICK_MILLIS)
        }
    }

    val lang = if (profile.language.isEmpty()) Lang.ofSystem() else Lang.of(profile.language)
    val words = strings(lang)

    CompositionLocalProvider(LocalStrings provides words) {
        // Landscape puts the camera hole and the status bar on the edges the text
        // runs along. Everything is inset once, here; the ime is left out so the
        // keyboard in settings does not crush a screen that is already short.
        val safe = WindowInsets.systemBars.union(WindowInsets.displayCutout)
        Box(Modifier.fillMaxSize().windowInsetsPadding(safe)) {
            when {
                !profile.signedIn -> SignInScreen(
                    working = status is Status.Working,
                    error = (status as? Status.Failed)?.let { words.say(it.trouble) },
                    onSignIn = onSignIn,
                )

                // Death outranks everything, settings included: a zeroed player
                // has one screen and nothing to do on it.
                seconds == 0L -> DeathScreen(
                    livedSeconds = ((profile.diedAt ?: System.currentTimeMillis() / 1000) - profile.bornAt)
                        .coerceAtLeast(0),
                )

                contact != null -> ContactScreen(contact!!, profile.name)

                settings -> SettingsScreen(
                    name = profile.name,
                    lang = lang,
                    update = update,
                    onSave = game::rename,
                    onLanguage = { game.setLanguage(it.code) },
                    onUpdate = game.updater::act,
                    onBack = { settings = false },
                )

                else -> CounterScreen(
                    seconds = seconds,
                    name = profile.name,
                    hint = when {
                        !nfcReady -> words.turnOnNfc
                        status is Status.Failed -> words.offline
                        else -> words.touch
                    },
                    signal = when {
                        !nfcReady -> Signal.NO_NFC
                        status is Status.Failed -> Signal.TROUBLE
                        else -> Signal.READY
                    },
                    onSettings = { settings = true },
                )
            }
        }
    }
}

private const val TICK_MILLIS = 250L
