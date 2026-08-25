package io.github.urionsisdi.nfcintime.time

/**
 * The balance as it is stored: a number of seconds and the monotonic reading it
 * was true at. Living costs a second per second, so the remainder is a
 * subtraction rather than a timer, and killing the process changes nothing.
 *
 * A reboot resets `elapsedRealtime` to zero and the anchor with it, which is why
 * [Ledger] is always saved together with the boot id it belongs to
 * (see `data.Store`).
 */
data class Ledger(val seconds: Long, val anchorMillis: Long) {

    fun secondsAt(elapsedMillis: Long): Long {
        val spent = (elapsedMillis - anchorMillis) / 1000
        return (seconds - spent).coerceAtLeast(0)
    }

    /** Re-anchors to now, dropping the time already spent into the number itself. */
    fun settled(elapsedMillis: Long): Ledger = Ledger(secondsAt(elapsedMillis), elapsedMillis)

    fun withDelta(delta: Long, elapsedMillis: Long): Ledger =
        Ledger((secondsAt(elapsedMillis) + delta).coerceAtLeast(0), elapsedMillis)

    companion object {
        fun of(seconds: Long, clock: Clock) = Ledger(seconds, clock.elapsedMillis())
    }
}
