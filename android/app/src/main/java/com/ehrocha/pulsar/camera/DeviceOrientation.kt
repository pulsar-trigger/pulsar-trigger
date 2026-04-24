/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Copyright (C) 2026 Pulsar Trigger contributors
 */

package com.ehrocha.pulsar.camera

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Provides device azimuth (compass heading) and pitch (tilt up/down)
 * using the rotation vector sensor.
 */
class DeviceOrientation(context: Context) : SensorEventListener {

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val rotationSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)

    private val rotationMatrix = FloatArray(9)
    private val orientation = FloatArray(3)

    /** Azimuth in degrees (0 = North, 90 = East, 180 = South, 270 = West). */
    private val _azimuth = MutableStateFlow(0f)
    val azimuth: StateFlow<Float> = _azimuth

    /** Pitch in degrees (-90 = pointing straight down, 0 = horizon, 90 = zenith). */
    private val _pitch = MutableStateFlow(0f)
    val pitch: StateFlow<Float> = _pitch

    /** Roll in degrees. */
    private val _roll = MutableStateFlow(0f)
    val roll: StateFlow<Float> = _roll

    val isAvailable: Boolean get() = rotationSensor != null

    fun start() {
        rotationSensor?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
        }
    }

    fun stop() {
        sensorManager.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_ROTATION_VECTOR) return

        SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
        SensorManager.getOrientation(rotationMatrix, orientation)

        // orientation[0] = azimuth (rad), [1] = pitch (rad), [2] = roll (rad)
        _azimuth.value = Math.toDegrees(orientation[0].toDouble()).toFloat().let {
            if (it < 0) it + 360f else it
        }
        // When phone is held upright (camera mode), pitch from sensor is actually
        // how far up/down the camera points. Convert to degrees.
        _pitch.value = Math.toDegrees(orientation[1].toDouble()).toFloat()
        _roll.value = Math.toDegrees(orientation[2].toDouble()).toFloat()
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}
