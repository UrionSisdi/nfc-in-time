package io.github.urionsisdi.nfcintime.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import io.github.urionsisdi.nfcintime.crypto.GENESIS_HASH
import io.github.urionsisdi.nfcintime.crypto.Transfer
import io.github.urionsisdi.nfcintime.time.Clock
import io.github.urionsisdi.nfcintime.time.Ledger
import kotlin.math.abs
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.json.JSONObject

private val Context.preferences: DataStore<Preferences> by preferencesDataStore("nfcit")

/**
 * The only thing kept on the device: who we are, what the balance was last time
 * anyone authoritative said so, and the transfers still waiting to be reported.
 * The server holds the truth, so nothing here is worth backing up.
 */
class Store(context: Context, private val clock: Clock) {
    private val data = context.applicationContext.preferences

    val profile: Flow<Profile> = data.data.map { it.toProfile() }

    suspend fun current(): Profile = profile.first()

    suspend fun signIn(state: PlayerState) {
        data.edit {
            it[TG_ID] = state.tgId
            it[KEY_ID] = state.keyId
            it[NAME] = state.name
            it.life(state)
            it.anchor(state.balanceSeconds)
        }
    }

    /** The server's number replaces ours outright: ours is a cache, not a claim. */
    suspend fun adopt(state: PlayerState) {
        data.edit {
            it[NAME] = state.name
            it[KEY_ID] = state.keyId
            it.life(state)
            it.anchor(state.balanceSeconds)
        }
    }

    /**
     * Drops the key this installation was registered with, which is what puts the
     * app back on the sign-in screen. The queue of settled transfers stays: they
     * are signed records, the server checks them against every key the player
     * ever held, and a fresh key on the same account can still report them.
     */
    suspend fun forgetKey() {
        data.edit { it.remove(KEY_ID) }
    }

    suspend fun rename(name: String) {
        data.edit { it[NAME] = name }
    }

    suspend fun setLanguage(code: String) {
        data.edit { it[LANGUAGE] = code }
    }

    /**
     * Queues a settled transfer and moves the balance by it. [chainHash] is set
     * only by the side that authored the record — the other one is a party to it,
     * not a link in its chain.
     */
    suspend fun enqueue(transfer: Transfer, balance: Long, chainHash: String? = null) {
        data.edit {
            it[PENDING] = (it[PENDING] ?: emptySet()) + transfer.json().toString()
            if (chainHash != null) it[PREV_HASH] = chainHash
            it.anchor(balance)
        }
    }

    suspend fun pending(): List<Transfer> =
        (data.data.first()[PENDING] ?: emptySet()).map { Transfer.of(JSONObject(it)) }

    suspend fun drop(nonces: Set<String>) {
        if (nonces.isEmpty()) return
        data.edit { prefs ->
            prefs[PENDING] = (prefs[PENDING] ?: emptySet()).filterNot {
                JSONObject(it).optString("nonce") in nonces
            }.toSet()
        }
    }

    /**
     * Reconciles the stored anchor with the current boot. `elapsedRealtime` counts
     * from the last boot, so an anchor taken before one measures nothing: what the
     * phone spent switched off is charged from the wall clock instead. That clock
     * is the player's to move, and the next sync replaces the result with the
     * server's number regardless.
     */
    suspend fun rebase() {
        data.edit { prefs ->
            val stored = prefs[ANCHOR_BOOT]
            if (stored != null && abs(bootUnix() - stored) <= BOOT_DRIFT_SECONDS) return@edit

            val offline = (clock.unixSeconds() - (prefs[ANCHOR_UNIX] ?: clock.unixSeconds()))
                .coerceAtLeast(0)
            val left = ((prefs[BALANCE] ?: 0) - offline).coerceAtLeast(0)
            prefs.anchor(left)
        }
    }

    /**
     * The instant this boot began, in unix seconds: the wall clock less the
     * uptime. It holds still while the phone is up and jumps forward by the time
     * it spent off, so an anchor can name the boot it belongs to. Comparing
     * uptimes instead cannot tell a reboot from a long session — a phone up
     * longer now than when the anchor was taken looks like one that never
     * restarted.
     */
    private fun bootUnix(): Long = clock.unixSeconds() - clock.elapsedMillis() / 1000

    private fun MutablePreferences.life(state: PlayerState) {
        this[BORN_AT] = state.bornAt
        state.diedAt?.let { this[DIED_AT] = it }
    }

    private fun MutablePreferences.anchor(seconds: Long) {
        this[BALANCE] = seconds
        this[ANCHOR_ELAPSED] = clock.elapsedMillis()
        this[ANCHOR_UNIX] = clock.unixSeconds()
        this[ANCHOR_BOOT] = bootUnix()
    }

    private fun Preferences.toProfile() = Profile(
        tgId = this[TG_ID] ?: "",
        keyId = this[KEY_ID] ?: "",
        name = this[NAME] ?: "",
        ledger = Ledger(this[BALANCE] ?: 0, this[ANCHOR_ELAPSED] ?: 0),
        prevHash = this[PREV_HASH] ?: GENESIS_HASH,
        language = this[LANGUAGE] ?: "",
        bornAt = this[BORN_AT] ?: 0,
        diedAt = this[DIED_AT],
    )

    private companion object {
        /** Two readings of one boot differ by NTP correction and a rounded second. */
        const val BOOT_DRIFT_SECONDS = 5L

        val TG_ID = stringPreferencesKey("tg_id")
        val KEY_ID = stringPreferencesKey("key_id")
        val NAME = stringPreferencesKey("name")
        val BALANCE = longPreferencesKey("balance_seconds")
        val ANCHOR_ELAPSED = longPreferencesKey("anchor_elapsed_millis")
        val ANCHOR_UNIX = longPreferencesKey("anchor_unix_seconds")
        val ANCHOR_BOOT = longPreferencesKey("anchor_boot_unix")
        val PREV_HASH = stringPreferencesKey("prev_hash")
        val LANGUAGE = stringPreferencesKey("language")
        val BORN_AT = longPreferencesKey("born_at")
        val DIED_AT = longPreferencesKey("died_at")
        val PENDING = stringSetPreferencesKey("pending_transfers")
    }
}
