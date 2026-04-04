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
        const val DEFAULT_PIN_SHUTTER = 25
        const val DEFAULT_PIN_FOCUS = 26
        val SAFE_OUTPUT_PINS = listOf(4, 13, 14, 16, 17, 18, 19, 21, 22, 23, 25, 26, 27)
    }

    private val prefs = app.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val bleManager = PulsarBleManager(app)

    // ── Astro dashboard ──────────────────────────────────────────────
    val dashboardManager = com.ehrocha.pulsar.astro.AstroDashboardManager(app)

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
    val intervalMs = MutableStateFlow(5000L)
    val exposureMs = MutableStateFlow(200L)
    val shotCount = MutableStateFlow(1)
    val delayMs = MutableStateFlow(0L)

    // ── Intervalometer defaults (persisted) ──────────────────────────────
    val defaultIntervalMs = MutableStateFlow(5000L)
    val defaultExposureMs = MutableStateFlow(200L)
    val defaultShotCount = MutableStateFlow(1)
    val defaultDelayMs = MutableStateFlow(0L)
    val maxShotCount = MutableStateFlow(999)

    // ── Sound params ─────────────────────────────────────────────────────
    val soundThreshold = MutableStateFlow(512)
    val soundExposureMs = MutableStateFlow(200L)

    // ── Lightning params ─────────────────────────────────────────────────
    val lightningSensitivity = MutableStateFlow(3)
    val lightningExposureMs = MutableStateFlow(200L)

    // ── Mode params (generic) ────────────────────────────────────────────
    val laserExposureMs = MutableStateFlow(200L)

    // ── Astro params ─────────────────────────────────────────────────────
    val astroFocalLength = MutableStateFlow(24)       // mm
    val astroCropFactor = MutableStateFlow(1.0f)      // Full Frame default
    val astroRuleDivisor = MutableStateFlow(500)      // 500 or 400
    val astroShotCount = MutableStateFlow(1)
    val astroDelayMs = MutableStateFlow(5000L)
    val astroGapMs = MutableStateFlow(2000L)          // gap between shots

    // ── GPIO pin config ──────────────────────────────────────────────────
    val pinShutter = MutableStateFlow(DEFAULT_PIN_SHUTTER)
    val pinFocus = MutableStateFlow(DEFAULT_PIN_FOCUS)

    // ── Custom Flow ──────────────────────────────────────────────────────
    val flowSteps = MutableStateFlow<List<FlowStep>>(emptyList())
    val savedFlows = MutableStateFlow<List<SavedFlow>>(emptyList())
    val flowRunning = MutableStateFlow(false)
    val flowPaused = MutableStateFlow(false)
    val flowCurrentStep = MutableStateFlow(-1)
    private var flowJob: Job? = null

    // ── BLE Scan ─────────────────────────────────────────────────────────
    private val btManager = app.getSystemService(BluetoothManager::class.java)
    private val scanner = btManager?.adapter?.bluetoothLeScanner

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
        defaultIntervalMs.value = prefs.getLong(KEY_INTV_INTERVAL, 5000L)
        defaultExposureMs.value = prefs.getLong(KEY_INTV_EXPOSURE, 200L)
        defaultShotCount.value = prefs.getInt(KEY_INTV_COUNT, 1)
        defaultDelayMs.value = prefs.getLong(KEY_INTV_DELAY, 0L)
        maxShotCount.value = prefs.getInt(KEY_INTV_MAX_SHOTS, 999)
        pinShutter.value = prefs.getInt(KEY_PIN_SHUTTER, DEFAULT_PIN_SHUTTER)
        pinFocus.value = prefs.getInt(KEY_PIN_FOCUS, DEFAULT_PIN_FOCUS)
        // Load custom flow steps
        flowSteps.value = try {
            FlowStep.deserializeList(prefs.getString(KEY_FLOW_STEPS, "") ?: "")
        } catch (_: Exception) { emptyList() }
        // Load saved flows library
        savedFlows.value = try {
            SavedFlow.deserializeList(prefs.getString(KEY_SAVED_FLOWS, "") ?: "")
        } catch (_: Exception) { emptyList() }
        // Apply defaults as initial working values
        intervalMs.value = defaultIntervalMs.value
        exposureMs.value = defaultExposureMs.value
        shotCount.value = defaultShotCount.value
        delayMs.value = defaultDelayMs.value

        app.registerReceiver(
            cancelReceiver,
            IntentFilter(PulsarNotificationService.ACTION_CANCEL),
            Context.RECEIVER_NOT_EXPORTED,
        )

        // Auto-update notification as status frames arrive
        viewModelScope.launch {
            status.collect { frame ->
                if (frame != null && (frame.state == DeviceState.RUNNING || frame.state == DeviceState.WAITING)) {
                    updateNotification()
                } else if (frame != null && frame.state == DeviceState.IDLE) {
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

        val filter = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid(PulsarUuids.SERVICE))
            .build()
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        scanner?.startScan(listOf(filter), settings, scanCallback)
        Log.i(TAG, "BLE scan started")
    }

    fun stopScan() {
        scanner?.stopScan(scanCallback)
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

    // ── Commands ─────────────────────────────────────────────────────────
    fun selectMode(mode: TriggerMode) {
        _currentMode.value = mode
    }

    fun saveIntervalometerDefaults(interval: Long, exposure: Long, count: Int, delay: Long) {
        defaultIntervalMs.value = interval
        defaultExposureMs.value = exposure
        defaultShotCount.value = count
        defaultDelayMs.value = delay
        prefs.edit()
            .putLong(KEY_INTV_INTERVAL, interval)
            .putLong(KEY_INTV_EXPOSURE, exposure)
            .putInt(KEY_INTV_COUNT, count)
            .putLong(KEY_INTV_DELAY, delay)
            .apply()
        // Apply to working values
        intervalMs.value = interval
        exposureMs.value = exposure
        shotCount.value = count
        delayMs.value = delay
    }

    fun saveMaxShotCount(max: Int) {
        maxShotCount.value = max.coerceIn(10, 9999)
        prefs.edit().putInt(KEY_INTV_MAX_SHOTS, maxShotCount.value).apply()
        // Clamp current values if they exceed the new max
        if (shotCount.value > maxShotCount.value) shotCount.value = maxShotCount.value
        if (defaultShotCount.value > maxShotCount.value) {
            saveIntervalometerDefaults(defaultIntervalMs.value, defaultExposureMs.value, maxShotCount.value, defaultDelayMs.value)
        }
    }

    fun resetIntervalometerDefaults() {
        saveIntervalometerDefaults(5000L, 200L, 1, 0L)
        saveMaxShotCount(999)
    }

    fun savePins(shutter: Int, focus: Int) {
        pinShutter.value = shutter
        pinFocus.value = focus
        prefs.edit()
            .putInt(KEY_PIN_SHUTTER, shutter)
            .putInt(KEY_PIN_FOCUS, focus)
            .apply()
        sendPins()
    }

    fun sendPins() {
        if (_simulatorActive.value) return
        bleManager.sendCommand(CommandBuilder.setPins(pinShutter.value, pinFocus.value))
    }

    // ── Custom Flow management ───────────────────────────────────────────

    fun saveFlowSteps(steps: List<FlowStep>) {
        flowSteps.value = steps
        prefs.edit().putString(KEY_FLOW_STEPS, FlowStep.serializeList(steps)).apply()
    }

    fun saveFlowAs(name: String) {
        val flow = SavedFlow(name = name, steps = flowSteps.value)
        val updated = savedFlows.value.filter { it.name != name } + flow
        savedFlows.value = updated
        prefs.edit().putString(KEY_SAVED_FLOWS, SavedFlow.serializeList(updated)).apply()
    }

    fun loadSavedFlow(name: String) {
        val flow = savedFlows.value.firstOrNull { it.name == name } ?: return
        saveFlowSteps(flow.steps)
    }

    fun deleteSavedFlow(name: String) {
        val updated = savedFlows.value.filter { it.name != name }
        savedFlows.value = updated
        prefs.edit().putString(KEY_SAVED_FLOWS, SavedFlow.serializeList(updated)).apply()
    }

    fun startFlow() {
        val steps = flowSteps.value
        if (steps.isEmpty()) return
        flowJob?.cancel()
        flowRunning.value = true
        flowPaused.value = false
        flowCurrentStep.value = 0
        flowJob = viewModelScope.launch {
            try {
                for (i in steps.indices) {
                    flowCurrentStep.value = i
                    executeFlowStep(steps[i])
                }
                // All steps complete
            } finally {
                flowRunning.value = false
                flowPaused.value = false
                flowCurrentStep.value = -1
            }
        }
    }

    fun continueFlow() {
        flowPaused.value = false
    }

    fun stopFlow() {
        flowJob?.cancel()
        flowJob = null
        // Stop the device if it's running
        if (!_simulatorActive.value) {
            bleManager.sendCommand(CommandBuilder.stop())
        }
        flowRunning.value = false
        flowPaused.value = false
        flowCurrentStep.value = -1
        dismissNotification()
    }

    private suspend fun executeFlowStep(step: FlowStep) {
        when (step.type) {
            FlowStepType.PAUSE -> {
                flowPaused.value = true
                // Wait until user taps Continue
                while (flowPaused.value) {
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
                val expMs = (expS * 1000).toLong().coerceAtLeast(100)
                if (_simulatorActive.value) {
                    simulateShots(step.shotCount, expMs, step.gapMs, step.delayMs)
                } else {
                    sendModeCommand(
                        CommandBuilder.setAstro(step.gapMs, expMs, step.shotCount, step.delayMs)
                    )
                    waitForCompletion(step.shotCount)
                }
            }
            FlowStepType.SOUND -> {
                if (_simulatorActive.value) {
                    simulateShots(step.shotCount, step.exposureMs, 1000L, 0L)
                } else {
                    sendModeCommand(
                        CommandBuilder.setSound(step.soundThreshold, step.exposureMs)
                    )
                    waitForShotCount(step.shotCount)
                }
            }
            FlowStepType.LIGHTNING -> {
                if (_simulatorActive.value) {
                    simulateShots(step.shotCount, step.exposureMs, 1000L, 0L)
                } else {
                    sendModeCommand(
                        CommandBuilder.setLightning(step.lightningSensitivity, step.exposureMs)
                    )
                    waitForShotCount(step.shotCount)
                }
            }
            FlowStepType.LASER -> {
                if (_simulatorActive.value) {
                    simulateShots(step.shotCount, step.exposureMs, 1000L, 0L)
                } else {
                    sendModeCommand(CommandBuilder.setLaser(step.exposureMs))
                    waitForShotCount(step.shotCount)
                }
            }
            FlowStepType.HDR -> {
                if (_simulatorActive.value) {
                    simulateHdr(step.hdrExposures)
                } else {
                    sendModeCommand(CommandBuilder.setHdr(step.hdrExposures))
                    waitForCompletion(step.hdrExposures.size)
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

    /** Simulate HDR bracket sequence in simulator. */
    private suspend fun simulateHdr(exposures: List<Long>) {
        for ((i, expMs) in exposures.withIndex()) {
            coroutineContext.ensureActive()
            _status.value = _status.value?.copy(
                state = DeviceState.RUNNING, shotsTaken = i, timeRemainingMs = expMs,
            )
            delay(expMs)
            _status.value = _status.value?.copy(shotsTaken = i + 1, timeRemainingMs = 0L)
            if (i < exposures.lastIndex) {
                _status.value = _status.value?.copy(state = DeviceState.WAITING)
                delay(500) // brief gap between HDR brackets
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

    /** Wait for reactive modes (sound/lightning/laser) to reach N shots, then stop. */
    private suspend fun waitForShotCount(targetCount: Int) {
        while (true) {
            coroutineContext.ensureActive()
            val s = _status.value
            if (s != null && s.shotsTaken >= targetCount) {
                bleManager.sendCommand(CommandBuilder.stop())
                // Wait for IDLE
                while (_status.value?.state != DeviceState.IDLE) {
                    delay(100)
                }
                break
            }
            delay(200)
        }
    }

    /** Serialize all persisted settings to JSON for export. */
    fun exportSettingsJson(): String {
        val json = org.json.JSONObject()
        json.put("intv_interval_ms", defaultIntervalMs.value)
        json.put("intv_exposure_ms", defaultExposureMs.value)
        json.put("intv_shot_count", defaultShotCount.value)
        json.put("intv_delay_ms", defaultDelayMs.value)
        json.put("intv_max_shots", maxShotCount.value)
        json.put("pin_shutter", pinShutter.value)
        json.put("pin_focus", pinFocus.value)
        // Custom flow steps
        json.put("flow_steps", org.json.JSONArray(flowSteps.value.map { it.toJson() }))
        // Saved flows library
        json.put("saved_flows", org.json.JSONArray(savedFlows.value.map { it.toJson() }))
        return json.toString(2)
    }

    /** Import settings from a JSON string. */
    fun importSettingsJson(json: String) {
        val obj = org.json.JSONObject(json)
        if (obj.has("intv_max_shots")) saveMaxShotCount(obj.getInt("intv_max_shots"))
        saveIntervalometerDefaults(
            obj.optLong("intv_interval_ms", 5000L),
            obj.optLong("intv_exposure_ms", 200L),
            obj.optInt("intv_shot_count", 1),
            obj.optLong("intv_delay_ms", 0L),
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
            savedFlows.value = flows
            prefs.edit().putString(KEY_SAVED_FLOWS, SavedFlow.serializeList(flows)).apply()
        }
    }

    fun sendConfig() {
        if (_simulatorActive.value) return
        val packet = when (_currentMode.value) {
            TriggerMode.INTERVALOMETER -> CommandBuilder.setIntervalometer(
                intervalMs.value, exposureMs.value, shotCount.value, delayMs.value
            )
            TriggerMode.ASTRO -> {
                val exposureS = astroRuleDivisor.value.toDouble() / (astroFocalLength.value * astroCropFactor.value)
                val exposureMs = (exposureS * 1000).toLong().coerceAtLeast(100)
                CommandBuilder.setAstro(
                    astroGapMs.value, exposureMs, astroShotCount.value, astroDelayMs.value
                )
            }
            TriggerMode.SOUND -> CommandBuilder.setSound(
                soundThreshold.value, soundExposureMs.value
            )
            TriggerMode.LIGHTNING -> CommandBuilder.setLightning(
                lightningSensitivity.value, lightningExposureMs.value
            )
            TriggerMode.LASER -> CommandBuilder.setLaser(laserExposureMs.value)
            TriggerMode.HDR -> CommandBuilder.setHdr(listOf(100, 200, 400, 800, 1600))
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
                _status.value = _status.value?.copy(state = DeviceState.RUNNING, shotsTaken = 1, timeRemainingMs = exposureMs.value)
                delay(exposureMs.value)
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
            batteryPct = 85,
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
            TriggerMode.INTERVALOMETER -> shotCount.value
            TriggerMode.ASTRO -> astroShotCount.value
            else -> 1
        }
        val expMs = when (mode) {
            TriggerMode.INTERVALOMETER -> exposureMs.value
            TriggerMode.ASTRO -> {
                val s = astroRuleDivisor.value.toDouble() / (astroFocalLength.value * astroCropFactor.value)
                (s * 1000).toLong().coerceAtLeast(100)
            }
            else -> exposureMs.value
        }
        val gapMs = when (mode) {
            TriggerMode.INTERVALOMETER -> intervalMs.value
            TriggerMode.ASTRO -> astroGapMs.value
            else -> 0L
        }
        val startDelayMs = when (mode) {
            TriggerMode.INTERVALOMETER -> delayMs.value
            TriggerMode.ASTRO -> astroDelayMs.value
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
            putExtra(PulsarNotificationService.EXTRA_TOTAL, shotCount.value)
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
