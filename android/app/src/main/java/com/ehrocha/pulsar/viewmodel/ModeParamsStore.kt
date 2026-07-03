/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Copyright (C) 2026 Pulsar Trigger contributors
 */

package com.ehrocha.pulsar.viewmodel

import android.content.SharedPreferences
import com.ehrocha.pulsar.AppConfig
import com.ehrocha.pulsar.ble.TriggerMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The selected trigger mode + its per-mode parameters — the "simple mode"
 * config the user edits on the mode screens and `sendConfig` packs for the
 * ESP32 firmware (the wizard/flow path builds its own `FlowStep`s and doesn't
 * touch this). Extracted from `PulsarViewModel` (audit H1 · `ModeParamsStore`).
 *
 * Only the **intervalometer** values persist — they're the last-edited working
 * set that also rides along in settings backup; astro / dark-frame / ramp are
 * session defaults. The ViewModel keeps its public `setX` / `selectMode` / flow
 * API by delegating here, so no call site moves. Same defaults + clamps as the
 * former inline state.
 */
class ModeParamsStore(private val prefs: SharedPreferences) {

    private val _currentMode = MutableStateFlow(TriggerMode.INTERVALOMETER)
    val currentMode: StateFlow<TriggerMode> = _currentMode.asStateFlow()
    fun selectMode(mode: TriggerMode) { _currentMode.value = mode }

    // ── Intervalometer — persisted (last-edited working values) ───────────
    private val _intervalMs = MutableStateFlow(prefs.getLong(KEY_INTV_INTERVAL, AppConfig.DEFAULT_INTERVAL_MS))
    val intervalMs: StateFlow<Long> = _intervalMs.asStateFlow()
    fun setIntervalMs(v: Long) {
        val c = v.coerceAtLeast(AppConfig.MIN_INTERVAL_MS)
        _intervalMs.value = c
        prefs.edit().putLong(KEY_INTV_INTERVAL, c).apply()
    }

    private val _exposureMs = MutableStateFlow(prefs.getLong(KEY_INTV_EXPOSURE, AppConfig.DEFAULT_EXPOSURE_MS))
    val exposureMs: StateFlow<Long> = _exposureMs.asStateFlow()
    fun setExposureMs(v: Long) {
        val c = v.coerceAtLeast(AppConfig.MIN_EXPOSURE_MS)
        _exposureMs.value = c
        prefs.edit().putLong(KEY_INTV_EXPOSURE, c).apply()
    }

    private val _shotCount = MutableStateFlow(prefs.getInt(KEY_INTV_COUNT, AppConfig.DEFAULT_SHOT_COUNT))
    val shotCount: StateFlow<Int> = _shotCount.asStateFlow()
    fun setShotCount(v: Int) {
        // 0 is the sentinel for "continuous — run until STOP".
        val c = v.coerceAtLeast(0)
        _shotCount.value = c
        prefs.edit().putInt(KEY_INTV_COUNT, c).apply()
    }

    private val _delayMs = MutableStateFlow(prefs.getLong(KEY_INTV_DELAY, AppConfig.DEFAULT_DELAY_MS))
    val delayMs: StateFlow<Long> = _delayMs.asStateFlow()
    fun setDelayMs(v: Long) {
        _delayMs.value = v
        prefs.edit().putLong(KEY_INTV_DELAY, v).apply()
    }

    // ── Astro — session defaults (not persisted) ──────────────────────────
    private val _astroFocalLength = MutableStateFlow(AppConfig.DEFAULT_FOCAL_LENGTH)
    val astroFocalLength: StateFlow<Int> = _astroFocalLength.asStateFlow()
    fun setAstroFocalLength(v: Int) { _astroFocalLength.value = v }

    private val _astroCropFactor = MutableStateFlow(AppConfig.DEFAULT_CROP_FACTOR)
    val astroCropFactor: StateFlow<Float> = _astroCropFactor.asStateFlow()
    fun setAstroCropFactor(v: Float) { _astroCropFactor.value = v }

    private val _astroRuleDivisor = MutableStateFlow(AppConfig.DEFAULT_RULE_DIVISOR)
    val astroRuleDivisor: StateFlow<Int> = _astroRuleDivisor.asStateFlow()
    fun setAstroRuleDivisor(v: Int) { _astroRuleDivisor.value = v }

    private val _astroShotCount = MutableStateFlow(AppConfig.DEFAULT_SHOT_COUNT)
    val astroShotCount: StateFlow<Int> = _astroShotCount.asStateFlow()
    fun setAstroShotCount(v: Int) { _astroShotCount.value = v.coerceAtLeast(0) }

    private val _astroDelayMs = MutableStateFlow(AppConfig.DEFAULT_ASTRO_DELAY_MS)
    val astroDelayMs: StateFlow<Long> = _astroDelayMs.asStateFlow()
    fun setAstroDelayMs(v: Long) { _astroDelayMs.value = v }

    private val _astroGapMs = MutableStateFlow(AppConfig.DEFAULT_ASTRO_GAP_MS)
    val astroGapMs: StateFlow<Long> = _astroGapMs.asStateFlow()
    fun setAstroGapMs(v: Long) { _astroGapMs.value = v.coerceAtLeast(AppConfig.MIN_ASTRO_GAP_MS) }

    // ── Dark Frame — session defaults ─────────────────────────────────────
    private val _darkFrameCount = MutableStateFlow(10)
    val darkFrameCount: StateFlow<Int> = _darkFrameCount.asStateFlow()
    fun setDarkFrameCount(v: Int) { _darkFrameCount.value = v.coerceAtLeast(AppConfig.MIN_SHOT_COUNT) }

    private val _darkFrameExposureMs = MutableStateFlow(AppConfig.DEFAULT_EXPOSURE_MS)
    val darkFrameExposureMs: StateFlow<Long> = _darkFrameExposureMs.asStateFlow()
    fun setDarkFrameExposureMs(v: Long) { _darkFrameExposureMs.value = v.coerceAtLeast(AppConfig.MIN_EXPOSURE_MS) }

    private val _darkFrameGapMs = MutableStateFlow(AppConfig.DEFAULT_ASTRO_GAP_MS)
    val darkFrameGapMs: StateFlow<Long> = _darkFrameGapMs.asStateFlow()
    fun setDarkFrameGapMs(v: Long) { _darkFrameGapMs.value = v.coerceAtLeast(AppConfig.MIN_ASTRO_GAP_MS) }

    // ── Exposure Ramp — session defaults ──────────────────────────────────
    private val _rampStartExposureMs = MutableStateFlow(500L)
    val rampStartExposureMs: StateFlow<Long> = _rampStartExposureMs.asStateFlow()
    fun setRampStartExposureMs(v: Long) { _rampStartExposureMs.value = v.coerceAtLeast(AppConfig.MIN_EXPOSURE_MS) }

    private val _rampEndExposureMs = MutableStateFlow(10000L)
    val rampEndExposureMs: StateFlow<Long> = _rampEndExposureMs.asStateFlow()
    fun setRampEndExposureMs(v: Long) { _rampEndExposureMs.value = v.coerceAtLeast(AppConfig.MIN_EXPOSURE_MS) }

    private val _rampSteps = MutableStateFlow(50)
    val rampSteps: StateFlow<Int> = _rampSteps.asStateFlow()
    fun setRampSteps(v: Int) { _rampSteps.value = v.coerceAtLeast(2) }

    private val _rampIntervalMs = MutableStateFlow(AppConfig.DEFAULT_INTERVAL_MS)
    val rampIntervalMs: StateFlow<Long> = _rampIntervalMs.asStateFlow()
    fun setRampIntervalMs(v: Long) { _rampIntervalMs.value = v.coerceAtLeast(AppConfig.MIN_INTERVAL_MS) }

    companion object {
        private const val KEY_INTV_INTERVAL = "intv_interval_ms"
        private const val KEY_INTV_EXPOSURE = "intv_exposure_ms"
        private const val KEY_INTV_COUNT = "intv_shot_count"
        private const val KEY_INTV_DELAY = "intv_delay_ms"
    }
}
