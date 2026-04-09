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
    /** Roll in degrees [−180..180]. 0 = phone is level side-to-side. */
    val rollDeg: Float = 0f,
    /** Sensor accuracy: UNRELIABLE(0), LOW(1), MEDIUM(2), HIGH(3). */
    val compassAccuracy: Int = SensorManager.SENSOR_STATUS_ACCURACY_HIGH,
)

/**
 * Provides a Flow of phone orientation (azimuth, pitch, roll).
 *
 * Uses TYPE_ROTATION_VECTOR — hardware-fused sensor (Kalman filter in the
 * sensor HAL combining gyroscope, accelerometer, and magnetometer). This is
 * smoother, drift-free, and gyro-stabilized; no manual EMA filter needed.
 *
 * Magnetic declination must be applied externally to convert to true north.
 *
 * ── Formula ──────────────────────────────────────────────────────────────
 *   1. SensorManager.getRotationMatrixFromVector(R, rotationVector)
 *   2. SensorManager.remapCoordinateSystem(R, AXIS_X, AXIS_Z, remappedR)
 *      (remap for phone lying flat on its back, top edge as pointer)
 *   3. SensorManager.getOrientation(remappedR, values)
 *      values[0] = azimuth, values[1] = pitch, values[2] = roll
 */
class CompassSensor(context: Context) {

    private val sensorManager =
        context.getSystemService(Context.SENSOR_SERVICE) as SensorManager

    /**
     * Emits [OrientationReading] with magnetic azimuth, pitch, and roll.
     * Apply [android.hardware.GeomagneticField] declination to get true north.
     */
    fun orientationFlow(): Flow<OrientationReading> = callbackFlow {
        val rotationMatrix = FloatArray(9)
        val remappedMatrix = FloatArray(9)
        val orientation = FloatArray(3)
        var sensorAccuracy = SensorManager.SENSOR_STATUS_ACCURACY_HIGH

        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                if (event.sensor.type != Sensor.TYPE_ROTATION_VECTOR) return

                SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)

                // Remap for phone lying flat (screen up, top edge = pointer):
                // X stays X, Y becomes Z
                SensorManager.remapCoordinateSystem(
                    rotationMatrix,
                    SensorManager.AXIS_X,
                    SensorManager.AXIS_Z,
                    remappedMatrix,
                )

                SensorManager.getOrientation(remappedMatrix, orientation)

                val azimuth = Math.toDegrees(orientation[0].toDouble())
                    .toFloat()
                    .mod(360f)
                val pitch = -Math.toDegrees(orientation[1].toDouble()).toFloat()
                val roll = Math.toDegrees(orientation[2].toDouble()).toFloat()

                trySend(OrientationReading(azimuth, pitch, roll, sensorAccuracy))
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
                if (sensor?.type == Sensor.TYPE_ROTATION_VECTOR) {
                    sensorAccuracy = accuracy
                }
            }
        }

        val rotationSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        rotationSensor?.let {
            sensorManager.registerListener(listener, it, SensorManager.SENSOR_DELAY_UI)
        }

        awaitClose {
            sensorManager.unregisterListener(listener)
        }
    }
}
