package io.github.urionsisdi.nfcintime.sensor

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager

/**
 * The Z component of gravity, which is all the game asks of the hardware: face
 * down is about -9.8, face up about +9.8, on edge about 0. The axis is the
 * device's own and does not turn with the screen, so the landscape lock does not
 * enter into it.
 *
 * `TYPE_GRAVITY` where it exists — it already has the jerk of a moving hand
 * filtered out. Not every phone composes one, and a missing sensor would leave
 * the reading at a permanent zero, so the raw accelerometer stands in behind a
 * low pass of its own.
 */
class Gravity(context: Context) : SensorEventListener {
    private val manager = context.getSystemService(SensorManager::class.java)
    private val composed: Sensor? = manager?.getDefaultSensor(Sensor.TYPE_GRAVITY)
    private val sensor: Sensor? = composed ?: manager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    @Volatile
    var z: Double = 0.0
        private set

    fun start() {
        sensor?.let { manager?.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
    }

    fun stop() {
        manager?.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent) {
        val reading = event.values[2].toDouble()
        z = if (composed != null) reading else z + SMOOTHING * (reading - z)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private companion object {
        /** Enough to follow a turning wrist, slow enough to drop a footstep. */
        const val SMOOTHING = 0.15
    }
}
