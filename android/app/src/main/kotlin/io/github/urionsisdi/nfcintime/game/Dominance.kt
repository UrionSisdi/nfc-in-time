package io.github.urionsisdi.nfcintime.game

import kotlin.math.abs
import kotlin.math.sign

/** Full inversion: one phone face down at -9.81, the other face up at +9.81. */
const val GRAVITY_SPAN = 19.62

/**
 * Normalised dominance from the two gravity readings. Positive means this device
 * is the one on top and time flows toward it; both sides feed the same two
 * numbers in and reach exactly opposite conclusions, so they cannot disagree.
 *
 * The antennas are in the backs, so a contact holds the two phones back to back:
 * the upper one has its back turned down and its screen up, the lower one the
 * other way round. The hand on top therefore reads +9.81, not -9.81.
 */
fun dominance(gzSelf: Double, gzPeer: Double): Double =
    ((gzSelf - gzPeer) / GRAVITY_SPAN).coerceIn(-1.0, 1.0)

/**
 * The dead zone around neutral, with hysteresis: without it the flow chatters
 * back and forth on the threshold from nothing more than a shaking hand.
 *
 * 0.30 is a relative tilt of about 35 degrees between the two phones. Narrower
 * than that and holding a pair level by hand already starts a flow, because the
 * dominance runs over the whole 19.62 span and a few degrees of wrist is worth
 * more of it than it feels like.
 */
class DeadZone(private val enter: Double = 0.30, private val exit: Double = 0.22) {
    private var open = false

    /** Flow shape: 0 in neutral, ±1 at full inversion, sign taken from [dn]. */
    fun shape(dn: Double): Double {
        val magnitude = abs(dn)
        open = if (open) magnitude > exit else magnitude >= enter
        if (!open) return 0.0
        val t = ((magnitude - exit) / (1.0 - exit)).coerceIn(0.0, 1.0)
        return sign(dn) * t * t * (3.0 - 2.0 * t)
    }

    fun reset() {
        open = false
    }
}
