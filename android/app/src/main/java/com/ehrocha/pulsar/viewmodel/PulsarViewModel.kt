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
import android.net.wifi.WifiManager
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
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
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
        private const val KEY_PIN_SHUTTER = "pin_shutter"
        private const val KEY_PIN_FOCUS = "pin_focus"
        private const val KEY_FLOW_STEPS = "flow_steps"
        private const val KEY_SAVED_FLOWS = "saved_flows"
        private const val KEY_AUTO_OFF = "auto_off_minutes"
        private const val NOTIFICATION_THROTTLE_MS = 5_000L
        /** Poll failures before we abandon the long-poll and switch into
         *  reconnect mode. 5× short long-polls (~10 s each at worst) gives
         *  a fast WiFi handoff or short auto-off blip a chance to recover. */
        private const val MAX_CANON_POLL_FAILS = 5
        /** Cap on the reconnect loop after polling gave up. ~2 min covers the
         *  common case (camera woke from auto-off, phone re-joined the AP).
         *  Beyond that the session is treated as truly dead. */
        private const val CANON_RECONNECT_TIMEOUT_MS = 120_000L
        private const val CANON_RECONNECT_BACKOFF_MS = 3_000L
        const val DEFAULT_PIN_SHUTTER = AppConfig.DEFAULT_PIN_SHUTTER
        const val DEFAULT_PIN_FOCUS = AppConfig.DEFAULT_PIN_FOCUS
        val SAFE_OUTPUT_PINS = AppConfig.SAFE_OUTPUT_PINS
        private const val CANON_CREDS_PREFS = "pulsar_canon_creds"
        private const val CANON_CREDS_PREFS_ENCRYPTED = "pulsar_canon_creds_v2"
        /** Settings-export envelope schema. v1 = original shape. Increment
         *  on field removal or rename; additive changes don't require a
         *  bump. */
        const val SETTINGS_EXPORT_SCHEMA = "pulsar-settings/1"

        /** Build the EncryptedSharedPreferences for CCAPI digest creds. On
         *  first run, copies anything still in the v1 plaintext file to v2
         *  and wipes v1. If Keystore initialisation fails for any reason
         *  (corrupted master key, GMS issues), falls back to the plaintext
         *  file so the user isn't locked out — logs a warning. */
        private fun buildCanonCredsPrefs(context: Context): android.content.SharedPreferences {
            return try {
                val masterKey = androidx.security.crypto.MasterKey.Builder(context)
                    .setKeyScheme(androidx.security.crypto.MasterKey.KeyScheme.AES256_GCM)
                    .build()
                val encrypted = androidx.security.crypto.EncryptedSharedPreferences.create(
                    context,
                    CANON_CREDS_PREFS_ENCRYPTED,
                    masterKey,
                    androidx.security.crypto.EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    androidx.security.crypto.EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
                )
                // One-shot migration: pull any plaintext entries forward then
                // wipe the old file so creds aren't sitting in cleartext.
                val legacy = context.getSharedPreferences(CANON_CREDS_PREFS, Context.MODE_PRIVATE)
                if (legacy.all.isNotEmpty()) {
                    val edit = encrypted.edit()
                    legacy.all.forEach { (k, v) -> if (v is String) edit.putString(k, v) }
                    edit.apply()
                    legacy.edit().clear().apply()
                    android.util.Log.i(TAG, "Migrated ${legacy.all.size} canon creds entries to encrypted prefs")
                }
                encrypted
            } catch (e: Exception) {
                android.util.Log.w(TAG, "EncryptedSharedPreferences init failed, falling back to plaintext", e)
                context.getSharedPreferences(CANON_CREDS_PREFS, Context.MODE_PRIVATE)
            }
        }
    }

    private val prefs = app.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    val shotLog = com.ehrocha.pulsar.model.ShotLog(prefs)

    // ── BLE (scan + connection) ─────────────────────────────────────────
    val bleController = com.ehrocha.pulsar.ble.BleController(app)
    private val bleManager get() = bleController.bleManager

    val scanning: StateFlow<Boolean> = bleController.scanning
    val devices: StateFlow<List<com.ehrocha.pulsar.ble.ScannedDevice>> = bleController.devices

    // ── CCAPI (Canon WiFi) discovery ─────────────────────────────────────
    // Runs alongside BLE scan; scan card shows both lists.
    private val ccapiDiscovery = com.ehrocha.pulsar.transport.ccapi.CcapiDiscovery(app)
    val canonCameras: StateFlow<List<com.ehrocha.pulsar.transport.ccapi.CanonCamera>> =
        ccapiDiscovery.cameras

    /** Current Wi-Fi SSID, or null if not on Wi-Fi / permission missing.
     *  Surfaced in the scan card so users can confirm they joined the
     *  camera's AP before tapping a discovered Canon. */
    private val _currentWifiSsid = MutableStateFlow<String?>(null)
    val currentWifiSsid: StateFlow<String?> = _currentWifiSsid

    @Suppress("DEPRECATION", "MissingPermission")
    private fun refreshWifiSsid() {
        // `connectionInfo.ssid` is deprecated for SDK 31+ but still works
        // with location permission; the recommended replacement
        // (`ConnectivityManager.NetworkCapabilities.transportInfo`) requires
        // listening for callbacks, which is heavier than we need for a
        // periodic display hint. Pulsar already holds FINE_LOCATION for BLE.
        val wifi = getApplication<Application>()
            .getSystemService(Context.WIFI_SERVICE) as? WifiManager
        val raw = wifi?.connectionInfo?.ssid
        val cleaned = raw
            ?.takeIf { it.isNotBlank() && it != "<unknown ssid>" }
            ?.removeSurrounding("\"")
        _currentWifiSsid.value = cleaned
    }

    // ── Canon CCAPI transport (Phase 2) ──────────────────────────────────
    // When non-null, the app talks to a Canon camera over HTTP instead of BLE.
    // Mutually exclusive with BLE & simulator — picking one disconnects the
    // others. Only Timelapse runs are supported in Phase 2.
    private val _canonTransport =
        MutableStateFlow<com.ehrocha.pulsar.transport.ccapi.CcapiTransport?>(null)
    val canonTransport: StateFlow<com.ehrocha.pulsar.transport.ccapi.CcapiTransport?> =
        _canonTransport
    private val _canonConnecting = MutableStateFlow(false)
    val canonConnecting: StateFlow<Boolean> = _canonConnecting
    private val _canonError = MutableStateFlow<String?>(null)
    val canonError: StateFlow<String?> = _canonError

    /** When non-null, the UI should prompt for username + password — the
     *  previous connect attempt to this camera returned 401. Cleared on
     *  successful connect or cancel. */
    private val _canonAuthPrompt =
        MutableStateFlow<com.ehrocha.pulsar.transport.ccapi.CanonCamera?>(null)
    val canonAuthPrompt: StateFlow<com.ehrocha.pulsar.transport.ccapi.CanonCamera?> =
        _canonAuthPrompt

    /** True while the polling loop has lost contact with the camera and is
     *  trying to reach it again — the UI keeps the session alive (no return
     *  to the scan screen) and shows a reconnecting banner. */
    private val _canonReconnecting = MutableStateFlow(false)
    val canonReconnecting: StateFlow<Boolean> = _canonReconnecting

    /** True when the connected Canon body advertises the manual-bulb endpoint.
     *  Older or PowerShot-class bodies don't — the UI hides bulb-based modes
     *  for them (only Timelapse / Manual / Custom flow remain useful). */
    val canonSupportsBulb: StateFlow<Boolean> = _canonTransport
        .map { it?.supportsBulb == true }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /** Per-UDN digest credentials. Each entry lets the next connect to the
     *  same camera skip the auth prompt entirely. */
    /** Encrypted-at-rest prefs file (AES-256, key in Android Keystore) for
     *  CCAPI digest credentials. Migrated from a plain SharedPreferences
     *  file in v0.237. Falls back to the plain file if EncryptedSharedPrefs
     *  fails to initialise (extremely rare; would indicate a Keystore fault).
     *  Old plain file is read once on first launch to migrate any saved
     *  creds, then cleared. */
    private val canonCredsPrefs = buildCanonCredsPrefs(app)

    /** Per-UDN user-set nicknames for Canon cameras. Shown in the scan card
     *  and as the connected device label in place of the body's own name. */
    private val canonNicknamesPrefs =
        app.getSharedPreferences("pulsar_canon_nicks", Context.MODE_PRIVATE)
    private val _canonNicknames = MutableStateFlow(loadAllCanonNicknames())
    val canonNicknames: StateFlow<Map<String, String>> = _canonNicknames

    private fun loadAllCanonNicknames(): Map<String, String> =
        canonNicknamesPrefs.all
            .mapNotNull { (k, v) -> if (v is String && k.isNotEmpty()) k to v else null }
            .toMap()

    /** Set or clear (empty string) the user-facing nickname for a Canon camera.
     *  The new value flows through [canonNicknames]; if this camera is
     *  currently active, its [deviceName] is updated immediately. */
    fun setCanonNickname(udn: String, nickname: String) {
        val trimmed = nickname.trim()
        val edit = canonNicknamesPrefs.edit()
        if (trimmed.isEmpty()) edit.remove(udn) else edit.putString(udn, trimmed)
        edit.apply()
        _canonNicknames.value = loadAllCanonNicknames()
        val active = _canonTransport.value
        if (active != null && active.camera.udn == udn) {
            _deviceName.value = effectiveCanonName(active.camera)
        }
    }

    /** Display name for a Canon camera. Precedence: user nickname > body
     *  nickname > body friendly name. */
    fun effectiveCanonName(camera: com.ehrocha.pulsar.transport.ccapi.CanonCamera): String =
        _canonNicknames.value[camera.udn]?.takeIf { it.isNotEmpty() }
            ?: camera.nickname
            ?: camera.friendlyName

    /** Snapshot of what a Canon body advertises in its `/ccapi` endpoint
     *  matrix. Populated by [probeCanonCapabilities] for the "Capabilities"
     *  menu item in the scan card. */
    data class CanonCapabilities(
        val version: String,
        val endpointCount: Int,
        val supportsBulb: Boolean,
        val supportsDialIgnore: Boolean,
        val supportsPolling: Boolean,
        val supportsShootingMode: Boolean,
    )

    /** One-shot probe used by the scan UI. Reuses any saved credentials for
     *  this camera. Returns null on auth-required / network failure — callers
     *  surface that as "couldn't probe". */
    suspend fun probeCanonCapabilities(
        camera: com.ehrocha.pulsar.transport.ccapi.CanonCamera,
    ): CanonCapabilities? {
        val creds = loadCanonCreds(camera.udn)
        val client = com.ehrocha.pulsar.transport.ccapi.CcapiClient(camera.accessUrl, creds)
        val r = client.connect()
        if (r !is com.ehrocha.pulsar.transport.ccapi.CcapiClient.Result.Ok) return null
        return CanonCapabilities(
            version = client.version ?: "?",
            endpointCount = client.endpoints[client.version]?.length() ?: 0,
            supportsBulb = client.supports("/shooting/control/shutterbutton/manual", "post"),
            supportsDialIgnore = client.supports("/shooting/control/ignoreshootingmodedialmode", "post"),
            supportsPolling = client.supports("/event/polling", "get"),
            supportsShootingMode = client.supports("/shooting/settings/shootingmode", "put"),
        )
    }

    // Connection-side flows. [status] is multiplexed below — BLE updates flow
    // in, but the simulator can write directly when it's running.
    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected

    private val _status = MutableStateFlow<StatusFrame?>(null)
    val status: StateFlow<StatusFrame?> = _status

    val deviceInfo: StateFlow<DeviceInfo?> = bleController.deviceInfo
    val rssi: StateFlow<Int?> = bleController.rssi
    val latencyMs: StateFlow<Int?> = bleController.latencyMs

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

    /** Flip the bookmark flag for a user mode by id. Bookmarked modes
     *  show up as quick-launch tiles in the Trigger tab. */
    fun toggleUserModeBookmark(id: String) {
        val existing = _userModes.value.firstOrNull { it.id == id } ?: return
        upsertUserMode(existing.copy(bookmarked = !existing.bookmarked))
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
     *  flows. See [RunState]. */
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
                // Canon takes priority — don't let BLE disconnect kick the
                // user out of an active Canon session.
                if (_canonTransport.value != null) return@collect
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
                if (!_simulatorActive.value && _canonTransport.value == null) {
                    _status.value = it
                }
            }
        }

        // Seed working values from persisted prefs.
        _intervalMs.value = prefs.getLong(KEY_INTV_INTERVAL, AppConfig.DEFAULT_INTERVAL_MS)
        _exposureMs.value = prefs.getLong(KEY_INTV_EXPOSURE, AppConfig.DEFAULT_EXPOSURE_MS)
        _shotCount.value = prefs.getInt(KEY_INTV_COUNT, AppConfig.DEFAULT_SHOT_COUNT)
        _delayMs.value = prefs.getLong(KEY_INTV_DELAY, AppConfig.DEFAULT_DELAY_MS)
        _pinShutter.value = prefs.getInt(KEY_PIN_SHUTTER, DEFAULT_PIN_SHUTTER)
        _pinFocus.value = prefs.getInt(KEY_PIN_FOCUS, DEFAULT_PIN_FOCUS)
        _autoOffMinutes.value = prefs.getInt(KEY_AUTO_OFF, 5)
        _flowSteps.value = try {
            FlowStep.deserializeList(prefs.getString(KEY_FLOW_STEPS, "") ?: "")
        } catch (_: Exception) { emptyList() }
        _savedFlows.value = try {
            SavedFlow.deserializeList(prefs.getString(KEY_SAVED_FLOWS, "") ?: "")
        } catch (_: Exception) { emptyList() }
        _combinedFlows.value = FlowPresets.ALL + _savedFlows.value

        // App-update check fires once on app launch (independent of any
        // connection). Previously this was gated on _connected.collect, which
        // meant users who hadn't paired a device yet never saw a new version.
        appUpdateManager.checkForUpdate(com.ehrocha.pulsar.BuildConfig.VERSION_NAME)

        // Firmware update check requires a connected ESP32 — needs the chip
        // model + current firmware version from DEVICE_INFO + status frames.
        viewModelScope.launch {
            _connected.collect { isConnected ->
                if (isConnected && !_simulatorActive.value) {
                    deviceInfo.filterNotNull().first()
                    val frame = _status.filterNotNull().first()
                    if (frame.fwVersion.isNotEmpty()) {
                        firmwareManager.checkForUpdate(frame.fwVersion)
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

    fun startScan() {
        bleController.startScan()
        ccapiDiscovery.start()
        refreshWifiSsid()
    }
    fun stopScan() {
        bleController.stopScan()
        ccapiDiscovery.stop()
    }

    @SuppressLint("MissingPermission")
    fun connectTo(device: BluetoothDevice) {
        // Hang up any active Canon session so transports stay single-valued.
        if (_canonTransport.value != null) disconnectCanon()
        _deviceName.value = device.name ?: "Pulsar"
        bleController.connect(device)
    }

    /** Open an HTTP session to a Canon CCAPI camera. Pins the API version and
     *  flips [connected] true on success. Mutually exclusive with BLE — any
     *  active BLE session is dropped first. Phase 2: Timelapse only. */
    fun connectCanon(
        camera: com.ehrocha.pulsar.transport.ccapi.CanonCamera,
        credentials: com.ehrocha.pulsar.transport.ccapi.CcapiClient.Credentials? = null,
    ) {
        viewModelScope.launch {
            _canonError.value = null
            _canonConnecting.value = true
            // Hang up any existing BLE link so the UI's notion of "what's
            // connected" stays single-valued.
            if (bleController.connected.value) bleController.disconnect()
            stopScan()

            // Reuse saved digest creds for this camera if the caller didn't
            // provide explicit ones (e.g. on app restart, user picks the same
            // camera — we shouldn't prompt again).
            val effectiveCreds = credentials ?: loadCanonCreds(camera.udn)
            val transport = com.ehrocha.pulsar.transport.ccapi.CcapiTransport(camera, effectiveCreds)
            val result = transport.connect()
            _canonConnecting.value = false
            when (result) {
                is com.ehrocha.pulsar.transport.ccapi.CcapiClient.Result.Ok -> {
                    _canonTransport.value = transport
                    _canonAuthPrompt.value = null
                    _deviceName.value = effectiveCanonName(camera)
                    _connected.value = true
                    _status.value = StatusFrame(
                        state = DeviceState.IDLE,
                        mode = TriggerMode.TIMELAPSE.id,
                        shotsTaken = 0,
                        timeRemainingMs = 0L,
                        batteryPct = 0,
                        errorCode = 0,
                        fwVersion = "",
                    )
                    // Persist creds only once they're known-good.
                    if (credentials != null) saveCanonCreds(camera.udn, credentials)
                    // Seed battery before the long-poll has a chance to fire
                    // — polling only delivers changed fields, so a static
                    // battery would otherwise show as 0%.
                    launch {
                        transport.getBatteryStatus()?.let { applyCanonPollUpdate(it, 0) }
                    }
                    startCanonPolling(transport)
                }
                is com.ehrocha.pulsar.transport.ccapi.CcapiClient.Result.NeedsAuth -> {
                    // Either the camera requires auth from cold start, or
                    // saved creds are stale (user changed password on the
                    // body). Drop the stale entry and prompt fresh.
                    if (effectiveCreds != null) clearCanonCreds(camera.udn)
                    _canonAuthPrompt.value = camera
                    _canonError.value = "auth_required"
                }
                is com.ehrocha.pulsar.transport.ccapi.CcapiClient.Result.Http ->
                    _canonError.value = "http_${result.code}"
                is com.ehrocha.pulsar.transport.ccapi.CcapiClient.Result.Network ->
                    _canonError.value = "network"
            }
        }
    }

    /** Called from the credentials dialog. Cleans the prior error state then
     *  re-runs [connectCanon] with the supplied digest credentials. */
    fun submitCanonCredentials(
        camera: com.ehrocha.pulsar.transport.ccapi.CanonCamera,
        username: String,
        password: String,
    ) {
        _canonError.value = null
        connectCanon(
            camera,
            com.ehrocha.pulsar.transport.ccapi.CcapiClient.Credentials(username, password),
        )
    }

    fun cancelCanonAuth() {
        _canonAuthPrompt.value = null
        _canonError.value = null
    }

    fun clearCanonError() { _canonError.value = null }

    private val _canonManualAdding = MutableStateFlow(false)
    val canonManualAdding: StateFlow<Boolean> = _canonManualAdding
    private val _canonManualError = MutableStateFlow<String?>(null)
    val canonManualError: StateFlow<String?> = _canonManualError

    /** Manually probe `http://<host>[:<port>]/ccapi` and add the camera to
     *  the scan list if it responds. Bypasses SSDP and the UPnP device
     *  descriptor entirely — many Canon bodies (notably the EOS RP) don't
     *  serve `/upnp/CameraDevDesc.xml`, but every CCAPI-active body answers
     *  `GET /ccapi` directly. Metadata (model + serial → stable UDN) comes
     *  from `/deviceinformation` once the probe succeeds. */
    fun addCanonByHost(rawInput: String, onResult: (Boolean) -> Unit) {
        val trimmed = rawInput.trim()
            .removePrefix("http://").removePrefix("https://")
            .substringBefore('/')
        if (trimmed.isEmpty()) {
            _canonManualError.value = "invalid"
            onResult(false)
            return
        }
        val (host, explicitPort) = if (':' in trimmed) {
            val (h, p) = trimmed.split(':', limit = 2)
            h to p.toIntOrNull()
        } else trimmed to null
        val portsToTry = explicitPort?.let { listOf(it) } ?: listOf(8080, 80, 8612)

        viewModelScope.launch {
            _canonManualError.value = null
            _canonManualAdding.value = true
            try {
                for (port in portsToTry) {
                    val accessUrl = "http://$host:$port/ccapi"
                    Log.i(TAG, "Probing $accessUrl")
                    // Reuse the per-UDN credential store if we can — but we
                    // don't know the UDN yet. Try unauthenticated first; the
                    // client will surface NeedsAuth and the user can retry
                    // from the regular auth dialog once the camera is added.
                    val probeClient = com.ehrocha.pulsar.transport.ccapi.CcapiClient(accessUrl)
                    val r = probeClient.connect()
                    if (r !is com.ehrocha.pulsar.transport.ccapi.CcapiClient.Result.Ok) {
                        Log.i(TAG, "  → $r")
                        continue
                    }
                    val camera = buildManualCamera(probeClient, host, port, accessUrl)
                    ccapiDiscovery.addManual(camera)
                    onResult(true)
                    return@launch
                }
                _canonManualError.value = "not_found"
                onResult(false)
            } finally {
                _canonManualAdding.value = false
            }
        }
    }

    /** Try to pull friendly name + a stable identifier from `/deviceinformation`.
     *  Falls back to host:port-derived defaults if the endpoint is missing or
     *  doesn't return the fields we'd like. Best-effort — the camera entry is
     *  still useful even if metadata is generic. */
    private suspend fun buildManualCamera(
        client: com.ehrocha.pulsar.transport.ccapi.CcapiClient,
        host: String,
        port: Int,
        accessUrl: String,
    ): com.ehrocha.pulsar.transport.ccapi.CanonCamera {
        var name = "Canon Camera"
        var udn = "manual:$host:$port"
        if (client.supports("/deviceinformation", "get")) {
            val r = client.get("/deviceinformation")
            if (r is com.ehrocha.pulsar.transport.ccapi.CcapiClient.Result.Ok) {
                runCatching {
                    val json = org.json.JSONObject(r.value)
                    json.optString("productname").takeIf { it.isNotBlank() }?.let { name = it }
                    // Prefer guid > serialnumber > macaddress as the stable
                    // per-camera id. All are body-side identifiers that
                    // survive IP changes.
                    val stable = listOf("guid", "serialnumber", "macaddress")
                        .firstNotNullOfOrNull { json.optString(it).takeIf { v -> v.isNotBlank() } }
                    if (stable != null) udn = "canon:$stable"
                }
            }
        }
        return com.ehrocha.pulsar.transport.ccapi.CanonCamera(
            udn = udn,
            friendlyName = name,
            nickname = null,
            ipAddress = host,
            port = port,
            accessUrl = accessUrl,
        )
    }

    fun clearCanonManualError() { _canonManualError.value = null }

    /** Fire-and-forget stop of any running Canon live-view session. Safe to
     *  call from non-suspending contexts (e.g. `DisposableEffect.onDispose`)
     *  — runs in the viewmodel scope. */
    fun stopCanonLiveView() {
        val transport = _canonTransport.value ?: return
        viewModelScope.launch { transport.stopLiveView() }
    }

    // ── Doze / battery optimisation ──────────────────────────────────────

    /** True when Pulsar is on the OS's battery-optimisation allow-list, so
     *  long-running flows aren't throttled by Doze. Re-read every time the
     *  Settings screen is opened (no broadcast for changes — the user can
     *  flip it in system Settings without us knowing). */
    fun isIgnoringBatteryOptimizations(): Boolean {
        val pm = getApplication<Application>()
            .getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager ?: return false
        return pm.isIgnoringBatteryOptimizations(getApplication<Application>().packageName)
    }

    /** Intent that opens the system "Request to ignore battery optimisations"
     *  dialog targeting Pulsar. Caller should `startActivity` it. */
    fun batteryOptimisationRequestIntent(): Intent {
        val pkg = getApplication<Application>().packageName
        return Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
            .setData(android.net.Uri.parse("package:$pkg"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    private fun loadCanonCreds(udn: String):
            com.ehrocha.pulsar.transport.ccapi.CcapiClient.Credentials? {
        val user = canonCredsPrefs.getString("u:$udn", null) ?: return null
        val pass = canonCredsPrefs.getString("p:$udn", null) ?: return null
        return com.ehrocha.pulsar.transport.ccapi.CcapiClient.Credentials(user, pass)
    }

    private fun saveCanonCreds(
        udn: String,
        creds: com.ehrocha.pulsar.transport.ccapi.CcapiClient.Credentials,
    ) {
        canonCredsPrefs.edit()
            .putString("u:$udn", creds.username)
            .putString("p:$udn", creds.password)
            .apply()
    }

    private fun clearCanonCreds(udn: String) {
        canonCredsPrefs.edit().remove("u:$udn").remove("p:$udn").apply()
    }

    private var canonPollJob: Job? = null

    /** Long-poll `/event/polling` for live battery + shot count. On a streak of
     *  failures the loop enters reconnect mode and re-probes `/ccapi` for up
     *  to [CANON_RECONNECT_TIMEOUT_MS] — covers the common case of the camera
     *  briefly napping or the phone roaming. Persistent failure drops the
     *  session for real. The poll is independent of the run loop — it runs
     *  whether or not a flow is active. */
    private fun startCanonPolling(transport: com.ehrocha.pulsar.transport.ccapi.CcapiTransport) {
        canonPollJob?.cancel()
        canonPollJob = viewModelScope.launch {
            var consecutiveFails = 0
            // Tracks shot count via `addedcontents` so the camera's own
            // counter — including any shots fired with the body's hardware
            // button — feeds back into the UI.
            var observedShots = 0
            while (currentCoroutineContext().isActive &&
                   _canonTransport.value === transport) {
                when (val r = transport.pollEvents()) {
                    is com.ehrocha.pulsar.transport.ccapi.CcapiClient.Result.Ok -> {
                        // Healthy poll: clear any prior failure / reconnect state.
                        if (_canonReconnecting.value) _canonReconnecting.value = false
                        consecutiveFails = 0
                        observedShots = applyCanonPollUpdate(r.value, observedShots)
                    }
                    else -> {
                        consecutiveFails += 1
                        if (consecutiveFails >= MAX_CANON_POLL_FAILS) {
                            Log.w(TAG, "Canon polling failed $consecutiveFails× — entering reconnect")
                            val recovered = attemptCanonReconnect(transport)
                            if (recovered) {
                                consecutiveFails = 0
                                continue
                            }
                            _canonError.value = "dropped"
                            disconnectCanon()
                            break
                        }
                        // Short backoff before the next long-poll so we don't
                        // hammer the camera. The long-poll itself is the bulk
                        // of the wait when the network is fine.
                        delay(2_000)
                    }
                }
            }
        }
    }

    /** Re-probe `GET /ccapi` on a backoff until the camera responds or we hit
     *  [CANON_RECONNECT_TIMEOUT_MS]. Keeps the existing transport / UI state
     *  intact so the user doesn't get bounced back to the scan screen for a
     *  transient blip. Returns true on recovery, false on giving up. */
    private suspend fun attemptCanonReconnect(
        transport: com.ehrocha.pulsar.transport.ccapi.CcapiTransport,
    ): Boolean {
        _canonReconnecting.value = true
        val deadline = System.currentTimeMillis() + CANON_RECONNECT_TIMEOUT_MS
        try {
            while (System.currentTimeMillis() < deadline &&
                   _canonTransport.value === transport) {
                coroutineContext.ensureActive()
                val r = transport.reconnect()
                if (r is com.ehrocha.pulsar.transport.ccapi.CcapiClient.Result.Ok) {
                    Log.i(TAG, "Canon reconnect succeeded")
                    return true
                }
                delay(CANON_RECONNECT_BACKOFF_MS)
            }
            return false
        } finally {
            _canonReconnecting.value = false
        }
    }

    /** Maps the long-poll payload onto our [StatusFrame]: battery level
     *  string → percent, addedcontents → cumulative shot count. */
    private fun applyCanonPollUpdate(json: org.json.JSONObject, prevShots: Int): Int {
        val current = _status.value ?: return prevShots
        var nextShots = prevShots

        // `battery` in poll/devicestatus responses comes in two shapes
        // depending on the body: an object (single battery) or an array
        // (multi-cell battery grip). Both are wrapped here.
        val battObj = json.optJSONObject("battery")
            ?: json.optJSONArray("battery")?.optJSONObject(0)
        if (battObj != null) {
            val pct = canonBatteryToPct(battObj.optString("level"))
            if (pct != null) _status.value = current.copy(batteryPct = pct)
        }

        // `addedcontents` is the list of files added since the last poll. Add
        // its length to our running counter so the wizard's progress matches
        // what's actually on the card.
        val added = json.optJSONArray("addedcontents")
        if (added != null && added.length() > 0) {
            nextShots += added.length()
            // Only stomp shotsTaken if the run loop isn't authoritative for
            // this moment (e.g. while we're in WAITING / IDLE between shots).
            val s = _status.value ?: return nextShots
            if (s.state != DeviceState.RUNNING) {
                _status.value = s.copy(shotsTaken = nextShots.coerceAtLeast(s.shotsTaken))
            }
        }
        return nextShots
    }

    /** Canon's battery `level` strings map onto rough percentages. Numeric
     *  forms ("85%") are parsed when present. */
    private fun canonBatteryToPct(level: String?): Int? {
        if (level.isNullOrEmpty()) return null
        // "85%" / "85" — parse the integer prefix.
        level.trimEnd('%').toIntOrNull()?.let { return it.coerceIn(0, 100) }
        return when (level.lowercase()) {
            "full" -> 100
            "high" -> 80
            "half" -> 50
            "low", "quarter" -> 20
            "charge", "chargestop", "chargecomp" -> 0
            else -> null
        }
    }

    private fun disconnectCanon() {
        val transport = _canonTransport.value ?: return
        canonPollJob?.cancel()
        canonPollJob = null
        _canonReconnecting.value = false
        viewModelScope.launch { transport.release() }
        _canonTransport.value = null
        if (!bleController.connected.value && !_simulatorActive.value) {
            _connected.value = false
            _status.value = null
        }
    }

    fun disconnect() {
        if (_canonTransport.value != null) {
            // Cancel any running flow first so the Canon loop stops firing.
            if (_flowRunning.value) {
                flowJob?.cancel()
                flowJob = null
                _flowRunning.value = false
                _flowPaused.value = false
                _flowCurrentStep.value = -1
            }
            disconnectCanon()
            return
        }
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
    // Setters persist to prefs so the user's last-edited working values
    // survive restarts. Previously `saveIntervalometerDefaults` did this via
    // a now-deleted settings panel; now the working values are themselves
    // the only persisted state.
    fun setIntervalMs(v: Long) {
        val clamped = v.coerceAtLeast(AppConfig.MIN_INTERVAL_MS)
        _intervalMs.value = clamped
        prefs.edit().putLong(KEY_INTV_INTERVAL, clamped).apply()
    }
    fun setExposureMs(v: Long) {
        val clamped = v.coerceAtLeast(AppConfig.MIN_EXPOSURE_MS)
        _exposureMs.value = clamped
        prefs.edit().putLong(KEY_INTV_EXPOSURE, clamped).apply()
    }
    fun setShotCount(v: Int) {
        // 0 is a sentinel for "continuous — run until STOP". Firmware
        // treats count==0 as no auto-completion check.
        val clamped = v.coerceAtLeast(0)
        _shotCount.value = clamped
        prefs.edit().putInt(KEY_INTV_COUNT, clamped).apply()
    }
    fun setDelayMs(v: Long) {
        _delayMs.value = v
        prefs.edit().putLong(KEY_INTV_DELAY, v).apply()
    }
    fun setAstroFocalLength(v: Int) { _astroFocalLength.value = v }
    fun setAstroCropFactor(v: Float) { _astroCropFactor.value = v }
    fun setAstroRuleDivisor(v: Int) { _astroRuleDivisor.value = v }
    fun setAstroGapMs(v: Long) { _astroGapMs.value = v.coerceAtLeast(AppConfig.MIN_ASTRO_GAP_MS) }
    fun setAstroShotCount(v: Int) { _astroShotCount.value = v.coerceAtLeast(0) }
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
        // cancelAndJoin so the previous job's `finally` (which clears
        // _flowRunning/_flowCurrentStep) runs to completion before we set the
        // new run's state — avoids a brief window where _flowRunning flickers
        // false right after the new launch.
        viewModelScope.launch {
            flowJob?.cancelAndJoin()
            _flowRunning.value = true
            _flowPaused.value = false
            _flowCurrentStep.value = 0
            val startedAt = System.currentTimeMillis()
            val plannedShots = steps.sumOf { plannedShotsFor(it) }
            val (modeLabel, expMs, intvMs) = summarizeSteps(steps)
            var threw = false
            flowJob = launch {
                try {
                    for (i in steps.indices) {
                        _flowCurrentStep.value = i
                        executeFlowStep(steps[i])
                    }
                } catch (t: Throwable) {
                    threw = true
                    throw t
                } finally {
                    val endedAt = System.currentTimeMillis()
                    val completed = _status.value?.shotsTaken ?: 0
                    val status = when {
                        threw && completed < plannedShots -> com.ehrocha.pulsar.model.ShotLogStatus.STOPPED
                        completed >= plannedShots -> com.ehrocha.pulsar.model.ShotLogStatus.COMPLETED
                        else -> com.ehrocha.pulsar.model.ShotLogStatus.STOPPED
                    }
                    shotLog.record(
                        com.ehrocha.pulsar.model.ShotLogEntry(
                            id = startedAt,
                            startedAtMs = startedAt,
                            endedAtMs = endedAt,
                            modeLabel = modeLabel,
                            stepCount = steps.size,
                            plannedShots = plannedShots,
                            completedShots = completed,
                            exposureMs = expMs,
                            intervalMs = intvMs,
                            status = status,
                        )
                    )
                    _flowRunning.value = false
                    _flowPaused.value = false
                    _flowCurrentStep.value = -1
                }
            }
        }
    }

    private fun plannedShotsFor(step: FlowStep): Int = when (step) {
        is FlowStep.Intervalometer -> step.shotCount
        is FlowStep.Astro          -> step.shotCount
        is FlowStep.DarkFrame      -> step.shotCount
        is FlowStep.Ramp           -> step.steps
        is FlowStep.Pause          -> 0
    }

    /** Pick a representative label + exposure/interval triple for the run.
     *  For single-step flows we use that step; for multi-step we tag as Custom. */
    private fun summarizeSteps(steps: List<FlowStep>): Triple<String, Long, Long> {
        if (steps.size == 1) {
            return when (val s = steps[0]) {
                is FlowStep.Intervalometer -> Triple("INTERVALOMETER", s.exposureMs, s.intervalMs)
                is FlowStep.Astro -> Triple(
                    "ASTRO",
                    AppConfig.astroExposureMs(s.focalLength, s.cropFactor, s.ruleDivisor),
                    s.gapMs,
                )
                is FlowStep.DarkFrame -> Triple("DARK_FRAME", s.exposureMs, s.gapMs)
                is FlowStep.Ramp      -> Triple("RAMP", (s.startExposureMs + s.endExposureMs) / 2, s.intervalMs)
                is FlowStep.Pause     -> Triple("PAUSE", 0L, 0L)
            }
        }
        val firstNonPause = steps.firstOrNull { it !is FlowStep.Pause } ?: steps.first()
        val (_, exp, intv) = summarizeSteps(listOf(firstNonPause))
        return Triple("CUSTOM", exp, intv)
    }

    fun continueFlow() {
        _flowPaused.value = false
    }

    fun stopFlow() {
        // Hand the BLE stop off immediately (don't wait on cancellation) so
        // the firmware halts ASAP; then await the flow's own finally to settle
        // _flowRunning / _flowCurrentStep before we stomp _status.
        if (!_simulatorActive.value && _canonTransport.value == null) {
            bleController.sendCommand(CommandBuilder.stop())
        }
        viewModelScope.launch {
            _canonTransport.value?.stop()
            flowJob?.cancelAndJoin()
            flowJob = null
            _status.value = _status.value?.copy(state = DeviceState.IDLE, timeRemainingMs = 0L)
        }
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
                val canon = _canonTransport.value
                when {
                    canon != null -> {
                        // Timelapse wizard stores its pulse-length sentinel as
                        // exposureMs; the camera owns timing in that path. Any
                        // other exposureMs means a bulb-style run.
                        if (step.exposureMs == AppConfig.TIMELAPSE_PULSE_MS) {
                            com.ehrocha.pulsar.transport.runCanonTimelapse(
                                canon, step.shotCount, step.intervalMs, step.delayMs,
                                af = step.useAutofocus, status = _status,
                                awaitReady = { awaitCanonReady(canon) },
                            )
                        } else {
                            com.ehrocha.pulsar.transport.runCanonBulb(
                                canon, step.shotCount, step.exposureMs,
                                step.intervalMs, step.delayMs, af = step.useAutofocus,
                                status = _status,
                                awaitReady = { awaitCanonReady(canon) },
                            )
                        }
                    }
                    _simulatorActive.value -> simulateShots(
                        step.shotCount, step.exposureMs, step.intervalMs, step.delayMs,
                    )
                    else -> {
                        sendModeCommand(
                            CommandBuilder.setIntervalometer(
                                step.intervalMs, step.exposureMs, step.shotCount, step.delayMs,
                            )
                        )
                        waitForCompletion(step.shotCount)
                    }
                }
            }
            is FlowStep.Astro -> {
                val canon = _canonTransport.value
                val expMs = AppConfig.astroExposureMs(step.focalLength, step.cropFactor, step.ruleDivisor)
                when {
                    canon != null -> com.ehrocha.pulsar.transport.runCanonBulb(
                        canon, step.shotCount, expMs,
                        step.gapMs, step.delayMs, af = step.useAutofocus,
                        status = _status, awaitReady = { awaitCanonReady(canon) },
                    )
                    _simulatorActive.value -> simulateShots(step.shotCount, expMs, step.gapMs, step.delayMs)
                    else -> {
                        sendModeCommand(
                            CommandBuilder.setAstro(step.gapMs, expMs, step.shotCount, step.delayMs)
                        )
                        waitForCompletion(step.shotCount)
                    }
                }
            }
            is FlowStep.DarkFrame -> {
                val canon = _canonTransport.value
                when {
                    canon != null -> com.ehrocha.pulsar.transport.runCanonBulb(
                        canon, step.shotCount, step.exposureMs,
                        step.gapMs, 0L, af = step.useAutofocus,
                        status = _status, awaitReady = { awaitCanonReady(canon) },
                    )
                    _simulatorActive.value -> simulateShots(step.shotCount, step.exposureMs, step.gapMs, 0L)
                    else -> {
                        sendModeCommand(
                            CommandBuilder.setDarkFrame(
                                step.gapMs, step.exposureMs, step.shotCount, 0L,
                            )
                        )
                        waitForCompletion(step.shotCount)
                    }
                }
            }
            is FlowStep.Ramp -> {
                val canon = _canonTransport.value
                val rampSteps = step.steps.coerceAtLeast(2)
                if (canon != null) {
                    com.ehrocha.pulsar.transport.runCanonRamp(
                        canon, step, rampSteps, af = step.useAutofocus,
                        status = _status, awaitReady = { awaitCanonReady(canon) },
                    )
                } else {
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
    }

    /** Holds the runner until the active transport is no longer paused, then
     *  returns. For CCAPI this checks [_canonReconnecting]; for non-CCAPI
     *  transports (e.g. direct-BLE) the pause condition is transport-specific
     *  and not wired yet, so this is currently a no-op there. Throws if a
     *  CCAPI transport was dropped entirely (e.g. reconnect timed out) so the
     *  caller can bail cleanly instead of firing shots into the void. The
     *  flow's [DeviceState] is flipped to WAITING while paused so the
     *  RunningView shows the paused affordance rather than RUNNING. */
    private suspend fun awaitCanonReady(
        transport: com.ehrocha.pulsar.transport.CameraTransport,
    ) {
        // CCAPI is the only transport with a pause-on-reconnect concept today.
        // Generalise when CanonBleTransport lands (bond-loss reconnect).
        val ccapi = transport as? com.ehrocha.pulsar.transport.ccapi.CcapiTransport ?: return
        if (!_canonReconnecting.value && _canonTransport.value === ccapi) return
        val priorState = _status.value?.state
        try {
            while (true) {
                coroutineContext.ensureActive()
                // Bail if the transport was replaced or torn down while we waited.
                if (_canonTransport.value !== ccapi) {
                    throw IllegalStateException("Canon transport dropped during pause")
                }
                if (!_canonReconnecting.value) return
                // Reflect "paused, waiting on camera" in the dashboard.
                _status.value = _status.value?.copy(state = DeviceState.WAITING)
                delay(500)
            }
        } finally {
            // Restore the pre-pause state when we exit (success or throw)
            // so the next iteration writes the right RUNNING/WAITING values.
            if (priorState != null && _status.value?.state == DeviceState.WAITING) {
                _status.value = _status.value?.copy(state = priorState)
            }
        }
    }

    // The CCAPI / Canon-direct-BLE run loops live in
    // `transport/CanonRunner.kt` — extracted so they can be exercised in
    // unit tests without an Application context. The viewmodel wires the
    // status flow and `awaitCanonReady` pause hook in to the top-level
    // funcs at each call site.

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
        // Schema tag at the envelope level — bumped on breaking field renames
        // or removals; additive changes don't require a bump because import
        // already tolerates missing keys.
        json.put("schema", SETTINGS_EXPORT_SCHEMA)
        json.put("intv_interval_ms", _intervalMs.value)
        json.put("intv_exposure_ms", _exposureMs.value)
        json.put("intv_shot_count", _shotCount.value)
        json.put("intv_delay_ms", _delayMs.value)
        json.put("pin_shutter", _pinShutter.value)
        json.put("pin_focus", _pinFocus.value)
        // Custom flow steps
        json.put("flow_steps", org.json.JSONArray(_flowSteps.value.map { it.toJson() }))
        // Saved flows library
        json.put("saved_flows", org.json.JSONArray(_savedFlows.value.map { it.toJson() }))
        return json.toString(2)
    }

    /** Import settings from a JSON string. Throws [IllegalArgumentException]
     *  on unknown schema so a malformed or future-version file doesn't
     *  silently clobber the user's current settings — the SAF-import UI
     *  surfaces the message. Files without a schema tag are accepted as
     *  legacy (pre-v0.238) and read as v1. */
    fun importSettingsJson(json: String) {
        val obj = org.json.JSONObject(json)
        val schema = obj.optString("schema", SETTINGS_EXPORT_SCHEMA)
        if (schema != SETTINGS_EXPORT_SCHEMA && !schema.startsWith("pulsar-settings/")) {
            throw IllegalArgumentException("Unknown settings-file schema: $schema")
        }
        setIntervalMs(obj.optLong("intv_interval_ms", AppConfig.DEFAULT_INTERVAL_MS))
        setExposureMs(obj.optLong("intv_exposure_ms", AppConfig.DEFAULT_EXPOSURE_MS))
        setShotCount(obj.optInt("intv_shot_count", AppConfig.DEFAULT_SHOT_COUNT))
        setDelayMs(obj.optLong("intv_delay_ms", AppConfig.DEFAULT_DELAY_MS))
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
            TriggerMode.TIMELAPSE -> return    // app-side discriminator, runtime is INTERVALOMETER
        }
        bleController.sendCommand(packet)
    }

    fun start() {
        if (_canonTransport.value != null) {
            // Direct start() is the legacy single-mode path; Canon only runs
            // via the flow runner (Timelapse wizard → startFlow()).
            Log.w(TAG, "start() ignored on Canon transport — use Timelapse wizard")
            return
        }
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
        if (_canonTransport.value != null) return
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
        val canon = _canonTransport.value
        if (canon != null) {
            _status.value = _status.value?.copy(state = DeviceState.RUNNING)
            viewModelScope.launch { canon.startBulb(af = true) }
            return
        }
        if (_simulatorActive.value) {
            _status.value = _status.value?.copy(state = DeviceState.RUNNING)
            return
        }
        sendConfig()
        bleController.sendCommand(CommandBuilder.start())
    }

    /** Press & Hold: shutter close on up */
    fun shutterUp() {
        val canon = _canonTransport.value
        if (canon != null) {
            viewModelScope.launch { canon.stopBulb() }
            _status.value = _status.value?.copy(state = DeviceState.IDLE)
            return
        }
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

    private fun disconnectSimulator() {
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
