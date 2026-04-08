/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Copyright (C) 2026 Pulsar Trigger contributors
 */

package com.ehrocha.pulsar.viewmodel

import android.annotation.SuppressLint
import android.app.Application
import android.hardware.GeomagneticField
import android.location.LocationManager
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ehrocha.pulsar.sensor.CompassSensor
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * ViewModel for Tracker Alignment Helper.
 *
 * Uses the phone's own sensors for **all** orientation data:
 *   - Accelerometer + magnetometer → azimuth (compass heading) and pitch (tilt)
 *   - GPS → latitude (target elevation) and magnetic declination
 *
 * No BLE connection to the Pulsar device is required.  Place the phone flat
 * on the tracker base and the crosshair guides the user to polar alignment.
 *
 * ── Formula summary ──────────────────────────────────────────────────────
 *   True Azimuth = (magneticAzimuth + magneticDeclination) mod 360
 *   Target Alt   = |latitude|   (elevation of the celestial pole)
 *   Target Az    = 180° (southern hemisphere) or 0° (northern hemisphere)
 */
class AlignmentViewModel(private val app: Application) : AndroidViewModel(app) {

    companion object {
        private const val TAG = "AlignmentVM"
    }

    // ── Sensor state ─────────────────────────────────────────────────────
    private val _trueAzimuth = MutableStateFlow(0f)
    val trueAzimuth: StateFlow<Float> = _trueAzimuth

    private val _pitch = MutableStateFlow(0f)
    val pitch: StateFlow<Float> = _pitch

    private val _sensorsActive = MutableStateFlow(false)
    val sensorsActive: StateFlow<Boolean> = _sensorsActive

    // ── Location & targets ───────────────────────────────────────────────
    private val _latitude = MutableStateFlow(0.0)
    val latitude: StateFlow<Double> = _latitude

    private val _declination = MutableStateFlow(0f)
    val declination: StateFlow<Float> = _declination

    private val _targetAltitude = MutableStateFlow(0f)
    val targetAltitude: StateFlow<Float> = _targetAltitude

    private val _targetAzimuth = MutableStateFlow(180f)
    val targetAzimuth: StateFlow<Float> = _targetAzimuth

    private val _locationReady = MutableStateFlow(false)
    val locationReady: StateFlow<Boolean> = _locationReady

    // ── Internal ─────────────────────────────────────────────────────────
    private val compassSensor = CompassSensor(app)
    private var sensorJob: Job? = null

    init {
        sensorJob = viewModelScope.launch {
            compassSensor.orientationFlow().collect { reading ->
                _trueAzimuth.value = (reading.azimuthDeg + _declination.value).mod(360f)
                _pitch.value = reading.pitchDeg
                _sensorsActive.value = true
            }
        }
    }

    // ── Location ─────────────────────────────────────────────────────────

    @SuppressLint("MissingPermission")
    fun acquireLocation() {
        val lm = app.getSystemService(LocationManager::class.java) ?: return
        val loc = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)
            ?: lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
        if (loc != null) {
            _latitude.value = loc.latitude

            val gf = GeomagneticField(
                loc.latitude.toFloat(),
                loc.longitude.toFloat(),
                loc.altitude.toFloat(),
                System.currentTimeMillis(),
            )
            _declination.value = gf.declination
            _targetAltitude.value = abs(loc.latitude).toFloat()
            _targetAzimuth.value = if (loc.latitude < 0) 180f else 0f
            _locationReady.value = true

            Log.i(TAG, "Location: %.4f, %.4f  decl=%.1f°  targetAlt=%.1f°  targetAz=%.0f°"
                .format(loc.latitude, loc.longitude, gf.declination,
                    _targetAltitude.value, _targetAzimuth.value))
        }
    }

    override fun onCleared() {
        super.onCleared()
        sensorJob?.cancel()
    }
}
