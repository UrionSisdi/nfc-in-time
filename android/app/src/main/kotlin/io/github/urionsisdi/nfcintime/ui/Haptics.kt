package io.github.urionsisdi.nfcintime.ui

import android.content.Context
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/**
 * Direction by touch. Nobody looks at the screen while wrestling for the top
 * hand, so taking and losing have to feel different: taking is a short double
 * pulse, losing one long fading one.
 */
class Haptics(context: Context) {
    private val vibrator: Vibrator? =
        context.getSystemService(VibratorManager::class.java)?.defaultVibrator

    private var last = Direction.NEUTRAL

    fun onFlow(direction: Direction) {
        if (direction == last) return
        last = direction
        when (direction) {
            Direction.TAKING -> play(longArrayOf(0, 22, 60, 22), intArrayOf(0, 160, 0, 160))
            Direction.LOSING -> play(longArrayOf(0, 180), intArrayOf(0, 90))
            Direction.NEUTRAL -> Unit
        }
    }

    fun reset() {
        last = Direction.NEUTRAL
    }

    private fun play(timings: LongArray, amplitudes: IntArray) {
        vibrator?.vibrate(VibrationEffect.createWaveform(timings, amplitudes, -1))
    }
}

enum class Direction { TAKING, LOSING, NEUTRAL }
