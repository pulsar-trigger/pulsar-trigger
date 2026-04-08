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

/**
 * Provides a Flow of compass azimuth (true north heading) in degrees [0..360).
 *
 * Uses TYPE_ACCELEROMETER + TYPE_MAGNETIC_FIELD → getRotationMatrix → getOrientation.
 * Magnetic declination must be applied externally to convert to true north.
 *
 * ── Formula ──────────────────────────────────────────────────────────────
 *   1. SensorManager.getRotationMatrix(R, null, gravity, geomagnetic)
 *   2. SensorManager.getOrientation(R, values)
 *      values[0] = azimuth in radians [-π..π] from magnetic north
 *   3. magneticAzimuth = Math.toDegrees(values[0]).mod(360)
 *   4. trueAzimuth = (magneticAzimuth + declination).mod(360)
 */
class CompassSensor(context: Context) {

    private val sensorManager =
        context.getSystemService(Context.SENSOR_SERVICE) as SensorManager

    /**
     * Emits magnetic azimuth in degrees [0..360).
     * Apply [android.hardware.GeomagneticField] declination to get true north.
     */
    fun azimuthFlow(): Flow<Float> = callbackFlow {
        val gravity = FloatArray(3)
        val geomagnetic = FloatArray(3)
        val rotationMatrix = FloatArray(9)
        val orientation = FloatArray(3)
        var hasGravity = false
        var hasMagnetic = false

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
                        // orientation[0] = azimuth in radians from magnetic north
                        val azimuthDeg = Math.toDegrees(orientation[0].toDouble())
                            .toFloat()
                            .mod(360f)
                        trySend(azimuthDeg)
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
