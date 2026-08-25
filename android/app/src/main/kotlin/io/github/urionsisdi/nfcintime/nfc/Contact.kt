package io.github.urionsisdi.nfcintime.nfc

import android.nfc.tech.IsoDep
import android.util.Base64
import android.util.Log
import io.github.urionsisdi.nfcintime.crypto.DeviceKey
import io.github.urionsisdi.nfcintime.crypto.Transfer
import io.github.urionsisdi.nfcintime.crypto.newNonce
import io.github.urionsisdi.nfcintime.crypto.transferHash
import io.github.urionsisdi.nfcintime.crypto.transferMessage
import io.github.urionsisdi.nfcintime.crypto.verifySignature
import io.github.urionsisdi.nfcintime.data.Profile
import io.github.urionsisdi.nfcintime.data.Store
import io.github.urionsisdi.nfcintime.game.ContactEngine
import io.github.urionsisdi.nfcintime.game.ContactState
import io.github.urionsisdi.nfcintime.game.rate
import io.github.urionsisdi.nfcintime.sensor.Gravity
import io.github.urionsisdi.nfcintime.time.Clock
import io.github.urionsisdi.nfcintime.ui.Direction
import io.github.urionsisdi.nfcintime.ui.Haptics
import java.io.IOException
import kotlin.math.abs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONException
import org.json.JSONObject

private const val TAG = "nfcit.contact"

/** What the contact screen draws. */
data class ContactUi(
    val peerName: String,
    val direction: Direction,
    val ratePerSecond: Double,
    val balance: Long,
)

/**
 * A contact, from either end. The transport role is decided once — whoever spots
 * the other first is the reader — and never changes; the game's active and
 * passive live on top of it and swap as often as the hands do.
 *
 * The reader drives: it measures the round, sends its gravity, and proposes the
 * settlement. The card answers with its own gravity and signs, or refuses. Both
 * feed identical numbers into identical arithmetic, so the amount is agreed
 * rather than negotiated.
 */
class Contact(
    private val store: Store,
    private val gravity: Gravity,
    private val clock: Clock,
    private val haptics: Haptics,
    private val scope: CoroutineScope,
    private val onSettled: () -> Unit,
) {
    private val _state = MutableStateFlow<ContactUi?>(null)
    val state: StateFlow<ContactUi?> = _state.asStateFlow()

    @Volatile
    private var profile: Profile = Profile()

    @Volatile
    private var session: Session? = null

    fun observeProfile(profile: Profile) {
        this.profile = profile
    }

    fun onApdu(apdu: ByteArray): ByteArray {
        if (isSelect(apdu)) {
            close()
            return SW_OK
        }
        val instruction = instruction(apdu) ?: return SW_UNKNOWN
        val data = payload(apdu) ?: return SW_UNKNOWN
        return try {
            when (instruction) {
                INS_HELLO -> onHello(data)
                INS_KEY -> onKey(data)
                INS_TICK -> onTick(data)
                INS_SETTLE -> onSettle(data)
                INS_CONFIRM -> onConfirm(data)
                else -> SW_UNKNOWN
            }
        } catch (e: JSONException) {
            Log.w(TAG, "malformed frame", e)
            SW_REFUSED
        }
    }

    fun onDeactivated() = close()

    private fun onHello(data: ByteArray): ByteArray {
        val me = profile
        if (!me.signedIn) return SW_REFUSED
        val peer = Hello.decode(data)
        if (peer.tgId == me.tgId) return SW_REFUSED

        val balance = me.ledger.secondsAt(clock.elapsedMillis())
        // Zero is the end of it: the dead neither give nor take.
        if (balance <= 0) return SW_REFUSED
        val engine = ContactEngine(balance, peer.balance)
        session = Session(engine, me.tgId, peer)
        haptics.reset()
        publish(peer.name, engine.state())
        return ok(Hello(me.tgId, me.name, balance).encode())
    }

    private fun onKey(data: ByteArray): ByteArray {
        val current = session ?: return SW_REFUSED
        current.peerKey = Base64.decode(data.toString(Charsets.UTF_8), Base64.NO_WRAP)
        return ok(DeviceKey.publicKeyBase64().toByteArray())
    }

    private fun onTick(data: ByteArray): ByteArray {
        val current = session ?: return SW_REFUSED
        val tick = Tick.decode(data)
        val state = current.engine.tick(boundedMillis(tick.millis), gravity.z, tick.gravityZ)
        publish(current.peer.name, state)
        return ok(Tick(gravity.z, 0, state.myBalance).encode())
    }

    /** Signs the reader's proposal, but only if our own integral says the same. */
    private fun onSettle(data: ByteArray): ByteArray {
        val current = session ?: return SW_REFUSED
        val proposal = transferOf(JSONObject(data.toString(Charsets.UTF_8)))
        if (!accepts(current, proposal)) {
            Log.w(TAG, "settlement refused: ${proposal.amount} against ${current.engine.pending()}")
            return SW_REFUSED
        }
        val signature = DeviceKey.sign(transferMessage(proposal))
        current.engine.commit()
        current.proposal = if (proposal.to == current.me) {
            proposal.copy(toSig = signature)
        } else {
            proposal.copy(fromSig = signature)
        }
        return ok(signature.toByteArray())
    }

    /** The reader's half of the signature closes the record on this side too. */
    private fun onConfirm(data: ByteArray): ByteArray {
        val current = session ?: return SW_REFUSED
        val proposal = current.proposal ?: return SW_REFUSED
        val peerKey = current.peerKey ?: return SW_REFUSED
        val signature = data.toString(Charsets.UTF_8)
        if (!verifySignature(peerKey, transferMessage(proposal), signature)) return SW_REFUSED

        val settled = if (proposal.to == current.me) {
            proposal.copy(fromSig = signature)
        } else {
            proposal.copy(toSig = signature)
        }
        current.proposal = null
        record(settled, chainHash = null)
        return SW_OK
    }

    /** Runs a whole contact against a discovered tag; returns when it breaks. */
    fun runReader(tag: IsoDep) {
        val me = profile
        if (!me.signedIn) return
        var current: Session? = null
        try {
            tag.timeout = TAG_TIMEOUT_MILLIS
            tag.connect()
            if (body(tag.transceive(selectAid())) == null) return

            val balance = me.ledger.secondsAt(clock.elapsedMillis())
            if (balance <= 0) return
            val hello = body(tag.transceive(command(INS_HELLO, Hello(me.tgId, me.name, balance).encode())))
                ?.let { Hello.decode(it) } ?: return
            if (hello.tgId == me.tgId) return

            val session = Session(ContactEngine(balance, hello.balance), me.tgId, hello, me.prevHash)
                .also {
                    current = it
                    this.session = it
                }
            session.peerKey = body(tag.transceive(command(INS_KEY, DeviceKey.publicKeyBase64().toByteArray())))
                ?.let { Base64.decode(it.toString(Charsets.UTF_8), Base64.NO_WRAP) } ?: return

            haptics.reset()
            drive(tag, session)
        } catch (e: IOException) {
            // A break in the middle is the normal ending: hands come apart.
            Log.i(TAG, "contact ended: ${e.message}")
        } catch (e: JSONException) {
            Log.w(TAG, "malformed frame from peer", e)
        } finally {
            runCatching { tag.close() }
            close()
        }
    }

    private fun drive(tag: IsoDep, session: Session) {
        var previous = clock.elapsedMillis()
        var settledAt = previous
        while (true) {
            val now = clock.elapsedMillis()
            val millis = boundedMillis((now - previous).toInt())
            previous = now

            val answer = body(tag.transceive(command(INS_TICK, Tick(gravity.z, millis, 0).encode())))
                ?: return
            val peer = Tick.decode(answer)
            val state = session.engine.tick(millis, gravity.z, peer.gravityZ)
            publish(session.peer.name, state)

            if (state.drained) {
                settle(tag, session)
                return
            }
            if (now - settledAt >= CHECKPOINT_MILLIS) {
                // Anything not signed for is lost when the hands come apart, and
                // at this rate a second of unsigned contact is years. So the
                // record is closed and reopened several times a second, and a
                // break costs at most the last fragment.
                if (!settle(tag, session)) return
                settledAt = now
            }

            val idle = TICK_INTERVAL_MILLIS - (clock.elapsedMillis() - now)
            if (idle > 0) Thread.sleep(idle)
        }
    }

    /** Closes the open fragment of the contact. False means the contact is over. */
    private fun settle(tag: IsoDep, session: Session): Boolean {
        val net = session.engine.pending()
        if (net == 0L) return true

        val proposal = Transfer(
            nonce = newNonce(),
            from = if (net > 0) session.peer.tgId else session.me,
            to = if (net > 0) session.me else session.peer.tgId,
            amount = abs(net),
            prevHash = session.chainHash,
            signedAt = clock.unixSeconds(),
        )
        val message = transferMessage(proposal)
        val mine = DeviceKey.sign(message)

        val theirs = body(tag.transceive(command(INS_SETTLE, proposal.unsigned().toString().toByteArray())))
            ?.toString(Charsets.UTF_8) ?: return false
        val peerKey = session.peerKey ?: return false
        if (!verifySignature(peerKey, message, theirs)) return false
        session.engine.commit()

        val settled = if (proposal.to == session.me) {
            proposal.copy(toSig = mine, fromSig = theirs)
        } else {
            proposal.copy(fromSig = mine, toSig = theirs)
        }
        val hash = transferHash(settled)
        session.chainHash = hash
        record(settled, hash)
        // The peer is left without our half if this frame is lost. The record is
        // still valid: the server applies it from whoever brings it.
        return body(tag.transceive(command(INS_CONFIRM, mine.toByteArray()))) != null
    }

    private fun accepts(session: Session, proposal: Transfer): Boolean {
        val net = session.engine.pending()
        val toward = if (proposal.to == session.me) proposal.amount else -proposal.amount
        return when {
            proposal.from != session.me && proposal.to != session.me -> false
            proposal.from != session.peer.tgId && proposal.to != session.peer.tgId -> false
            proposal.amount <= 0 -> false
            abs(proposal.signedAt - clock.unixSeconds()) > MAX_SKEW_SECONDS -> false
            else -> session.engine.agreesWith(toward) && (net == 0L || (toward > 0) == (net > 0))
        }
    }

    private fun record(transfer: Transfer, chainHash: String?) {
        val me = profile
        val delta = if (transfer.to == me.tgId) transfer.amount else -transfer.amount
        val ledger = me.ledger.withDelta(delta, clock.elapsedMillis())
        scope.launch(Dispatchers.IO) {
            store.enqueue(transfer, ledger.seconds, chainHash)
            onSettled()
        }
    }

    private fun publish(peerName: String, state: ContactState) {
        val direction = when {
            state.flow > 0 -> Direction.TAKING
            state.flow < 0 -> Direction.LOSING
            else -> Direction.NEUTRAL
        }
        haptics.onFlow(direction)
        _state.value = ContactUi(
            peerName = peerName,
            direction = direction,
            ratePerSecond = rate(state.contactSeconds),
            balance = state.myBalance,
        )
    }

    private fun close() {
        session = null
        haptics.reset()
        _state.value = null
    }

    private fun transferOf(json: JSONObject) = Transfer(
        nonce = json.getString("nonce"),
        from = json.getString("from"),
        to = json.getString("to"),
        amount = json.getLong("amount"),
        prevHash = json.getString("prev_hash"),
        signedAt = json.getLong("signed_at"),
    )

    private fun selectAid(): ByteArray =
        byteArrayOf(0x00, 0xA4.toByte(), 0x04, 0x00, AID.size.toByte()) + AID + byteArrayOf(0x00)

    private fun boundedMillis(millis: Int): Int =
        millis.coerceIn(1, (ContactEngine.MAX_TICK_SECONDS * 1000).toInt())

    private class Session(
        val engine: ContactEngine,
        val me: String,
        val peer: Hello,
        chainHash: String = "",
    ) {
        @Volatile
        var peerKey: ByteArray? = null

        @Volatile
        var proposal: Transfer? = null

        /** Our own chain, advanced by every record we author. */
        @Volatile
        var chainHash: String = chainHash
    }

    private companion object {
        const val TICK_INTERVAL_MILLIS = 60L
        const val CHECKPOINT_MILLIS = 500L
        const val TAG_TIMEOUT_MILLIS = 1500
        const val MAX_SKEW_SECONDS = 300
    }
}
