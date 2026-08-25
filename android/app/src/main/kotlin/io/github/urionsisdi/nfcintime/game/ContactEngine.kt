package io.github.urionsisdi.nfcintime.game

import kotlin.math.abs

data class ContactState(
    /** Held contact, the only thing the multiplier depends on. */
    val contactSeconds: Double,
    /** Seconds per second, signed toward this device. */
    val flow: Double,
    /** Everything moved so far, signed toward this device. */
    val net: Long,
    val myBalance: Long,
    val peerBalance: Long,
    /** Set once either side has been drained: the contact ends in a death. */
    val drained: Boolean,
)

/**
 * The whole game, as arithmetic. Both phones run this over the same inputs — the
 * two gravity readings and the tick length the reader measured — so both arrive
 * at the same total without exchanging it, and the settlement at the end is a
 * signature over a number neither side had to be trusted for.
 *
 * That is also why the tick length comes from the wire rather than from a local
 * clock: two clocks would drift, and the sides would sign different numbers.
 */
class ContactEngine(myBalance: Long, peerBalance: Long) {
    private val deadZone = DeadZone()
    private var mine = myBalance.toDouble()
    private var theirs = peerBalance.toDouble()
    private var held = 0.0
    private var moved = 0L
    private var carry = 0.0
    private var signed = 0L
    private var drained = false

    fun tick(dtMillis: Int, gzSelf: Double, gzPeer: Double): ContactState {
        val dt = dtMillis / 1000.0
        val shape = deadZone.shape(dominance(gzSelf, gzPeer))
        val flow = rate(held) * shape
        if (shape != 0.0) held = (held + dt).coerceAtMost(MAX_CONTACT_SECONDS)

        val wanted = flow * dt + carry
        var whole = wanted.toLong()
        val donor = if (whole > 0) theirs else mine
        val available = donor.toLong().coerceAtLeast(0)
        if (abs(whole) >= available) {
            whole = if (whole > 0) available else -available
            drained = true
        }
        carry = if (drained) 0.0 else wanted - whole

        moved += whole
        mine += whole - dt
        theirs += -whole - dt
        if (mine <= 0.0 || theirs <= 0.0) drained = true

        return state(flow)
    }

    /** Everything moved since the last settlement was signed. */
    fun pending(): Long = moved - signed

    /** Marks the pending amount as signed for; the next settlement starts from zero. */
    fun commit() {
        signed = moved
    }

    /** The state as it stands, without advancing anything. */
    fun state(flow: Double = 0.0) = ContactState(
        contactSeconds = held,
        flow = flow,
        net = moved,
        myBalance = mine.toLong().coerceAtLeast(0),
        peerBalance = theirs.toLong().coerceAtLeast(0),
        drained = drained,
    )

    /**
     * True when a settlement proposed by the other side is close enough to sign.
     * The two integrals differ by at most the last tick, which the reader may have
     * counted after the card had already answered it.
     */
    fun agreesWith(net: Long): Boolean = abs(net - pending()) <= lastTickBound()

    private fun lastTickBound(): Long =
        (rate(held) * MAX_TICK_SECONDS).toLong().coerceAtLeast(1)

    companion object {
        /** A round longer than this is a stalled transport, not a round. */
        const val MAX_TICK_SECONDS = 0.25
    }
}
