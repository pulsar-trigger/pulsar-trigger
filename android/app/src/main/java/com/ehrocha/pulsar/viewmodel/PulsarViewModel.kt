/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Copyright (C) 2026 Pulsar Trigger contributors
 */

package com.ehrocha.pulsar.viewmodel

import android.annotation.SuppressLint
import android.app.Application
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.Intent
import android.os.ParcelUuid
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ehrocha.pulsar.AppConfig
import com.ehrocha.pulsar.ble.*
import com.ehrocha.pulsar.model.FlowStep
import com.ehrocha.pulsar.model.FlowStepType
import com.ehrocha.pulsar.model.FlowPresets
import com.ehrocha.pulsar.model.SavedFlow
import com.ehrocha.pulsar.service.PulsarNotificationService
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.sqrt

class PulsarViewModel(app: Application) : AndroidViewModel(app) {

    companion object {
        /** First N frames of an Auto Astro sequence are foreground (mean-stacked
         *  by Nightscape); the rest are sky. */
        const val FG_FRAMES = 10
        private const val TAG = "PulsarVM"
        private const val PREFS_NAME = "pulsar_settings"
        private const val KEY_INTV_INTERVAL = "intv_interval_ms"
        private const val KEY_INTV_EXPOSURE = "intv_exposure_ms"
        private const val KEY_INTV_COUNT = "intv_shot_count"
        private const val KEY_INTV_DELAY = "intv_delay_ms"
        private const val KEY_INTV_MAX_SHOTS = "intv_max_shots"
        private const val KEY_PIN_SHUTTER = "pin_shutter"
        private const val KEY_PIN_FOCUS = "pin_focus"
        private const val KEY_FLOW_STEPS = "flow_steps"
        private const val KEY_SAVED_FLOWS = "saved_flows"
        private const val KEY_AUTO_OFF = "auto_off_minutes"
        private const val NOTIFICATION_THROTTLE_MS = 5_000L
        const val DEFAULT_PIN_SHUTTER = AppConfig.DEFAULT_PIN_SHUTTER
        const val DEFAULT_PIN_FOCUS = AppConfig.DEFAULT_PIN_FOCUS
        val SAFE_OUTPUT_PINS = AppConfig.SAFE_OUTPUT_PINS
    }

    private val prefs = app.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val bleManager = PulsarBleManager(app)

    // ── Astro dashboard ──────────────────────────────────────────────
    val dashboardManager = com.ehrocha.pulsar.astro.AstroDashboardManager(app)

    // ── Planner ──────────────────────────────────────────────────────
    val plannerManager = com.ehrocha.pulsar.planner.PlannerManager(app)

    // ── Firmware OTA ─────────────────────────────────────────────────
    val firmwareManager = FirmwareUpdateManager(bleManager, viewModelScope)

    // ── App self-update ──────────────────────────────────────────────
    val appUpdateManager = com.ehrocha.pulsar.update.AppUpdateManager(app, viewModelScope)

    // ── Scan state ───────────────────────────────────────────────────────
    private val _scanning = MutableStateFlow(false)
    val scanning: StateFlow<Boolean> = _scanning

    private val _devices = MutableStateFlow<List<BluetoothDevice>>(emptyList())
    val devices: StateFlow<List<BluetoothDevice>> = _devices

    // ── Connection state ─────────────────────────────────────────────────
    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected

    private val _status = MutableStateFlow<StatusFrame?>(null)
    val status: StateFlow<StatusFrame?> = _status

    val deviceInfo: StateFlow<DeviceInfo?> = bleManager.deviceInfo

    val safeOutputPins: StateFlow<List<Int>> = bleManager.deviceInfo
        .map { AppConfig.safeOutputPinsForChip(it?.chipModel) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, AppConfig.SAFE_OUTPUT_PINS)

    val rssi: StateFlow<Int?> = bleManager.rssi

    // ── Simulator ────────────────────────────────────────────────────────
    private val _simulatorActive = MutableStateFlow(false)
    val simulatorActive: StateFlow<Boolean> = _simulatorActive
    private var simulatorJob: Job? = null

    // ── Phone Camera ─────────────────────────────────────────────────────
    private val _phoneCameraActive = MutableStateFlow(false)
    val phoneCameraActive: StateFlow<Boolean> = _phoneCameraActive
    val phoneCameraManager = com.ehrocha.pulsar.camera.PhoneCameraManager(app)

    private val _deviceName = MutableStateFlow("Pulsar")
    val deviceName: StateFlow<String> = _deviceName

    // ── Mode config state ────────────────────────────────────────────────
    private val _currentMode = MutableStateFlow(TriggerMode.INTERVALOMETER)
    val currentMode: StateFlow<TriggerMode> = _currentMode

    // ── Intervalometer params ────────────────────────────────────────────
    private val _intervalMs = MutableStateFlow(AppConfig.DEFAULT_INTERVAL_MS)
    val intervalMs: StateFlow<Long> = _intervalMs
    private val _exposureMs = MutableStateFlow(AppConfig.DEFAULT_EXPOSURE_MS)
    val exposureMs: StateFlow<Long> = _exposureMs
    private val _shotCount = MutableStateFlow(AppConfig.DEFAULT_SHOT_COUNT)
    val shotCount: StateFlow<Int> = _shotCount
    private val _delayMs = MutableStateFlow(AppConfig.DEFAULT_DELAY_MS)
    val delayMs: StateFlow<Long> = _delayMs

    // ── Intervalometer defaults (persisted) ──────────────────────────────
    private val _defaultIntervalMs = MutableStateFlow(AppConfig.DEFAULT_INTERVAL_MS)
    val defaultIntervalMs: StateFlow<Long> = _defaultIntervalMs
    private val _defaultExposureMs = MutableStateFlow(AppConfig.DEFAULT_EXPOSURE_MS)
    val defaultExposureMs: StateFlow<Long> = _defaultExposureMs
    private val _defaultShotCount = MutableStateFlow(AppConfig.DEFAULT_SHOT_COUNT)
    val defaultShotCount: StateFlow<Int> = _defaultShotCount
    private val _defaultDelayMs = MutableStateFlow(AppConfig.DEFAULT_DELAY_MS)
    val defaultDelayMs: StateFlow<Long> = _defaultDelayMs
    private val _maxShotCount = MutableStateFlow(AppConfig.DEFAULT_MAX_SHOTS)
    val maxShotCount: StateFlow<Int> = _maxShotCount


    // ── Astro params ─────────────────────────────────────────────────────
    private val _astroFocalLength = MutableStateFlow(AppConfig.DEFAULT_FOCAL_LENGTH)       // mm
    val astroFocalLength: StateFlow<Int> = _astroFocalLength
    private val _astroCropFactor = MutableStateFlow(AppConfig.DEFAULT_CROP_FACTOR)      // Full Frame default
    val astroCropFactor: StateFlow<Float> = _astroCropFactor
    private val _astroRuleDivisor = MutableStateFlow(AppConfig.DEFAULT_RULE_DIVISOR)      // 500 or 400
    val astroRuleDivisor: StateFlow<Int> = _astroRuleDivisor
    private val _astroShotCount = MutableStateFlow(AppConfig.DEFAULT_SHOT_COUNT)
    val astroShotCount: StateFlow<Int> = _astroShotCount
    private val _astroDelayMs = MutableStateFlow(AppConfig.DEFAULT_ASTRO_DELAY_MS)
    val astroDelayMs: StateFlow<Long> = _astroDelayMs
    private val _astroGapMs = MutableStateFlow(AppConfig.DEFAULT_ASTRO_GAP_MS)          // gap between shots
    val astroGapMs: StateFlow<Long> = _astroGapMs

    // ── Dark Frame params ───────────────────────────────────────────────────
    private val _darkFrameCount = MutableStateFlow(10)
    val darkFrameCount: StateFlow<Int> = _darkFrameCount
    private val _darkFrameExposureMs = MutableStateFlow(AppConfig.DEFAULT_EXPOSURE_MS)
    val darkFrameExposureMs: StateFlow<Long> = _darkFrameExposureMs
    private val _darkFrameGapMs = MutableStateFlow(AppConfig.DEFAULT_ASTRO_GAP_MS)
    val darkFrameGapMs: StateFlow<Long> = _darkFrameGapMs

    // ── Exposure Ramp state ─────────────────────────────────────────────
    private val _rampStartExposureMs = MutableStateFlow(500L)
    val rampStartExposureMs: StateFlow<Long> = _rampStartExposureMs
    private val _rampEndExposureMs = MutableStateFlow(10000L)
    val rampEndExposureMs: StateFlow<Long> = _rampEndExposureMs
    private val _rampSteps = MutableStateFlow(50)
    val rampSteps: StateFlow<Int> = _rampSteps
    private val _rampIntervalMs = MutableStateFlow(AppConfig.DEFAULT_INTERVAL_MS)
    val rampIntervalMs: StateFlow<Long> = _rampIntervalMs

    // ── GPIO pin config ──────────────────────────────────────────────────
    private val _pinShutter = MutableStateFlow(DEFAULT_PIN_SHUTTER)
    val pinShutter: StateFlow<Int> = _pinShutter
    private val _pinFocus = MutableStateFlow(DEFAULT_PIN_FOCUS)
    val pinFocus: StateFlow<Int> = _pinFocus
    private val _autoOffMinutes = MutableStateFlow(5)
    val autoOffMinutes: StateFlow<Int> = _autoOffMinutes

    // ── Custom Flow ──────────────────────────────────────────────────────
    private val _flowSteps = MutableStateFlow<List<FlowStep>>(emptyList())
    val flowSteps: StateFlow<List<FlowStep>> = _flowSteps
    private val _savedFlows = MutableStateFlow<List<SavedFlow>>(emptyList())
    val savedFlows: StateFlow<List<SavedFlow>>
        get() = _combinedFlows
    private val _combinedFlows = MutableStateFlow<List<SavedFlow>>(FlowPresets.ALL)
    private val _flowRunning = MutableStateFlow(false)
    val flowRunning: StateFlow<Boolean> = _flowRunning
    private val _flowPaused = MutableStateFlow(false)
    val flowPaused: StateFlow<Boolean> = _flowPaused
    private val _flowCurrentStep = MutableStateFlow(-1)
    val flowCurrentStep: StateFlow<Int> = _flowCurrentStep
    private var flowJob: Job? = null

    // ── BLE Scan ─────────────────────────────────────────────────────────
    private val btManager = app.getSystemService(BluetoothManager::class.java)
    // Resolved lazily — must not be cached at init time because Bluetooth
    // permissions may not yet be granted when the ViewModel is created.
    private val scanner get() = btManager?.adapter?.bluetoothLeScanner

    init {
        // Forward BLE manager state to ViewModel flows
        viewModelScope.launch {
            bleManager.connectionState.collect {
                _connected.value = it
                if (it) {
                    sendAutoOff()
                    // Device info is requested by BleManager.initialize()
                    // after notifications are active — no need to request here.
                }
            }
        }
        viewModelScope.launch {
            bleManager.status.collect { _status.value = it }
        }

        // Load persisted intervalometer defaults
        _defaultIntervalMs.value = prefs.getLong(KEY_INTV_INTERVAL, AppConfig.DEFAULT_INTERVAL_MS)
        _defaultExposureMs.value = prefs.getLong(KEY_INTV_EXPOSURE, AppConfig.DEFAULT_EXPOSURE_MS)
        _defaultShotCount.value = prefs.getInt(KEY_INTV_COUNT, AppConfig.DEFAULT_SHOT_COUNT)
        _defaultDelayMs.value = prefs.getLong(KEY_INTV_DELAY, AppConfig.DEFAULT_DELAY_MS)
        _maxShotCount.value = prefs.getInt(KEY_INTV_MAX_SHOTS, AppConfig.DEFAULT_MAX_SHOTS)
        _pinShutter.value = prefs.getInt(KEY_PIN_SHUTTER, DEFAULT_PIN_SHUTTER)
        _pinFocus.value = prefs.getInt(KEY_PIN_FOCUS, DEFAULT_PIN_FOCUS)
        _autoOffMinutes.value = prefs.getInt(KEY_AUTO_OFF, 5)
        // Load custom flow steps
        _flowSteps.value = try {
            FlowStep.deserializeList(prefs.getString(KEY_FLOW_STEPS, "") ?: "")
        } catch (_: Exception) { emptyList() }
        // Load saved flows library
        _savedFlows.value = try {
            SavedFlow.deserializeList(prefs.getString(KEY_SAVED_FLOWS, "") ?: "")
        } catch (_: Exception) { emptyList() }
        _combinedFlows.value = FlowPresets.ALL + _savedFlows.value
        // Apply defaults as initial working values
        _intervalMs.value = _defaultIntervalMs.value
        _exposureMs.value = _defaultExposureMs.value
        _shotCount.value = _defaultShotCount.value
        _delayMs.value = _defaultDelayMs.value

        // Auto-check for updates on connect
        viewModelScope.launch {
            _connected.collect { isConnected ->
                if (isConnected) {
                    // Always check app update
                    appUpdateManager.checkForUpdate(
                        com.ehrocha.pulsar.BuildConfig.VERSION_NAME
                    )
                    // For real device, wait for device info + status before
                    // checking firmware updates so chip model is known
                    if (!_simulatorActive.value) {
                        deviceInfo.filterNotNull().first()
                        val frame = _status.filterNotNull().first()
                        if (frame.fwVersion.isNotEmpty()) {
                            firmwareManager.checkForUpdate(frame.fwVersion)
                        }
                    }
                }
            }
        }

        // When device info arrives, ensure saved pins are valid for the chip,
        // then send them. This avoids sending wrong defaults before we know the chip model.
        viewModelScope.launch {
            deviceInfo.filterNotNull().collect { info ->
                val validPins = AppConfig.safeOutputPinsForChip(info.chipModel)
                if (_pinShutter.value !in validPins || _pinFocus.value !in validPins) {
                    val defShutter = AppConfig.defaultShutterPinForChip(info.chipModel)
                    val defFocus = AppConfig.defaultFocusPinForChip(info.chipModel)
                    savePins(defShutter, defFocus) // saves + sends
                } else {
                    sendPins()
                }
            }
        }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val dev = result.device
            if (_devices.value.none { it.address == dev.address }) {
                _devices.value = _devices.value + dev
            }
        }
    }

    fun requestDeviceInfo() {
        if (!_simulatorActive.value && !_phoneCameraActive.value) {
            bleManager.sendCommand(CommandBuilder.deviceInfoRequest())
        }
    }

    fun startScan() {
        _devices.value = emptyList()
        _scanning.value = true

        val s = scanner
        if (s == null) {
            Log.w(TAG, "BLE scanner unavailable — Bluetooth off or permissions not granted")
            _scanning.value = false
            return
        }

        val filter = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid(PulsarUuids.SERVICE))
            .build()
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        try {
            s.startScan(listOf(filter), settings, scanCallback)
            Log.i(TAG, "BLE scan started")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start BLE scan", e)
            _scanning.value = false
        }
    }

    fun stopScan() {
        try {
            scanner?.stopScan(scanCallback)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to stop BLE scan", e)
        }
        _scanning.value = false
    }

    @SuppressLint("MissingPermission")
    fun connectTo(device: BluetoothDevice) {
        stopScan()
        _deviceName.value = device.name ?: "Pulsar"
        bleManager.connectDevice(device)
    }

    fun disconnect() {
        if (_simulatorActive.value) {
            disconnectSimulator()
            return
        }
        if (_phoneCameraActive.value) {
            disconnectPhoneCamera()
            return
        }
        // Stop any running job on the device before disconnecting so the
        // firmware doesn't keep firing after the BLE link drops.
        val running = _status.value?.state.let {
            it == DeviceState.RUNNING || it == DeviceState.WAITING
        }
        if (running || _flowRunning.value) {
            flowJob?.cancel()
            flowJob = null
            _flowRunning.value = false
            _flowPaused.value = false
            _flowCurrentStep.value = -1
            bleManager.sendCommand(CommandBuilder.stop())
        }
        bleManager.disconnectDevice()
    }


    // ── Field setters (encapsulation) ────────────────────────────────────
    fun setIntervalMs(v: Long) {
        // Phone camera can shoot back-to-back (gap=0); BLE device needs minimum gap
        val min = if (_phoneCameraActive.value) 0L else AppConfig.MIN_INTERVAL_MS
        _intervalMs.value = v.coerceAtLeast(min)
    }
    fun setExposureMs(v: Long) { _exposureMs.value = v.coerceAtLeast(AppConfig.MIN_EXPOSURE_MS) }
    fun setShotCount(v: Int) { _shotCount.value = v.coerceAtLeast(AppConfig.MIN_SHOT_COUNT) }
    fun setDelayMs(v: Long) { _delayMs.value = v }
    fun setAstroFocalLength(v: Int) { _astroFocalLength.value = v }
    fun setAstroCropFactor(v: Float) { _astroCropFactor.value = v }
    fun setAstroRuleDivisor(v: Int) { _astroRuleDivisor.value = v }
    fun setAstroGapMs(v: Long) { _astroGapMs.value = v.coerceAtLeast(AppConfig.MIN_ASTRO_GAP_MS) }
    fun setAstroShotCount(v: Int) { _astroShotCount.value = v.coerceAtLeast(AppConfig.MIN_SHOT_COUNT) }
    fun setAstroDelayMs(v: Long) { _astroDelayMs.value = v }
    fun setDarkFrameCount(v: Int) { _darkFrameCount.value = v.coerceAtLeast(AppConfig.MIN_SHOT_COUNT) }
    fun setDarkFrameExposureMs(v: Long) { _darkFrameExposureMs.value = v.coerceAtLeast(AppConfig.MIN_EXPOSURE_MS) }
    fun setDarkFrameGapMs(v: Long) { _darkFrameGapMs.value = v.coerceAtLeast(AppConfig.MIN_ASTRO_GAP_MS) }

    fun setRampStartExposureMs(v: Long) { _rampStartExposureMs.value = v.coerceAtLeast(AppConfig.MIN_EXPOSURE_MS) }
    fun setRampEndExposureMs(v: Long) { _rampEndExposureMs.value = v.coerceAtLeast(AppConfig.MIN_EXPOSURE_MS) }
    fun setRampSteps(v: Int) { _rampSteps.value = v.coerceAtLeast(2) }
    fun setRampIntervalMs(v: Long) { _rampIntervalMs.value = v.coerceAtLeast(AppConfig.MIN_INTERVAL_MS) }

    // ── Commands ─────────────────────────────────────────────────────────
    fun selectMode(mode: TriggerMode) {
        _currentMode.value = mode
    }

    fun saveIntervalometerDefaults(interval: Long, exposure: Long, count: Int, delay: Long) {
        _defaultIntervalMs.value = interval
        _defaultExposureMs.value = exposure
        _defaultShotCount.value = count
        _defaultDelayMs.value = delay
        prefs.edit()
            .putLong(KEY_INTV_INTERVAL, interval)
            .putLong(KEY_INTV_EXPOSURE, exposure)
            .putInt(KEY_INTV_COUNT, count)
            .putLong(KEY_INTV_DELAY, delay)
            .apply()
        // Apply to working values
        _intervalMs.value = interval
        _exposureMs.value = exposure
        _shotCount.value = count
        _delayMs.value = delay
    }

    fun saveMaxShotCount(max: Int) {
        _maxShotCount.value = max.coerceIn(AppConfig.MIN_MAX_SHOTS, AppConfig.MAX_MAX_SHOTS)
        prefs.edit().putInt(KEY_INTV_MAX_SHOTS, _maxShotCount.value).apply()
        // Clamp current values if they exceed the new max
        if (_shotCount.value > _maxShotCount.value) _shotCount.value = _maxShotCount.value
        if (_defaultShotCount.value > _maxShotCount.value) {
            saveIntervalometerDefaults(_defaultIntervalMs.value, _defaultExposureMs.value, _maxShotCount.value, _defaultDelayMs.value)
        }
    }

    fun resetIntervalometerDefaults() {
        saveIntervalometerDefaults(AppConfig.DEFAULT_INTERVAL_MS, AppConfig.DEFAULT_EXPOSURE_MS, AppConfig.DEFAULT_SHOT_COUNT, AppConfig.DEFAULT_DELAY_MS)
        saveMaxShotCount(AppConfig.DEFAULT_MAX_SHOTS)
    }

    fun savePins(shutter: Int, focus: Int) {
        _pinShutter.value = shutter
        _pinFocus.value = focus
        prefs.edit()
            .putInt(KEY_PIN_SHUTTER, shutter)
            .putInt(KEY_PIN_FOCUS, focus)
            .apply()
        sendPins()
    }

    fun sendPins() {
        if (_simulatorActive.value || _phoneCameraActive.value) return
        bleManager.sendCommand(CommandBuilder.setPins(_pinShutter.value, _pinFocus.value))
    }

    private fun sendAutoOff() {
        if (_simulatorActive.value || _phoneCameraActive.value) return
        bleManager.sendCommand(CommandBuilder.setAutoOff(_autoOffMinutes.value))
    }

    // ── Custom Flow management ───────────────────────────────────────────

    /** Load a single-step flow pre-filled with the user's current working settings. */
    fun loadQuickMode(type: FlowStepType) {
        val step = when (type) {
            FlowStepType.INTERVALOMETER -> FlowStep(
                type = FlowStepType.INTERVALOMETER,
                intervalMs = _intervalMs.value,
                exposureMs = _exposureMs.value,
                shotCount = _shotCount.value,
                delayMs = _delayMs.value,
            )
            FlowStepType.ASTRO -> FlowStep(
                type = FlowStepType.ASTRO,
                focalLength = _astroFocalLength.value,
                cropFactor = _astroCropFactor.value,
                ruleDivisor = _astroRuleDivisor.value,
                gapMs = _astroGapMs.value,
                shotCount = _astroShotCount.value,
                delayMs = _astroDelayMs.value,
            )
            FlowStepType.PAUSE -> FlowStep(type = FlowStepType.PAUSE)
            FlowStepType.DARK_FRAME -> FlowStep(
                type = FlowStepType.DARK_FRAME,
                darkFrameCount = _darkFrameCount.value,
                darkFrameExposureMs = _darkFrameExposureMs.value,
                darkFrameGapMs = _darkFrameGapMs.value,
            )
            FlowStepType.RAMP -> FlowStep(
                type = FlowStepType.RAMP,
                rampStartExposureMs = _rampStartExposureMs.value,
                rampEndExposureMs = _rampEndExposureMs.value,
                rampSteps = _rampSteps.value,
                rampIntervalMs = _rampIntervalMs.value,
            )
        }
        saveFlowSteps(listOf(step))
    }

    /**
     * One-tap astrophotography for phone camera. Computes NPF-rule exposure from
     * the active lens's metadata, picks a sensible high ISO, then fires a 20-shot
     * sequence with 4s gaps. Stacking later turns these into a single image.
     */
    fun startAutoAstro() {
        if (!_phoneCameraActive.value) return
        val lens = phoneCameraManager.lenses.value
            .getOrNull(phoneCameraManager.selectedLens.value) ?: return

        val isoRange = lens.capabilities.isoRange
        val skyIso = isoRange?.let { 1600.coerceIn(it.lower, it.upper) }
        val groundIso = isoRange?.let { 200.coerceIn(it.lower, it.upper) }

        // Single 30s low-ISO foreground frame, then 20 NPF sky frames. We pass
        // the raw photographic intent through — the camera driver decides how
        // long it can actually integrate (some honor more than they advertise).
        val ground = com.ehrocha.pulsar.model.FlowStep(
            type = com.ehrocha.pulsar.model.FlowStepType.INTERVALOMETER,
            intervalMs = 2_000L,
            exposureMs = 30_000L,
            shotCount = 1,
            delayMs = 0L,
            isoOverride = groundIso,
        )
        val sky = com.ehrocha.pulsar.model.FlowStep(
            type = com.ehrocha.pulsar.model.FlowStepType.INTERVALOMETER,
            intervalMs = 4_000L,
            exposureMs = computePhoneNpfExposureMs(lens),
            shotCount = 20,
            delayMs = 0L,
            isoOverride = skyIso,
        )
        _flowSteps.value = listOf(ground, sky)
        pendingSequenceMode = com.ehrocha.pulsar.stacking.CaptureMode.AUTO_ASTRO
        startFlow()
    }


    /**
     * One-tap daytime timelapse. Fast 1/1000s exposures every 10 seconds at low
     * ISO — built for clouds, sunsets, traffic, construction. 360 shots ≈ one
     * hour of capture, which renders to ~12 s of 30 fps video.
     */
    fun startTimelapse() {
        if (!_phoneCameraActive.value) return
        val lens = phoneCameraManager.lenses.value
            .getOrNull(phoneCameraManager.selectedLens.value) ?: return

        // ISO 200 — low for daylight, clean output.
        lens.capabilities.isoRange?.let { range ->
            phoneCameraManager.setManualIso(200.coerceIn(range.lower, range.upper))
        }

        val step = com.ehrocha.pulsar.model.FlowStep(
            type = com.ehrocha.pulsar.model.FlowStepType.INTERVALOMETER,
            intervalMs = 10_000L,
            exposureMs = 1L,
            shotCount = 360,
            delayMs = 0L,
        )
        _flowSteps.value = listOf(step)
        pendingSequenceMode = com.ehrocha.pulsar.stacking.CaptureMode.TIMELAPSE
        startFlow()
    }

    /**
     * One-tap fireworks capture. Bursts are very bright, so we use the lowest
     * ISO available and long-enough exposures to capture the full launch+bloom
     * arc. Zero gap to never miss a burst during a show.
     */
    fun startFireworks() {
        if (!_phoneCameraActive.value) return
        val lens = phoneCameraManager.lenses.value
            .getOrNull(phoneCameraManager.selectedLens.value) ?: return

        // Lowest ISO — fireworks are bright; we want highlight headroom.
        lens.capabilities.isoRange?.let { range ->
            phoneCameraManager.setManualIso(range.lower.coerceAtMost(200))
        }

        val step = com.ehrocha.pulsar.model.FlowStep(
            type = com.ehrocha.pulsar.model.FlowStepType.INTERVALOMETER,
            intervalMs = 2_000L,
            exposureMs = 4_000L,
            shotCount = 200,
            delayMs = 0L,
        )
        _flowSteps.value = listOf(step)
        pendingSequenceMode = com.ehrocha.pulsar.stacking.CaptureMode.FIREWORKS
        startFlow()
    }

    /**
     * One-tap star trails capture. Don't fight earth rotation — embrace it. Many
     * back-to-back medium-long exposures that lighten-blend later into iconic
     * concentric arcs around the celestial pole.
     */
    fun startStarTrails() {
        if (!_phoneCameraActive.value) return
        val lens = phoneCameraManager.lenses.value
            .getOrNull(phoneCameraManager.selectedLens.value) ?: return

        // ISO 800 — moderate; balances per-frame trail brightness against noise.
        lens.capabilities.isoRange?.let { range ->
            phoneCameraManager.setManualIso(800.coerceIn(range.lower, range.upper))
        }

        val step = com.ehrocha.pulsar.model.FlowStep(
            type = com.ehrocha.pulsar.model.FlowStepType.INTERVALOMETER,
            intervalMs = 2_000L,
            exposureMs = 30_000L,
            shotCount = 120,
            delayMs = 0L,
        )
        _flowSteps.value = listOf(step)
        pendingSequenceMode = com.ehrocha.pulsar.stacking.CaptureMode.TRAILS
        startFlow()
    }

    /**
     * One-tap thunderstorm/lightning capture. Long-but-not-too-long exposures with
     * zero gap to maximize the chance of catching a strike. ISO 400 keeps highlight
     * headroom for the flash itself. Frames land in their own sequence folder so a
     * later auto-cull/composite pass can pick out the winners.
     */
    fun startStormCapture() {
        if (!_phoneCameraActive.value) return
        val lens = phoneCameraManager.lenses.value
            .getOrNull(phoneCameraManager.selectedLens.value) ?: return

        // Modest ISO — storm sky has ambient light, lightning is bright.
        lens.capabilities.isoRange?.let { range ->
            phoneCameraManager.setManualIso(400.coerceIn(range.lower, range.upper))
        }

        val step = com.ehrocha.pulsar.model.FlowStep(
            type = com.ehrocha.pulsar.model.FlowStepType.INTERVALOMETER,
            intervalMs = 2_000L,
            exposureMs = 4_000L,
            shotCount = 300,
            delayMs = 0L,
        )
        _flowSteps.value = listOf(step)
        pendingSequenceMode = com.ehrocha.pulsar.stacking.CaptureMode.STORM
        startFlow()
    }

    /** NPF rule applied to phone-camera lens metadata (handles small sensors with sub-2µm pitch). */
    private fun computePhoneNpfExposureMs(lens: com.ehrocha.pulsar.camera.PhoneLens): Long {
        val focal = if (lens.focalLength > 0f) lens.focalLength else 5f
        val sensorWidth = if (lens.sensorWidth > 0f) lens.sensorWidth else 6f
        val cropFactor = (36f / sensorWidth).coerceAtLeast(1f)
        val aperture = if (lens.aperture > 0f) lens.aperture else 2.0f

        // Estimate horizontal pixel count from megapixels assuming 4:3 aspect ratio.
        val mp = if (lens.megapixels > 0f) lens.megapixels else 12f
        val horizPixels = sqrt(mp.toDouble() * 1_000_000.0 * 4.0 / 3.0)
        val pixelPitchUm = (sensorWidth.toDouble() / horizPixels) * 1000.0

        // NPF: (35 × aperture + 30 × pixelPitch_um) / (focal × cropFactor)
        val expS = (35.0 * aperture + 30.0 * pixelPitchUm) / (focal * cropFactor)
        return (expS * 1000).toLong().coerceAtLeast(AppConfig.MIN_ASTRO_EXPOSURE_MS)
    }

    fun saveFlowSteps(steps: List<FlowStep>) {
        _flowSteps.value = steps
        prefs.edit().putString(KEY_FLOW_STEPS, FlowStep.serializeList(steps)).apply()
    }

    fun saveFlowAs(name: String, tags: List<String> = emptyList()) {
        val existing = _savedFlows.value.firstOrNull { it.name == name }
        val flow = SavedFlow(
            name = name,
            steps = _flowSteps.value,
            favorite = existing?.favorite ?: false,
            tags = tags.ifEmpty { existing?.tags ?: emptyList() },
        )
        val updated = _savedFlows.value.filter { it.name != name } + flow
        _savedFlows.value = updated
        _combinedFlows.value = FlowPresets.ALL + updated
        prefs.edit().putString(KEY_SAVED_FLOWS, SavedFlow.serializeList(updated)).apply()
    }

    fun loadSavedFlow(name: String) {
        val flow = _combinedFlows.value.firstOrNull { it.name == name } ?: return
        saveFlowSteps(flow.steps)
    }

    fun deleteSavedFlow(name: String) {
        val updated = _savedFlows.value.filter { it.name != name }
        _savedFlows.value = updated
        _combinedFlows.value = FlowPresets.ALL + updated
        prefs.edit().putString(KEY_SAVED_FLOWS, SavedFlow.serializeList(updated)).apply()
    }

    fun toggleFavorite(name: String) {
        val updated = _savedFlows.value.map {
            if (it.name == name) it.copy(favorite = !it.favorite) else it
        }
        _savedFlows.value = updated
        _combinedFlows.value = FlowPresets.ALL + updated
        prefs.edit().putString(KEY_SAVED_FLOWS, SavedFlow.serializeList(updated)).apply()
    }

    fun updateFlowTags(name: String, tags: List<String>) {
        val updated = _savedFlows.value.map {
            if (it.name == name) it.copy(tags = tags) else it
        }
        _savedFlows.value = updated
        _combinedFlows.value = FlowPresets.ALL + updated
        prefs.edit().putString(KEY_SAVED_FLOWS, SavedFlow.serializeList(updated)).apply()
    }

    /** All unique tags across built-in and user flows. */
    val allTags: StateFlow<List<String>> = _combinedFlows
        .map { flows -> flows.flatMap { it.tags }.distinct().sorted() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** Capture mode tag to write on the next sequence folder, consumed by [startFlow]. */
    private var pendingSequenceMode: com.ehrocha.pulsar.stacking.CaptureMode? = null

    fun startFlow() {
        val steps = _flowSteps.value
        if (steps.isEmpty()) return
        flowJob?.cancel()
        _flowRunning.value = true
        _flowPaused.value = false
        _flowCurrentStep.value = 0
        flowJob = viewModelScope.launch {
            // One sequence folder for the whole flow — all steps share a single
            // DCIM/Pulsar/Sequence_<ts>/ so multi-step captures (e.g. Auto Astro
            // sky frames + bonus foreground) stack cleanly together.
            if (_phoneCameraActive.value) {
                phoneCameraManager.beginSequenceFolder()
                // Tag the folder with which capture mode produced it so the
                // Sequences detail screen can offer only composites that fit.
                val mode = pendingSequenceMode ?: com.ehrocha.pulsar.stacking.CaptureMode.MANUAL
                phoneCameraManager.activeSequencePath()?.let { path ->
                    com.ehrocha.pulsar.stacking.SequenceTags.set(
                        getApplication<Application>(), path, mode,
                    )
                }
                pendingSequenceMode = null
            }
            try {
                for (i in steps.indices) {
                    _flowCurrentStep.value = i
                    executeFlowStep(steps[i])
                }
                // All steps complete
            } finally {
                withContext(NonCancellable) {
                    if (_phoneCameraActive.value) phoneCameraManager.endSequenceFolder()
                }
                _flowRunning.value = false
                _flowPaused.value = false
                _flowCurrentStep.value = -1
            }
        }
    }

    fun continueFlow() {
        _flowPaused.value = false
    }

    fun stopFlow() {
        flowJob?.cancel()
        flowJob = null
        // Stop the device if it's running
        if (!_simulatorActive.value && !_phoneCameraActive.value) {
            bleManager.sendCommand(CommandBuilder.stop())
        }
        // Reset status to IDLE immediately so UI doesn't show stale progress
        _status.value = _status.value?.copy(state = DeviceState.IDLE, timeRemainingMs = 0L)
        _flowRunning.value = false
        _flowPaused.value = false
        _flowCurrentStep.value = -1
    }

    private suspend fun executeFlowStep(step: FlowStep) {
        when (step.type) {
            FlowStepType.PAUSE -> {
                _flowPaused.value = true
                if (step.wakeOnPause) {
                    wakeScreen()
                }
                // Wait until user taps Continue
                while (_flowPaused.value) {
                    coroutineContext.ensureActive()
                    delay(100)
                }
            }
            FlowStepType.INTERVALOMETER -> {
                val expMs = step.exposureMs
                val gapMs = step.intervalMs
                val shots = step.shotCount
                if (_simulatorActive.value) {
                    simulateShots(shots, expMs, gapMs, step.delayMs)
                } else if (_phoneCameraActive.value) {
                    phoneCameraShots(shots, expMs, gapMs, step.delayMs, step.isoOverride)
                } else {
                    sendModeCommand(
                        CommandBuilder.setIntervalometer(
                            step.intervalMs, step.exposureMs, step.shotCount, step.delayMs,
                        )
                    )
                    waitForCompletion(step.shotCount)
                }
            }
            FlowStepType.ASTRO -> {
                val expMs = AppConfig.astroExposureMs(step.focalLength, step.cropFactor, step.ruleDivisor)
                if (_simulatorActive.value) {
                    simulateShots(step.shotCount, expMs, step.gapMs, step.delayMs)
                } else if (_phoneCameraActive.value) {
                    phoneCameraShots(step.shotCount, expMs, step.gapMs, step.delayMs)
                } else {
                    sendModeCommand(
                        CommandBuilder.setAstro(step.gapMs, expMs, step.shotCount, step.delayMs)
                    )
                    waitForCompletion(step.shotCount)
                }
            }
            FlowStepType.DARK_FRAME -> {
                if (_simulatorActive.value) {
                    simulateShots(step.darkFrameCount, step.darkFrameExposureMs, step.darkFrameGapMs, 0L)
                } else if (_phoneCameraActive.value) {
                    phoneCameraShots(step.darkFrameCount, step.darkFrameExposureMs, step.darkFrameGapMs, 0L)
                } else {
                    sendModeCommand(
                        CommandBuilder.setDarkFrame(
                            step.darkFrameGapMs, step.darkFrameExposureMs, step.darkFrameCount, 0L,
                        )
                    )
                    waitForCompletion(step.darkFrameCount)
                }
            }
            FlowStepType.RAMP -> {
                val steps = step.rampSteps.coerceAtLeast(2)
                for (i in 0 until steps) {
                    coroutineContext.ensureActive()
                    val fraction = i.toDouble() / (steps - 1)
                    val expMs = (step.rampStartExposureMs +
                        fraction * (step.rampEndExposureMs - step.rampStartExposureMs)).toLong()
                    if (_simulatorActive.value) {
                        simulateShots(1, expMs, step.rampIntervalMs, 0L)
                    } else if (_phoneCameraActive.value) {
                        phoneCameraShots(1, expMs, step.rampIntervalMs, 0L)
                    } else {
                        sendModeCommand(
                            CommandBuilder.setRamp(step.rampIntervalMs, expMs, 1, 0L)
                        )
                        waitForCompletion(1)
                    }
                }
            }
        }
    }

    /** Simulate a sequence of shots by updating _status directly (for flow steps in simulator). */
    private suspend fun simulateShots(totalShots: Int, expMs: Long, gapMs: Long, startDelayMs: Long) {
        if (startDelayMs > 0) {
            _status.value = _status.value?.copy(
                state = DeviceState.WAITING, shotsTaken = 0,
                timeRemainingMs = startDelayMs + totalShots * (expMs + gapMs) - gapMs,
            )
            delay(startDelayMs)
        }
        for (shot in 1..totalShots) {
            coroutineContext.ensureActive()
            val remaining = (totalShots - shot + 1) * (expMs + gapMs) - gapMs
            // Exposure starts
            _status.value = _status.value?.copy(
                state = DeviceState.RUNNING, shotsTaken = shot - 1, timeRemainingMs = remaining,
            )
            delay(expMs)
            // Exposure ends — transition to WAITING with updated shot count
            _status.value = _status.value?.copy(
                state = DeviceState.WAITING, shotsTaken = shot,
                timeRemainingMs = (remaining - expMs).coerceAtLeast(0),
            )
            if (shot < totalShots) {
                delay(gapMs)
            }
        }
        _status.value = _status.value?.copy(state = DeviceState.IDLE, timeRemainingMs = 0L)
    }

    private fun sendModeCommand(packet: ByteArray) {
        bleManager.sendCommand(packet)
        bleManager.sendCommand(CommandBuilder.start())
    }

    /** Wait for firmware to go back to IDLE (modes with built-in completion). */
    private suspend fun waitForCompletion(expectedShots: Int) {
        var sawRunning = false
        while (true) {
            coroutineContext.ensureActive()
            val s = _status.value
            if (s != null) {
                if (s.state == DeviceState.RUNNING || s.state == DeviceState.WAITING) {
                    sawRunning = true
                }
                if (sawRunning && s.state == DeviceState.IDLE) break
            }
            delay(200)
        }
    }

    /** Serialize all persisted settings to JSON for export. */
    fun exportSettingsJson(): String {
        val json = org.json.JSONObject()
        json.put("intv_interval_ms", _defaultIntervalMs.value)
        json.put("intv_exposure_ms", _defaultExposureMs.value)
        json.put("intv_shot_count", _defaultShotCount.value)
        json.put("intv_delay_ms", _defaultDelayMs.value)
        json.put("intv_max_shots", _maxShotCount.value)
        json.put("pin_shutter", _pinShutter.value)
        json.put("pin_focus", _pinFocus.value)
        // Custom flow steps
        json.put("flow_steps", org.json.JSONArray(_flowSteps.value.map { it.toJson() }))
        // Saved flows library
        json.put("saved_flows", org.json.JSONArray(_savedFlows.value.map { it.toJson() }))
        return json.toString(2)
    }

    /** Import settings from a JSON string. */
    fun importSettingsJson(json: String) {
        val obj = org.json.JSONObject(json)
        if (obj.has("intv_max_shots")) saveMaxShotCount(obj.getInt("intv_max_shots"))
        saveIntervalometerDefaults(
            obj.optLong("intv_interval_ms", AppConfig.DEFAULT_INTERVAL_MS),
            obj.optLong("intv_exposure_ms", AppConfig.DEFAULT_EXPOSURE_MS),
            obj.optInt("intv_shot_count", AppConfig.DEFAULT_SHOT_COUNT),
            obj.optLong("intv_delay_ms", AppConfig.DEFAULT_DELAY_MS),
        )
        val shutter = obj.optInt("pin_shutter", DEFAULT_PIN_SHUTTER)
        val focus = obj.optInt("pin_focus", DEFAULT_PIN_FOCUS)
        val validPins = safeOutputPins.value
        if (shutter in validPins && focus in validPins && shutter != focus) {
            savePins(shutter, focus)
        }
        // Import custom flow steps
        obj.optJSONArray("flow_steps")?.let { arr ->
            val steps = (0 until arr.length()).map { FlowStep.fromJson(arr.getJSONObject(it)) }
            saveFlowSteps(steps)
        }
        // Import saved flows library
        obj.optJSONArray("saved_flows")?.let { arr ->
            val flows = (0 until arr.length()).map { SavedFlow.fromJson(arr.getJSONObject(it)) }
            _savedFlows.value = flows
            _combinedFlows.value = FlowPresets.ALL + flows
            prefs.edit().putString(KEY_SAVED_FLOWS, SavedFlow.serializeList(flows)).apply()
        }
    }

    private fun sendConfig() {
        if (_simulatorActive.value) return
        val packet = when (_currentMode.value) {
            TriggerMode.INTERVALOMETER -> CommandBuilder.setIntervalometer(
                _intervalMs.value, _exposureMs.value, _shotCount.value, _delayMs.value
            )
            TriggerMode.ASTRO -> {
                val exposureMs = AppConfig.astroExposureMs(_astroFocalLength.value, _astroCropFactor.value, _astroRuleDivisor.value)
                CommandBuilder.setAstro(
                    _astroGapMs.value, exposureMs, _astroShotCount.value, _astroDelayMs.value
                )
            }
            TriggerMode.DARK_FRAME -> CommandBuilder.setDarkFrame(
                _darkFrameGapMs.value, _darkFrameExposureMs.value, _darkFrameCount.value, 0L
            )
            TriggerMode.PRESS_HOLD -> CommandBuilder.setPressHold()
            TriggerMode.PRESS_LOCK -> CommandBuilder.setPressLock()
            TriggerMode.RAMP -> return          // app-orchestrated ramp, no single command
            TriggerMode.CUSTOM_FLOW -> return  // app-orchestrated, no single command
            TriggerMode.TRACKER -> return      // IMU streaming, no config needed
        }
        bleManager.sendCommand(packet)
    }

    fun start() {
        if (_simulatorActive.value || _phoneCameraActive.value) {
            startSimulatorRun()
            return
        }
        sendConfig()
        bleManager.sendCommand(CommandBuilder.start())
    }

    fun stop() {
        // Check flow first — phone camera and simulator both use flows from CameraScreen
        if (_flowRunning.value) {
            stopFlow()
            return
        }
        if (_simulatorActive.value || _phoneCameraActive.value) {
            stopSimulatorRun()
            return
        }
        bleManager.sendCommand(CommandBuilder.stop())
    }

    fun renameDevice(suffix: String) {
        if (!_simulatorActive.value && !_phoneCameraActive.value) {
            // Request GATT cache clear so the new name is picked up on reconnect
            bleManager.requestCacheRefresh()
            bleManager.sendCommand(CommandBuilder.setName(suffix))
        }
        _deviceName.value = if (suffix.isNotEmpty()) "Pulsar-$suffix" else "Pulsar"
    }

    fun setAutoOff(minutes: Int) {
        _autoOffMinutes.value = minutes
        prefs.edit().putInt(KEY_AUTO_OFF, minutes).apply()
        if (!_simulatorActive.value && !_phoneCameraActive.value) {
            bleManager.sendCommand(CommandBuilder.setAutoOff(minutes))
        }
    }

    /** Press & Hold: shutter open on down */
    fun shutterDown() {
        if (_simulatorActive.value || _phoneCameraActive.value) {
            _status.value = _status.value?.copy(state = DeviceState.RUNNING)
            if (_phoneCameraActive.value) {
                phoneCameraManager.capture()
            }
            return
        }
        sendConfig()
        bleManager.sendCommand(CommandBuilder.start())
    }

    /** Press & Hold: shutter close on up */
    fun shutterUp() {
        if (_simulatorActive.value || _phoneCameraActive.value) {
            _status.value = _status.value?.copy(state = DeviceState.IDLE)
            return
        }
        bleManager.sendCommand(CommandBuilder.stop())
    }

    // ── Simulator ────────────────────────────────────────────────────────

    fun connectSimulator() {
        stopScan()
        _simulatorActive.value = true
        _deviceName.value = "Pulsar (Simulator)"
        _status.value = StatusFrame(
            state = DeviceState.IDLE,
            mode = TriggerMode.INTERVALOMETER.id,
            shotsTaken = 0,
            timeRemainingMs = 0L,
            batteryPct = AppConfig.SIMULATOR_BATTERY_PCT,
            errorCode = 0,
        )
        _connected.value = true
    }

    fun disconnectSimulator() {
        simulatorJob?.cancel()
        simulatorJob = null
        _simulatorActive.value = false
        _connected.value = false
        _status.value = null
        _deviceName.value = "Pulsar"
    }

    private fun startSimulatorRun() {
        simulatorJob?.cancel()
        val mode = _currentMode.value
        val totalShots = when (mode) {
            TriggerMode.INTERVALOMETER -> _shotCount.value
            TriggerMode.ASTRO -> _astroShotCount.value
            else -> 1
        }
        val expMs = when (mode) {
            TriggerMode.INTERVALOMETER -> _exposureMs.value
            TriggerMode.ASTRO -> AppConfig.astroExposureMs(_astroFocalLength.value, _astroCropFactor.value, _astroRuleDivisor.value)
            else -> _exposureMs.value
        }
        val gapMs = when (mode) {
            TriggerMode.INTERVALOMETER -> _intervalMs.value
            TriggerMode.ASTRO -> _astroGapMs.value
            else -> 0L
        }
        val startDelayMs = when (mode) {
            TriggerMode.INTERVALOMETER -> _delayMs.value
            TriggerMode.ASTRO -> _astroDelayMs.value
            else -> 0L
        }

        simulatorJob = viewModelScope.launch {
            if (_phoneCameraActive.value) phoneCameraManager.beginSequenceFolder()
            try {
                if (startDelayMs > 0) {
                    val totalTimeMs = startDelayMs + totalShots * (expMs + gapMs) - gapMs
                    _status.value = _status.value?.copy(
                        state = DeviceState.WAITING, shotsTaken = 0,
                        timeRemainingMs = totalTimeMs,
                    )
                    delay(startDelayMs)
                }

                for (shot in 1..totalShots) {
                    val remaining = (totalShots - shot + 1) * (expMs + gapMs) - gapMs
                    // Exposing
                    _status.value = _status.value?.copy(
                        state = DeviceState.RUNNING,
                        shotsTaken = shot - 1,
                        timeRemainingMs = remaining,
                    )
                    if (_phoneCameraActive.value) {
                        val captureTimeoutMs = maxOf(30_000L, expMs * 2 + 10_000L)
                        withTimeoutOrNull(captureTimeoutMs) {
                            phoneCameraManager.captureAndWait()
                        }
                    }
                    delay(expMs)
                    // Shot complete — transition to WAITING with updated shot count
                    _status.value = _status.value?.copy(
                        state = DeviceState.WAITING,
                        shotsTaken = shot,
                        timeRemainingMs = (remaining - expMs).coerceAtLeast(0),
                    )
                    // Gap (except after last shot)
                    if (shot < totalShots) {
                        delay(gapMs)
                    }
                }

                // Done
                _status.value = _status.value?.copy(
                    state = DeviceState.IDLE,
                    timeRemainingMs = 0L,
                )
            } finally {
                withContext(NonCancellable) {
                    if (_phoneCameraActive.value) phoneCameraManager.endSequenceFolder()
                }
            }
        }
    }

    private fun stopSimulatorRun() {
        simulatorJob?.cancel()
        simulatorJob = null
        _status.value = _status.value?.copy(
            state = DeviceState.IDLE,
            timeRemainingMs = 0L,
        )
    }

    // ── Phone Camera ─────────────────────────────────────────────────────

    fun connectPhoneCamera() {
        stopScan()
        _phoneCameraActive.value = true
        _intervalMs.value = 0L  // Phone camera defaults to no gap (back-to-back)
        _deviceName.value = "Phone Camera"
        _status.value = StatusFrame(
            state = DeviceState.IDLE,
            mode = TriggerMode.INTERVALOMETER.id,
            shotsTaken = 0,
            timeRemainingMs = 0L,
            batteryPct = 100,
            errorCode = 0,
        )
        _connected.value = true
    }

    fun disconnectPhoneCamera() {
        simulatorJob?.cancel()
        simulatorJob = null
        phoneCameraManager.release()
        _phoneCameraActive.value = false
        _connected.value = false
        _status.value = null
        _deviceName.value = "Pulsar"
    }

    /**
     * Run a shot sequence using the phone camera via CameraX.
     *
     * Sequence folder is owned by [startFlow], not this function — multi-step flows
     * (e.g. Auto Astro sky + bonus foreground) deliberately share one folder.
     *
     * @param isoOverride if non-null, manual ISO is locked to this value for the
     *   duration of the step and restored on exit. Used for the bonus foreground
     *   frame (low ISO + long exposure).
     */
    private suspend fun phoneCameraShots(
        totalShots: Int,
        expMs: Long,
        gapMs: Long,
        startDelayMs: Long,
        isoOverride: Int? = null,
    ) {
        val actualExpNs = phoneCameraManager.setSensorExposureForCapture(expMs)
        val sensorHandlesExposure = actualExpNs > 0

        // ISO override stash so we can restore on exit
        val savedIso = phoneCameraManager.manualIso.value
        if (isoOverride != null) phoneCameraManager.setManualIso(isoOverride)

        try {
            if (startDelayMs > 0) {
                _status.value = _status.value?.copy(
                    state = DeviceState.WAITING, shotsTaken = 0,
                    timeRemainingMs = startDelayMs + totalShots * (expMs + gapMs) - gapMs,
                )
                delay(startDelayMs)
            }
            for (shot in 1..totalShots) {
                coroutineContext.ensureActive()
                val remaining = (totalShots - shot + 1) * (expMs + gapMs) - gapMs
                _status.value = _status.value?.copy(
                    state = DeviceState.RUNNING, shotsTaken = shot - 1, timeRemainingMs = remaining,
                )
                // Timeout = 2× requested exposure + 10s safety margin (or 30s minimum
                // for short exposures). Long takePicture calls have been observed to
                // hang on some devices after a previous long shot — this stops the
                // sequence rather than letting the user have to manually intervene.
                val captureTimeoutMs = maxOf(30_000L, expMs * 2 + 10_000L)
                val ok = withTimeoutOrNull(captureTimeoutMs) {
                    phoneCameraManager.captureAndWait()
                }
                if (ok == null) {
                    Log.w("PulsarVM", "captureAndWait timed out at shot $shot/$totalShots (${expMs}ms requested) — moving on")
                }
                if (!sensorHandlesExposure) {
                    delay(expMs)
                }
                _status.value = _status.value?.copy(
                    state = DeviceState.WAITING, shotsTaken = shot,
                    timeRemainingMs = (remaining - expMs).coerceAtLeast(0),
                )
                if (shot < totalShots) {
                    delay(gapMs)
                }
            }
            _status.value = _status.value?.copy(state = DeviceState.IDLE, timeRemainingMs = 0L)
        } finally {
            withContext(NonCancellable) {
                phoneCameraManager.restoreExposureSettings()
                if (isoOverride != null) phoneCameraManager.setManualIso(savedIso)
            }
        }
    }

    // ── Notification helpers ─────────────────────────────────────────────
    fun updateOtaNotification(title: String, text: String, progress: Int = -1, done: Boolean = false) {
        val app = getApplication<Application>()
        val intent = Intent(app, PulsarNotificationService::class.java).apply {
            action = PulsarNotificationService.ACTION_OTA
            putExtra(PulsarNotificationService.EXTRA_OTA_TITLE, title)
            putExtra(PulsarNotificationService.EXTRA_OTA_TEXT, text)
            putExtra(PulsarNotificationService.EXTRA_OTA_PROGRESS, progress)
            putExtra(PulsarNotificationService.EXTRA_OTA_DONE, done)
        }
        app.startForegroundService(intent)
    }

    private fun dismissOtaNotification() {
        val app = getApplication<Application>()
        app.stopService(Intent(app, PulsarNotificationService::class.java))
    }

    /** Turn the screen on briefly so the user sees the pause prompt. */
    @Suppress("DEPRECATION")
    private fun wakeScreen() {
        val app = getApplication<Application>()
        val pm = app.getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager ?: return
        val wl = pm.newWakeLock(
            android.os.PowerManager.FULL_WAKE_LOCK
                or android.os.PowerManager.ACQUIRE_CAUSES_WAKEUP
                or android.os.PowerManager.ON_AFTER_RELEASE,
            "pulsar:pause_wake",
        )
        wl.acquire(5_000L)  // auto-release after 5 seconds
    }

    override fun onCleared() {
        super.onCleared()
        stopScan()
        simulatorJob?.cancel()
        phoneCameraManager.release()
        // Send stop before tearing down so firmware doesn't keep firing
        if (flowJob != null || _status.value?.state.let {
                it == DeviceState.RUNNING || it == DeviceState.WAITING
            }) {
            flowJob?.cancel()
            bleManager.sendCommand(CommandBuilder.stop())
        }
        bleManager.disconnectDevice()
    }
}
