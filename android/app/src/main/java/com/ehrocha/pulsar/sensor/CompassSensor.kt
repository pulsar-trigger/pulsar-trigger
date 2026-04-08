/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Copyright (C) 2026 Pulsar Trigger contributors
 */

package com.ehrocha.pulsar.sensor

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/** Orientation reading from phone sensors. */
data class OrientationReading(
    /** Magnetic azimuth in degrees [0..360). Apply declination externally. */
    val azimuthDeg: Float,
    /** Pitch (tilt) in degrees [−90..90]. Positive = phone tilted back. */
    val pitchDeg: Float,
)

/**
 * Provides a Flow of phone orientation (azimuth + pitch).
 *
 * Uses TYPE_ACCELEROMETER + TYPE_MAGNETIC_FIELD → getRotationMatrix → getOrientation.
 * Magnetic declination must be applied externally to convert to true north.
 *
 * ── Formula ──────────────────────────────────────────────────────────────
 *   1. SensorManager.getRotationMatrix(R, null, gravity, geomagnetic)
 *   2. SensorManager.getOrientation(R, values)
 *      values[0] = azimuth in radians [-π..π] from magnetic north
 *      values[1] = pitch in radians [-π..π]
 *   3. magneticAzimuth = Math.toDegrees(values[0]).mod(360)
 *   4. pitch = −Math.toDegrees(values[1])  (negate: face-up tilt = positive)
 */
class CompassSensor(context: Context) {

    private val sensorManager =
        context.getSystemService(Context.SENSOR_SERVICE) as SensorManager

    /**
     * Emits [OrientationReading] with magnetic azimuth and pitch.
     * Apply [android.hardware.GeomagneticField] declination to get true north.
     */
    fun orientationFlow(): Flow<OrientationReading> = callbackFlow {
        val gravity = FloatArray(3)
        val geomagnetic = FloatArray(3)
        val rotationMatrix = FloatArray(9)
        val orientation = FloatArray(3)
        var hasGravity = false
        var hasMagnetic = false

        // Low-pass filter state (EMA)
        val alpha = 0.12f
        var filteredAz = Float.NaN
        var filteredPitch = Float.NaN

        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                when (event.sensor.type) {
                    Sensor.TYPE_ACCELEROMETER -> {
                        System.arraycopy(event.values, 0, gravity, 0, 3)
                        hasGravity = true
                    }
                    Sensor.TYPE_MAGNETIC_FIELD -> {
                        System.arraycopy(event.values, 0, geomagnetic, 0, 3)
                        hasMagnetic = true
                    }
                }

                if (hasGravity && hasMagnetic) {
                    val ok = SensorManager.getRotationMatrix(
                        rotationMatrix, null, gravity, geomagnetic,
                    )
                    if (ok) {
                        SensorManager.getOrientation(rotationMatrix, orientation)
                        val rawAz = Math.toDegrees(orientation[0].toDouble())
                            .toFloat()
                            .mod(360f)
                        val rawPitch = -Math.toDegrees(orientation[1].toDouble()).toFloat()

                        // Low-pass filter (EMA) to smooth jitter
                        if (filteredAz.isNaN()) {
                            filteredAz = rawAz
                            filteredPitch = rawPitch
                        } else {
                            // Circular EMA for azimuth (handles 0°/360° wrap)
                            val dAz = ((rawAz - filteredAz + 540f) % 360f) - 180f
                            filteredAz = (filteredAz + alpha * dAz).mod(360f)
                            // Linear EMA for pitch
                            filteredPitch += alpha * (rawPitch - filteredPitch)
                        }
                        trySend(OrientationReading(filteredAz, filteredPitch))
                    }
                }
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }

        val accel = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        val mag = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)

        accel?.let {
            sensorManager.registerListener(listener, it, SensorManager.SENSOR_DELAY_UI)
        }
        mag?.let {
            sensorManager.registerListener(listener, it, SensorManager.SENSOR_DELAY_UI)
        }

        awaitClose {
            sensorManager.unregisterListener(listener)
        }
    }
}
