package io.github.urionsisdi.nfcintime

import android.app.Activity
import android.content.Context
import android.util.Log
import io.github.urionsisdi.nfcintime.auth.Login
import io.github.urionsisdi.nfcintime.auth.TelegramMissing
import io.github.urionsisdi.nfcintime.auth.TelegramUnreachable
import io.github.urionsisdi.nfcintime.crypto.DeviceKey
import io.github.urionsisdi.nfcintime.data.Profile
import io.github.urionsisdi.nfcintime.data.Store
import io.github.urionsisdi.nfcintime.net.Api
import io.github.urionsisdi.nfcintime.net.ApiException
import io.github.urionsisdi.nfcintime.net.CODE_KEY_UNKNOWN
import io.github.urionsisdi.nfcintime.nfc.Contact
import io.github.urionsisdi.nfcintime.sensor.Gravity
import io.github.urionsisdi.nfcintime.time.Clock
import io.github.urionsisdi.nfcintime.time.SystemClockSource
import io.github.urionsisdi.nfcintime.update.Updater
import io.github.urionsisdi.nfcintime.ui.Haptics
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TAG = "nfcit.game"

/**
 * Why the last attempt did not go through. The interface names these itself:
 * the exception's own text is English, technical, and written for the log.
 */
enum class Trouble {
    NO_TELEGRAM,
    TELEGRAM_UNREACHABLE,
    REFUSED,
    OFFLINE,

    /** The key is gone from the server, so the installation has to register again. */
    SIGNED_OUT,
}

/** What the app is doing about the server right now. */
sealed interface Status {
    data object Idle : Status
    data object Working : Status
    data class Failed(val trouble: Trouble) : Status
}

/**
 * Everything the app is: the local ledger, the server it defers to, and the
 * contact that moves time between two phones. One instance per process, held by
 * [App] — there is only ever one player on a device.
 */
class Game(context: Context, private val scope: CoroutineScope) {
    private val clock: Clock = SystemClockSource
    private val store = Store(context, clock)
    private val api = Api(BuildConfig.BASE_URL, clock)
    private val gravity = Gravity(context)

    val updater = Updater(context, api, scope)

    val contact = Contact(
        store = store,
        gravity = gravity,
        clock = clock,
        haptics = Haptics(context),
        scope = scope,
        onSettled = ::sync,
    )

    val profile: StateFlow<Profile> = store.profile
        .onEach { contact.observeProfile(it) }
        .stateIn(scope, SharingStarted.Eagerly, Profile())

    private val _status = MutableStateFlow<Status>(Status.Idle)
    val status: StateFlow<Status> = _status.asStateFlow()

    /**
     * Conflated: a contact closes a checkpoint twice a second, and every one of
     * them wants the result reported. The server counts requests per minute, so
     * the asks collapse into one and the trailing one still runs — nothing is
     * lost either way, because unsent transfers sit in the store until accepted.
     */
    private val syncs = Channel<Unit>(Channel.CONFLATED)

    fun start() {
        scope.launch(Dispatchers.IO) {
            store.rebase()
            if (store.current().signedIn) sync()
        }
        scope.launch(Dispatchers.IO) {
            for (ignored in syncs) {
                report()
                delay(MIN_SYNC_INTERVAL_MILLIS)
            }
        }
    }

    fun resume() = gravity.start()

    fun pause() = gravity.stop()

    /**
     * Registers this installation: a fresh key in the secure element, attested
     * against a challenge the server issued, tied to the Telegram account the
     * player just proved they hold.
     */
    fun signIn(activity: Activity, login: Login, name: String) {
        scope.launch {
            _status.value = Status.Working
            try {
                val idToken = login.idToken(activity)
                withContext(Dispatchers.IO) {
                    val chain = DeviceKey.generate(api.challenge())
                    val state = api.authTelegram(idToken, DeviceKey.publicKeyBase64(), chain, name, LISTED)
                    store.signIn(state)
                }
                _status.value = Status.Idle
            } catch (e: IOException) {
                fail("sign-in", e)
            }
        }
    }

    /** Asks for a report; the loop in [start] decides when it actually goes out. */
    fun sync() {
        syncs.trySend(Unit)
    }

    /** Reports what happened offline and takes back whatever the server says. */
    private suspend fun report() {
        val me = store.current()
        if (!me.signedIn) return
        _status.value = Status.Working
        try {
            val queued = store.pending()
            val state = api.sync(me.keyId, me.name, LISTED, queued)
            store.adopt(state)
            store.drop(state.settled.toSet())
            _status.value = Status.Idle
        } catch (e: ApiException) {
            // Signing in on a second phone revokes this one's key, and the server
            // says so on every sync afterwards. Without forgetting the key the app
            // would sit on "no connection" for good: the sign-in screen only comes
            // back when there is no key to sit on.
            if (e.code == CODE_KEY_UNKNOWN) {
                store.forgetKey()
                Log.w(TAG, "device key is no longer registered, signing out")
                _status.value = Status.Failed(Trouble.SIGNED_OUT)
            } else {
                fail("sync", e)
            }
        } catch (e: IOException) {
            fail("sync", e)
        }
    }

    fun rename(name: String) {
        scope.launch(Dispatchers.IO) {
            store.rename(name)
            sync()
        }
    }

    fun setLanguage(code: String) {
        scope.launch(Dispatchers.IO) { store.setLanguage(code) }
    }

    private fun fail(what: String, e: IOException) {
        Log.w(TAG, "$what failed", e)
        _status.value = Status.Failed(
            when (e) {
                is TelegramMissing -> Trouble.NO_TELEGRAM
                is TelegramUnreachable -> Trouble.TELEGRAM_UNREACHABLE
                is ApiException -> Trouble.REFUSED
                else -> Trouble.OFFLINE
            },
        )
    }

    private companion object {
        /** Everyone is on the board: there is one world and one table of it. */
        const val LISTED = true

        /** Below the server's own rate limit, which is thirty a minute. */
        const val MIN_SYNC_INTERVAL_MILLIS = 2_500L
    }
}
