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
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.ParcelUuid
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ehrocha.pulsar.AppConfig
import com.ehrocha.pulsar.ble.*
import com.ehrocha.pulsar.model.FlowStep
import com.ehrocha.pulsar.model.FlowStepType
import com.ehrocha.pulsar.model.SavedFlow
import com.ehrocha.pulsar.service.PulsarNotificationService
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.coroutines.coroutineContext

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

    // ── Simulator ────────────────────────────────────────────────────────
    private val _simulatorActive = MutableStateFlow(false)
    val simulatorActive: StateFlow<Boolean> = _simulatorActive
    private var simulatorJob: Job? = null

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

    // ── GPIO pin config ──────────────────────────────────────────────────
    private val _pinShutter = MutableStateFlow(DEFAULT_PIN_SHUTTER)
    val pinShutter: StateFlow<Int> = _pinShutter
    private val _pinFocus = MutableStateFlow(DEFAULT_PIN_FOCUS)
    val pinFocus: StateFlow<Int> = _pinFocus

    // ── Custom Flow ──────────────────────────────────────────────────────
    private val _flowSteps = MutableStateFlow<List<FlowStep>>(emptyList())
    val flowSteps: StateFlow<List<FlowStep>> = _flowSteps
    private val _savedFlows = MutableStateFlow<List<SavedFlow>>(emptyList())
    val savedFlows: StateFlow<List<SavedFlow>> = _savedFlows
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

    // ── Notification / cancel ────────────────────────────────────────────
    private val cancelReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            if (intent.action == PulsarNotificationService.ACTION_CANCEL) {
                stop()
            }
        }
    }

    init {
        // Forward BLE manager state to ViewModel flows
        viewModelScope.launch {
            bleManager.connectionState.collect {
                _connected.value = it
                if (it) sendPins()
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
        // Load custom flow steps
        _flowSteps.value = try {
            FlowStep.deserializeList(prefs.getString(KEY_FLOW_STEPS, "") ?: "")
        } catch (_: Exception) { emptyList() }
        // Load saved flows library
        _savedFlows.value = try {
            SavedFlow.deserializeList(prefs.getString(KEY_SAVED_FLOWS, "") ?: "")
        } catch (_: Exception) { emptyList() }
        // Apply defaults as initial working values
        _intervalMs.value = _defaultIntervalMs.value
        _exposureMs.value = _defaultExposureMs.value
        _shotCount.value = _defaultShotCount.value
        _delayMs.value = _defaultDelayMs.value

        app.registerReceiver(
            cancelReceiver,
            IntentFilter(PulsarNotificationService.ACTION_CANCEL),
            Context.RECEIVER_NOT_EXPORTED,
        )

        // Auto-update notification as status frames arrive (throttled)
        viewModelScope.launch {
            var lastNotifShots = -1
            var lastNotifTimeMs = 0L
            status.collect { frame ->
                if (frame != null && (frame.state == DeviceState.RUNNING || frame.state == DeviceState.WAITING)) {
                    val now = System.currentTimeMillis()
                    val shotsChanged = frame.shotsTaken != lastNotifShots
                    val elapsed = now - lastNotifTimeMs
                    if (shotsChanged || elapsed >= NOTIFICATION_THROTTLE_MS) {
                        lastNotifShots = frame.shotsTaken
                        lastNotifTimeMs = now
                        updateNotification()
                    }
                } else if (frame != null && frame.state == DeviceState.IDLE) {
                    lastNotifShots = -1
                    dismissNotification()
                }
            }
        }

        // Auto-check for updates on connect
        viewModelScope.launch {
            _connected.collect { isConnected ->
                if (isConnected) {
                    // Request device hardware info from real device
                    if (!_simulatorActive.value) {
                        bleManager.sendCommand(CommandBuilder.deviceInfoRequest())
                    }
                    // Always check app update
                    appUpdateManager.checkForUpdate(
                        com.ehrocha.pulsar.BuildConfig.VERSION_NAME
                    )
                    // For real device, check firmware update once status arrives
                    if (!_simulatorActive.value) {
                        val frame = _status.filterNotNull().first()
                        if (frame.fwVersion.isNotEmpty()) {
                            firmwareManager.checkForUpdate(frame.fwVersion)
                        }
                    }
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
        if (!_simulatorActive.value) {
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
        bleManager.disconnectDevice()
    }


    // ── Field setters (encapsulation) ────────────────────────────────────
    fun setIntervalMs(v: Long) { _intervalMs.value = v.coerceAtLeast(AppConfig.MIN_INTERVAL_MS) }
    fun setExposureMs(v: Long) { _exposureMs.value = v.coerceAtLeast(AppConfig.MIN_EXPOSURE_MS) }
    fun setShotCount(v: Int) { _shotCount.value = v.coerceAtLeast(AppConfig.MIN_SHOT_COUNT) }
    fun setDelayMs(v: Long) { _delayMs.value = v }
    fun setAstroFocalLength(v: Int) { _astroFocalLength.value = v }
    fun setAstroCropFactor(v: Float) { _astroCropFactor.value = v }
    fun setAstroRuleDivisor(v: Int) { _astroRuleDivisor.value = v }
    fun setAstroGapMs(v: Long) { _astroGapMs.value = v.coerceAtLeast(AppConfig.MIN_ASTRO_GAP_MS) }
    fun setAstroShotCount(v: Int) { _astroShotCount.value = v.coerceAtLeast(AppConfig.MIN_SHOT_COUNT) }
    fun setAstroDelayMs(v: Long) { _astroDelayMs.value = v }

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
        bleManager.sendCommand(CommandBuilder.setPins(_pinShutter.value, _pinFocus.value))
    }

    // ── Custom Flow management ───────────────────────────────────────────

    fun saveFlowSteps(steps: List<FlowStep>) {
        _flowSteps.value = steps
        prefs.edit().putString(KEY_FLOW_STEPS, FlowStep.serializeList(steps)).apply()
    }

    fun saveFlowAs(name: String) {
        val flow = SavedFlow(name = name, steps = _flowSteps.value)
        val updated = _savedFlows.value.filter { it.name != name } + flow
        _savedFlows.value = updated
        prefs.edit().putString(KEY_SAVED_FLOWS, SavedFlow.serializeList(updated)).apply()
    }

    fun loadSavedFlow(name: String) {
        val flow = _savedFlows.value.firstOrNull { it.name == name } ?: return
        saveFlowSteps(flow.steps)
    }

    fun deleteSavedFlow(name: String) {
        val updated = _savedFlows.value.filter { it.name != name }
        _savedFlows.value = updated
        prefs.edit().putString(KEY_SAVED_FLOWS, SavedFlow.serializeList(updated)).apply()
    }

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
                // All steps complete
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
        // Stop the device if it's running
        if (!_simulatorActive.value) {
            bleManager.sendCommand(CommandBuilder.stop())
        }
        _flowRunning.value = false
        _flowPaused.value = false
        _flowCurrentStep.value = -1
        dismissNotification()
    }

    private suspend fun executeFlowStep(step: FlowStep) {
        when (step.type) {
            FlowStepType.PAUSE -> {
                _flowPaused.value = true
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
                val expS = step.ruleDivisor.toDouble() / (step.focalLength * step.cropFactor)
                val expMs = (expS * 1000).toLong().coerceAtLeast(AppConfig.MIN_ASTRO_EXPOSURE_MS)
                if (_simulatorActive.value) {
                    simulateShots(step.shotCount, expMs, step.gapMs, step.delayMs)
                } else {
                    sendModeCommand(
                        CommandBuilder.setAstro(step.gapMs, expMs, step.shotCount, step.delayMs)
                    )
                    waitForCompletion(step.shotCount)
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
            _status.value = _status.value?.copy(
                state = DeviceState.RUNNING, shotsTaken = shot - 1, timeRemainingMs = remaining,
            )
            delay(expMs)
            _status.value = _status.value?.copy(
                shotsTaken = shot, timeRemainingMs = remaining - expMs,
            )
            if (shot < totalShots) {
                _status.value = _status.value?.copy(state = DeviceState.WAITING)
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
        if (shutter in SAFE_OUTPUT_PINS && focus in SAFE_OUTPUT_PINS && shutter != focus) {
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
            prefs.edit().putString(KEY_SAVED_FLOWS, SavedFlow.serializeList(flows)).apply()
        }
    }

    fun sendConfig() {
        if (_simulatorActive.value) return
        val packet = when (_currentMode.value) {
            TriggerMode.INTERVALOMETER -> CommandBuilder.setIntervalometer(
                _intervalMs.value, _exposureMs.value, _shotCount.value, _delayMs.value
            )
            TriggerMode.ASTRO -> {
                val exposureS = _astroRuleDivisor.value.toDouble() / (_astroFocalLength.value * _astroCropFactor.value)
                val exposureMs = (exposureS * 1000).toLong().coerceAtLeast(AppConfig.MIN_ASTRO_EXPOSURE_MS)
                CommandBuilder.setAstro(
                    _astroGapMs.value, exposureMs, _astroShotCount.value, _astroDelayMs.value
                )
            }
            TriggerMode.PRESS_HOLD -> CommandBuilder.setPressHold()
            TriggerMode.PRESS_LOCK -> CommandBuilder.setPressLock()
            TriggerMode.CUSTOM_FLOW -> return  // app-orchestrated, no single command
        }
        bleManager.sendCommand(packet)
    }

    fun start() {
        if (_simulatorActive.value) {
            startSimulatorRun()
            return
        }
        sendConfig()
        bleManager.sendCommand(CommandBuilder.start())
        updateNotification()
    }

    fun stop() {
        if (_simulatorActive.value) {
            stopSimulatorRun()
            return
        }
        bleManager.sendCommand(CommandBuilder.stop())
        dismissNotification()
    }

    fun singleShot() {
        if (_simulatorActive.value) {
            viewModelScope.launch {
                _status.value = _status.value?.copy(state = DeviceState.RUNNING, shotsTaken = 1, timeRemainingMs = _exposureMs.value)
                delay(_exposureMs.value)
                _status.value = _status.value?.copy(state = DeviceState.IDLE, shotsTaken = 1, timeRemainingMs = 0L)
            }
            return
        }
        bleManager.sendCommand(CommandBuilder.shutter())
    }

    fun renameDevice(suffix: String) {
        if (!_simulatorActive.value) {
            bleManager.sendCommand(CommandBuilder.setName(suffix))
        }
        _deviceName.value = if (suffix.isNotEmpty()) "Pulsar-$suffix" else "Pulsar"
    }

    /** Press & Hold: shutter open on down */
    fun shutterDown() {
        if (_simulatorActive.value) {
            _status.value = _status.value?.copy(state = DeviceState.RUNNING)
            return
        }
        sendConfig()
        bleManager.sendCommand(CommandBuilder.start())
    }

    /** Press & Hold: shutter close on up */
    fun shutterUp() {
        if (_simulatorActive.value) {
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
            TriggerMode.ASTRO -> {
                val s = _astroRuleDivisor.value.toDouble() / (_astroFocalLength.value * _astroCropFactor.value)
                (s * 1000).toLong().coerceAtLeast(AppConfig.MIN_ASTRO_EXPOSURE_MS)
            }
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
                // Exposing
                _status.value = _status.value?.copy(
                    state = DeviceState.RUNNING,
                    shotsTaken = shot - 1,
                    timeRemainingMs = remaining,
                )
                delay(expMs)
                // Shot complete
                _status.value = _status.value?.copy(
                    shotsTaken = shot,
                    timeRemainingMs = remaining - expMs,
                )
                // Gap (except after last shot)
                if (shot < totalShots) {
                    _status.value = _status.value?.copy(state = DeviceState.WAITING)
                    delay(gapMs)
                }
            }

            // Done
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
    fun updateNotification() {
        val app = getApplication<Application>()
        val s = status.value
        val intent = Intent(app, PulsarNotificationService::class.java).apply {
            putExtra(PulsarNotificationService.EXTRA_MODE, _currentMode.value.name.replace('_', ' '))
            putExtra(PulsarNotificationService.EXTRA_SHOTS, s?.shotsTaken ?: 0)
            putExtra(PulsarNotificationService.EXTRA_TOTAL, _shotCount.value)
            putExtra(PulsarNotificationService.EXTRA_STATE, s?.state?.name ?: "RUNNING")
        }
        app.startForegroundService(intent)
    }

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

    private fun dismissNotification() {
        val app = getApplication<Application>()
        app.stopService(Intent(app, PulsarNotificationService::class.java))
    }

    override fun onCleared() {
        super.onCleared()
        stopScan()
        simulatorJob?.cancel()
        flowJob?.cancel()
        dismissNotification()
        try {
            getApplication<Application>().unregisterReceiver(cancelReceiver)
        } catch (_: Exception) {}
        bleManager.disconnectDevice()
    }
}
