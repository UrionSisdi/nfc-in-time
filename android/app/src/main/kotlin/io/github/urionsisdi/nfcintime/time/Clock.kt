package io.github.urionsisdi.nfcintime.time

import android.os.SystemClock

/**
 * Elapsed time is read from a monotonic source only. `currentTimeMillis` is set
 * by hand in the settings, and the whole game would come down to that.
 */
interface Clock {
    fun elapsedMillis(): Long

    /** Wall clock, used only for the timestamps the server checks against its own. */
    fun unixSeconds(): Long
}

object SystemClockSource : Clock {
    override fun elapsedMillis(): Long = SystemClock.elapsedRealtime()

    override fun unixSeconds(): Long = System.currentTimeMillis() / 1000
}
