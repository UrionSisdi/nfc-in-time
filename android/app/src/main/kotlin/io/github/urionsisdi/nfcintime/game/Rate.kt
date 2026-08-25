package io.github.urionsisdi.nfcintime.game

import kotlin.math.pow

/** A minute per second at the moment of contact. */
const val BASE_RATE = 60.0

/**
 * Seconds of contact that double the rate. Doubling every second put the counter
 * past reading speed by the sixth, and watching your own digits fall is the
 * whole of it; a second and a half stretches the same curve by half again.
 */
const val DOUBLING_SECONDS = 1.5

/**
 * The starting twenty-five years drain in about thirty-four seconds of held
 * contact, so anything past a minute is already beyond every balance in the
 * game. The cap only keeps the arithmetic finite; it is never reached in play.
 */
const val MAX_CONTACT_SECONDS = 120.0

fun rate(contactSeconds: Double): Double =
    BASE_RATE * 2.0.pow(contactSeconds.coerceIn(0.0, MAX_CONTACT_SECONDS) / DOUBLING_SECONDS)
