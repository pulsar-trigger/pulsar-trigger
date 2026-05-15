/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Copyright (C) 2026 Pulsar Trigger contributors
 */

package com.ehrocha.pulsar.viewmodel

import android.annotation.SuppressLint
import android.app.Application
import android.bluetooth.BluetoothDevice
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ehrocha.pulsar.AppConfig
import com.ehrocha.pulsar.ble.*
import com.ehrocha.pulsar.model.FlowStep
import com.ehrocha.pulsar.model.FlowStepType
import com.ehrocha.pulsar.model.FlowPresets
import com.ehrocha.pulsar.model.RunState
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
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.withTimeoutOrNull

class PulsarViewModel(app: Application) : AndroidViewModel(app) {

    companion object {
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

    // ── BLE (scan + connection) ─────────────────────────────────────────
    val bleController = com.ehrocha.pulsar.ble.BleController(app)
    private val bleManager get() = bleController.bleManager

    val scanning: StateFlow<Boolean> = bleController.scanning
    val devices: StateFlow<List<BluetoothDevice>> = bleController.devices

    // Connection-side flows. [status] is multiplexed below — BLE updates flow
    // in, but the simulator can write directly when it's running.
    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected

    private val _status = MutableStateFlow<StatusFrame?>(null)
    val status: StateFlow<StatusFrame?> = _status

    val deviceInfo: StateFlow<DeviceInfo?> = bleController.deviceInfo
    val rssi: StateFlow<Int?> = bleController.rssi

    val safeOutputPins: StateFlow<List<Int>> = bleController.deviceInfo
        .map { AppConfig.safeOutputPinsForChip(it?.chipModel) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, AppConfig.SAFE_OUTPUT_PINS)

    // ── Astro dashboard ──────────────────────────────────────────────
    val dashboardManager = com.ehrocha.pulsar.astro.AstroDashboardManager(app)

    // ── Planner ──────────────────────────────────────────────────────
    val plannerManager = com.ehrocha.pulsar.planner.PlannerManager(app)

    // ── Firmware OTA ─────────────────────────────────────────────────
    val firmwareManager = FirmwareUpdateManager(bleManager, viewModelScope)

    // ── App self-update ──────────────────────────────────────────────
    val appUpdateManager = com.ehrocha.pulsar.update.AppUpdateManager(app, viewModelScope)

    // ── Simulator ────────────────────────────────────────────────────────
    private val _simulatorActive = MutableStateFlow(false)
    val simulatorActive: StateFlow<Boolean> = _simulatorActive
    private var simulatorJob: Job? = null

    // ── User-authored modes ──────────────────────────────────────────────
    private val userModeRepo = com.ehrocha.pulsar.model.UserModeRepository(app)
    private val _userModes = MutableStateFlow(userModeRepo.load())
    val userModes: StateFlow<List<com.ehrocha.pulsar.model.UserMode>> = _userModes

    fun upsertUserMode(mode: com.ehrocha.pulsar.model.UserMode) {
        _userModes.value = userModeRepo.upsert(mode)
    }

    fun removeUserMode(id: String) {
        _userModes.value = userModeRepo.remove(id)
    }

    fun reorderUserModes(ids: List<String>) {
        _userModes.value = userModeRepo.reorder(ids)
    }

    /** Run a user trigger mode through the firmware path via the flow runner. */
    fun runUserMode(mode: com.ehrocha.pulsar.model.UserMode) {
        if (_simulatorActive.value) return
        val body = mode.body
        val step: com.ehrocha.pulsar.model.FlowStep = when (body.fwMode) {
            TriggerMode.INTERVALOMETER -> com.ehrocha.pulsar.model.FlowStep.Intervalometer(
                intervalMs = body.intervalMs,
                exposureMs = body.exposureMs,
                shotCount = body.shotCount,
                delayMs = body.delayMs,
            )
            TriggerMode.ASTRO -> com.ehrocha.pulsar.model.FlowStep.Astro(
                focalLength = body.focalLength,
                cropFactor = body.cropFactor,
                ruleDivisor = body.ruleDivisor,
                gapMs = body.intervalMs,
                shotCount = body.shotCount,
                delayMs = body.delayMs,
            )
            TriggerMode.DARK_FRAME -> com.ehrocha.pulsar.model.FlowStep.DarkFrame(
                shotCount = body.shotCount,
                exposureMs = body.exposureMs,
                gapMs = body.intervalMs,
            )
            TriggerMode.RAMP -> com.ehrocha.pulsar.model.FlowStep.Ramp(
                startExposureMs = body.rampStartExposureMs,
                endExposureMs = body.rampEndExposureMs,
                steps = body.rampSteps,
                intervalMs = body.intervalMs,
            )
            else -> return
        }
        _flowSteps.value = listOf(step)
        startFlow()
    }

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

    /** Single derived run-state — UI consumers should prefer this over the
     *  individual `status` / `flowRunning` / `flowPaused` / `flowCurrentStep`
     *  flows. See [RunState] and `docs/refactor-plan.md` Phase 3. */
    val runState: StateFlow<RunState> = combine(
        _status, _flowRunning, _flowPaused, _flowCurrentStep, _flowSteps,
    ) { status, running, paused, currentStep, steps ->
        RunState.from(status, running, paused, currentStep, steps)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, RunState.Idle)

    init {
        // Forward BLE controller state into the viewmodel's writable flows.
        // [_status] is multiplexed — BLE updates land here, and the simulator
        // also writes to it directly while it's running.
        viewModelScope.launch {
            bleController.connected.collect {
                _connected.value = it
                if (it) {
                    sendAutoOff()
                    // Device info is requested by BleManager.initialize()
                    // after notifications are active — no need to request here.
                }
            }
        }
        viewModelScope.launch {
            bleController.status.collect {
                if (!_simulatorActive.value) _status.value = it
            }
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

    fun requestDeviceInfo() {
        if (!_simulatorActive.value) {
            bleController.sendCommand(CommandBuilder.deviceInfoRequest())
        }
    }

    fun startScan() = bleController.startScan()
    fun stopScan() = bleController.stopScan()

    @SuppressLint("MissingPermission")
    fun connectTo(device: BluetoothDevice) {
        _deviceName.value = device.name ?: "Pulsar"
        bleController.connect(device)
    }

    fun disconnect() {
        if (_simulatorActive.value) {
            disconnectSimulator()
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
            bleController.sendCommand(CommandBuilder.stop())
        }
        bleController.disconnect()
    }


    // ── Field setters (encapsulation) ────────────────────────────────────
    fun setIntervalMs(v: Long) {
        _intervalMs.value = v.coerceAtLeast(AppConfig.MIN_INTERVAL_MS)
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
        if (_simulatorActive.value) return
        bleController.sendCommand(CommandBuilder.setPins(_pinShutter.value, _pinFocus.value))
    }

    private fun sendAutoOff() {
        if (_simulatorActive.value) return
        bleController.sendCommand(CommandBuilder.setAutoOff(_autoOffMinutes.value))
    }

    // ── Custom Flow management ───────────────────────────────────────────

    /** Load a single-step flow pre-filled with the user's current working settings. */
    fun loadQuickMode(type: FlowStepType) {
        val step: FlowStep = when (type) {
            FlowStepType.INTERVALOMETER -> FlowStep.Intervalometer(
                intervalMs = _intervalMs.value,
                exposureMs = _exposureMs.value,
                shotCount = _shotCount.value,
                delayMs = _delayMs.value,
            )
            FlowStepType.ASTRO -> FlowStep.Astro(
                focalLength = _astroFocalLength.value,
                cropFactor = _astroCropFactor.value,
                ruleDivisor = _astroRuleDivisor.value,
                gapMs = _astroGapMs.value,
                shotCount = _astroShotCount.value,
                delayMs = _astroDelayMs.value,
            )
            FlowStepType.PAUSE -> FlowStep.Pause()
            FlowStepType.DARK_FRAME -> FlowStep.DarkFrame(
                shotCount = _darkFrameCount.value,
                exposureMs = _darkFrameExposureMs.value,
                gapMs = _darkFrameGapMs.value,
            )
            FlowStepType.RAMP -> FlowStep.Ramp(
                startExposureMs = _rampStartExposureMs.value,
                endExposureMs = _rampEndExposureMs.value,
                steps = _rampSteps.value,
                intervalMs = _rampIntervalMs.value,
            )
        }
        saveFlowSteps(listOf(step))
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

    fun startFlow() {
        val steps = _flowSteps.value
        if (steps.isEmpty()) return
        flowJob?.cancel()
        _flowRunning.value = true
        _flowPaused.value = false
        _flowCurrentStep.value = 0
        flowJob = viewModelScope.launch {
            try {
                for (i in steps.indices) {
                    _flowCurrentStep.value = i
                    executeFlowStep(steps[i])
                }
            } finally {
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
        if (!_simulatorActive.value) {
            bleController.sendCommand(CommandBuilder.stop())
        }
        _status.value = _status.value?.copy(state = DeviceState.IDLE, timeRemainingMs = 0L)
        _flowRunning.value = false
        _flowPaused.value = false
        _flowCurrentStep.value = -1
    }

    private suspend fun executeFlowStep(step: FlowStep) {
        when (step) {
            is FlowStep.Pause -> {
                _flowPaused.value = true
                if (step.wakeOnPause) {
                    wakeScreen()
                }
                while (_flowPaused.value) {
                    coroutineContext.ensureActive()
                    delay(100)
                }
            }
            is FlowStep.Intervalometer -> {
                if (_simulatorActive.value) {
                    simulateShots(step.shotCount, step.exposureMs, step.intervalMs, step.delayMs)
                } else {
                    sendModeCommand(
                        CommandBuilder.setIntervalometer(
                            step.intervalMs, step.exposureMs, step.shotCount, step.delayMs,
                        )
                    )
                    waitForCompletion(step.shotCount)
                }
            }
            is FlowStep.Astro -> {
                val expMs = AppConfig.astroExposureMs(step.focalLength, step.cropFactor, step.ruleDivisor)
                if (_simulatorActive.value) {
                    simulateShots(step.shotCount, expMs, step.gapMs, step.delayMs)
                } else {
                    sendModeCommand(
                        CommandBuilder.setAstro(step.gapMs, expMs, step.shotCount, step.delayMs)
                    )
                    waitForCompletion(step.shotCount)
                }
            }
            is FlowStep.DarkFrame -> {
                if (_simulatorActive.value) {
                    simulateShots(step.shotCount, step.exposureMs, step.gapMs, 0L)
                } else {
                    sendModeCommand(
                        CommandBuilder.setDarkFrame(
                            step.gapMs, step.exposureMs, step.shotCount, 0L,
                        )
                    )
                    waitForCompletion(step.shotCount)
                }
            }
            is FlowStep.Ramp -> {
                val rampSteps = step.steps.coerceAtLeast(2)
                for (i in 0 until rampSteps) {
                    coroutineContext.ensureActive()
                    val fraction = i.toDouble() / (rampSteps - 1)
                    val expMs = (step.startExposureMs +
                        fraction * (step.endExposureMs - step.startExposureMs)).toLong()
                    if (_simulatorActive.value) {
                        simulateShots(1, expMs, step.intervalMs, 0L)
                    } else {
                        sendModeCommand(
                            CommandBuilder.setRamp(step.intervalMs, expMs, 1, 0L)
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
        bleController.sendCommand(packet)
        bleController.sendCommand(CommandBuilder.start())
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
        bleController.sendCommand(packet)
    }

    fun start() {
        if (_simulatorActive.value) {
            startSimulatorRun()
            return
        }
        sendConfig()
        bleController.sendCommand(CommandBuilder.start())
    }

    fun stop() {
        if (_flowRunning.value) {
            stopFlow()
            return
        }
        if (_simulatorActive.value) {
            stopSimulatorRun()
            return
        }
        bleController.sendCommand(CommandBuilder.stop())
    }

    fun renameDevice(suffix: String) {
        if (!_simulatorActive.value) {
            bleController.requestCacheRefresh()
            bleController.sendCommand(CommandBuilder.setName(suffix))
        }
        _deviceName.value = if (suffix.isNotEmpty()) "Pulsar-$suffix" else "Pulsar"
    }

    fun setAutoOff(minutes: Int) {
        _autoOffMinutes.value = minutes
        prefs.edit().putInt(KEY_AUTO_OFF, minutes).apply()
        if (!_simulatorActive.value) {
            bleController.sendCommand(CommandBuilder.setAutoOff(minutes))
        }
    }

    /** Press & Hold: shutter open on down */
    fun shutterDown() {
        if (_simulatorActive.value) {
            _status.value = _status.value?.copy(state = DeviceState.RUNNING)
            return
        }
        sendConfig()
        bleController.sendCommand(CommandBuilder.start())
    }

    /** Press & Hold: shutter close on up */
    fun shutterUp() {
        if (_simulatorActive.value) {
            _status.value = _status.value?.copy(state = DeviceState.IDLE)
            return
        }
        bleController.sendCommand(CommandBuilder.stop())
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
                _status.value = _status.value?.copy(
                    state = DeviceState.RUNNING,
                    shotsTaken = shot - 1,
                    timeRemainingMs = remaining,
                )
                delay(expMs)
                _status.value = _status.value?.copy(
                    state = DeviceState.WAITING,
                    shotsTaken = shot,
                    timeRemainingMs = (remaining - expMs).coerceAtLeast(0),
                )
                if (shot < totalShots) {
                    delay(gapMs)
                }
            }

            _status.value = _status.value?.copy(
                state = DeviceState.IDLE,
                timeRemainingMs = 0L,
            )
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
        // Send stop before tearing down so firmware doesn't keep firing
        if (flowJob != null || _status.value?.state.let {
                it == DeviceState.RUNNING || it == DeviceState.WAITING
            }) {
            flowJob?.cancel()
            bleController.sendCommand(CommandBuilder.stop())
        }
        bleController.disconnect()
    }
}
