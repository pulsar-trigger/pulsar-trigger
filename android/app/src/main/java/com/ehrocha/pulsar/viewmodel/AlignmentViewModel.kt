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
 *   - TYPE_ROTATION_VECTOR → azimuth, pitch, and roll (hardware Kalman fusion)
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

    private val _roll = MutableStateFlow(0f)
    val roll: StateFlow<Float> = _roll

    private val _sensorsActive = MutableStateFlow(false)
    val sensorsActive: StateFlow<Boolean> = _sensorsActive

    /** Magnetometer accuracy: 0=UNRELIABLE, 1=LOW, 2=MEDIUM, 3=HIGH. */
    private val _compassAccuracy = MutableStateFlow(3)
    val compassAccuracy: StateFlow<Int> = _compassAccuracy

    // ── Compass calibration ──────────────────────────────────────────────
    /** Azimuth offset applied to correct for magnetic interference on the tracker. */
    private val _compassOffset = MutableStateFlow(0f)

    private val _calibrated = MutableStateFlow(false)
    val calibrated: StateFlow<Boolean> = _calibrated

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
                _trueAzimuth.value = (reading.azimuthDeg + _declination.value
                    + _compassOffset.value).mod(360f)
                _pitch.value = reading.pitchDeg
                _roll.value = reading.rollDeg
                _compassAccuracy.value = reading.compassAccuracy
                _sensorsActive.value = true
            }
        }
    }

    /**
     * Calibrate the compass for magnetic interference on the tracker.
     *
     * The user should place the phone on the tracker and physically aim the
     * tracker at the celestial pole (using PhotoPills or a known reference).
     * Calling this captures the difference between the current compass reading
     * and the known target azimuth, and applies it as a persistent offset.
     */
    fun calibrateCompass() {
        val rawAz = (_trueAzimuth.value - _compassOffset.value).mod(360f)
        val target = _targetAzimuth.value
        // offset = target − raw  (shortest path)
        var offset = (target - rawAz).mod(360f)
        if (offset > 180f) offset -= 360f
        _compassOffset.value = offset
        _calibrated.value = true
        // Re-apply immediately
        _trueAzimuth.value = (rawAz + offset).mod(360f)
        Log.i(TAG, "Compass calibrated: offset=%.1f°  raw=%.1f°  target=%.0f°"
            .format(offset, rawAz, target))
    }

    fun clearCalibration() {
        _compassOffset.value = 0f
        _calibrated.value = false
        Log.i(TAG, "Compass calibration cleared")
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
