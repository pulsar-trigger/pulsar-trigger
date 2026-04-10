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
 * ── Physical setup ───────────────────────────────────────────────────────
 *   Phone lies flat on its back on the tracker plate (screen facing sky,
 *   rear cameras touching the plate).  Top edge points toward the gears
 *   (polar axis / celestial pole).  The whole assembly tilts together.
 *
 * ── Why NO remapCoordinateSystem ─────────────────────────────────────────
 *   The default getOrientation() on the unremapped rotation matrix gives:
 *     azimuth = compass heading of device Y (top edge → pole)
 *     pitch   = tilt of top edge above horizontal (= tracker altitude)
 *     roll    = left-right tilt (0 = level)
 *   This is exactly what we need for a flat phone.
 *
 *   The commonly-seen remap(AXIS_X, AXIS_Z) is for an UPRIGHT phone
 *   (screen facing the user, top edge pointing up).  Applying it to a
 *   flat phone causes gimbal lock at pitch ≈ −90° — unusable.
 *
 * ── Formula ──────────────────────────────────────────────────────────────
 *   1. SensorManager.getRotationMatrixFromVector(R, rotationVector)
 *   2. SensorManager.getOrientation(R, values)
 *      values[0] = azimuth (rad), values[1] = pitch (rad), values[2] = roll (rad)
 *   3. azimuth = toDegrees(values[0]).mod(360)   → [0..360)
 *   4. pitch   = −toDegrees(values[1])           → positive = tilted up
 *   5. roll    = toDegrees(values[2])             → 0 = level
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
        val orientation = FloatArray(3)
        var sensorAccuracy = SensorManager.SENSOR_STATUS_ACCURACY_HIGH

        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                if (event.sensor.type != Sensor.TYPE_ROTATION_VECTOR) return

                SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)

                // No remap — the default rotation matrix gives correct
                // azimuth/pitch/roll for a phone lying flat on its back.
                SensorManager.getOrientation(rotationMatrix, orientation)

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
