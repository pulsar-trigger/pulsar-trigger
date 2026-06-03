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
import com.ehrocha.pulsar.ptp.attemptPtpIpReconnect
import com.ehrocha.pulsar.ptp.attemptPtpReopen
import com.ehrocha.pulsar.ptp.awaitPtpIpReady
import com.ehrocha.pulsar.ptp.awaitPtpUsbReady
import com.ehrocha.pulsar.ptp.startPtpBatteryPolling
import com.ehrocha.pulsar.ptp.startPtpIpBatteryPolling
import com.ehrocha.pulsar.ptp.watchPtpIpWire
import com.ehrocha.pulsar.service.PulsarNotificationService
import com.ehrocha.pulsar.transport.ccapi.attemptCanonCcapiReconnect
import com.ehrocha.pulsar.transport.ccapi.awaitCcapiReady
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
        // CCAPI reconnect timing constants moved alongside the function
        // that uses them — see CcapiPulsarViewModelExt.kt.
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

    // ── Last successful connection (drives Scan-landing's reconnect CTA) ─
    private val lastConnectionPrefs = app.getSharedPreferences(
        com.ehrocha.pulsar.model.LastConnection.SHARED_PREFS_NAME,
        Context.MODE_PRIVATE,
    )
    private val _lastConnection = MutableStateFlow(
        com.ehrocha.pulsar.model.LastConnection.deserialise(
            lastConnectionPrefs.getString(com.ehrocha.pulsar.model.LastConnection.PREF_KEY, null)
        )
    )
    val lastConnection: StateFlow<com.ehrocha.pulsar.model.LastConnection?> = _lastConnection

    private fun recordLastConnection(entry: com.ehrocha.pulsar.model.LastConnection) {
        _lastConnection.value = entry
        lastConnectionPrefs.edit()
            .putString(com.ehrocha.pulsar.model.LastConnection.PREF_KEY, entry.serialise())
            .apply()
    }

    /** Clear the persisted last-connection — called from `forgetLastConnection`
     *  in the UI when the user wants to remove a stale entry (e.g. they sold
     *  the camera). */
    fun forgetLastConnection() {
        _lastConnection.value = null
        lastConnectionPrefs.edit()
            .remove(com.ehrocha.pulsar.model.LastConnection.PREF_KEY)
            .apply()
    }

    /** One-tap reconnect to whatever [lastConnection] points at. Dispatches
     *  to the per-transport `connectX` after reconstructing the right device
     *  handle from the persisted identifier. Whether the connect succeeds
     *  depends on the device being reachable right now — for BLE bodies
     *  that's "advertising or already bonded", for CCAPI "on the same
     *  network", for PTP "cable plugged in".
     *
     *  No-op if no entry is persisted. Errors surface through the relevant
     *  per-transport error flow (`canonCcapiError` / `ptpError` /
     *  `canonBleError`) the same way an explicit user tap would. */
    @SuppressLint("MissingPermission")
    fun reconnectLast() {
        val last = _lastConnection.value ?: return
        when (last.kind) {
            com.ehrocha.pulsar.transport.TransportKind.BLE_ESP -> {
                val adapter = getApplication<Application>()
                    .getSystemService(android.bluetooth.BluetoothManager::class.java)
                    ?.adapter ?: return
                val device = runCatching { adapter.getRemoteDevice(last.identifier) }.getOrNull()
                    ?: return
                connectTo(device)
            }
            com.ehrocha.pulsar.transport.TransportKind.CCAPI -> {
                // identifier = "udn|accessUrl|ipAddress|port|friendlyName"
                val parts = last.identifier.split('|')
                if (parts.size < 5) {
                    forgetLastConnection()
                    return
                }
                val port = parts[3].toIntOrNull() ?: return
                val camera = com.ehrocha.pulsar.transport.ccapi.CanonCamera(
                    udn = parts[0],
                    friendlyName = parts[4],
                    nickname = canonCcapiNicknames.value[parts[0]],
                    ipAddress = parts[2],
                    port = port,
                    accessUrl = parts[1],
                )
                connectCanonCcapi(camera)
            }
            com.ehrocha.pulsar.transport.TransportKind.PTP_USB -> {
                // identifier = "vendorId:productId"
                val (vid, pid) = last.identifier.split(':').mapNotNull { it.toIntOrNull() }
                    .takeIf { it.size == 2 } ?: return
                val device = ptpCameras.value
                    .firstOrNull { it.vendorId == vid && it.productId == pid }
                    ?: run {
                        _ptpError.value = "no_device"
                        return
                    }
                connectPtp(device)
            }
            com.ehrocha.pulsar.transport.TransportKind.CANON_BLE -> {
                val adapter = getApplication<Application>()
                    .getSystemService(android.bluetooth.BluetoothManager::class.java)
                    ?.adapter ?: return
                val device = runCatching { adapter.getRemoteDevice(last.identifier) }.getOrNull()
                    ?: return
                // The body may not be advertising the service UUID right now
                // (registered cameras often re-advertise directed at the bonded
                // phone) — use OS-managed autoConnect so the reconnect lands
                // when the camera becomes available, rather than failing fast.
                connectCanonBle(device, autoReconnect = true)
            }
            com.ehrocha.pulsar.transport.TransportKind.PTP_IP -> {
                // identifier = "name|host|port"; try to reconnect to the
                // recorded host directly (we can't browse mDNS instantly).
                val parts = last.identifier.split('|')
                if (parts.size < 3) {
                    forgetLastConnection()
                    return
                }
                val port = parts[2].toIntOrNull() ?: return
                val cam = com.ehrocha.pulsar.ptp.PtpIpCamera(
                    name = parts[0], host = parts[1], port = port,
                )
                connectPtpIp(cam)
            }
        }
    }

    // ── BLE (scan + connection) ─────────────────────────────────────────
    val bleController = com.ehrocha.pulsar.ble.BleController(app)
    private val bleManager get() = bleController.bleManager

    val scanning: StateFlow<Boolean> = bleController.scanning
    val devices: StateFlow<List<com.ehrocha.pulsar.ble.ScannedDevice>> = bleController.devices

    // ── CCAPI (Canon WiFi) discovery ─────────────────────────────────────
    // Runs alongside BLE scan; scan card shows both lists.
    private val ccapiDiscovery = com.ehrocha.pulsar.transport.ccapi.CcapiDiscovery(app)
    val canonCcapiCameras: StateFlow<List<com.ehrocha.pulsar.transport.ccapi.CanonCamera>> =
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
    internal val _canonCcapiTransport =
        MutableStateFlow<com.ehrocha.pulsar.transport.ccapi.CcapiTransport?>(null)
    val canonCcapiTransport: StateFlow<com.ehrocha.pulsar.transport.ccapi.CcapiTransport?> =
        _canonCcapiTransport
    private val _canonCcapiConnecting = MutableStateFlow(false)
    val canonCcapiConnecting: StateFlow<Boolean> = _canonCcapiConnecting
    internal val _canonCcapiError = MutableStateFlow<String?>(null)
    val canonCcapiError: StateFlow<String?> = _canonCcapiError

    /** When non-null, the UI should prompt for username + password — the
     *  previous connect attempt to this camera returned 401. Cleared on
     *  successful connect or cancel. */
    private val _canonCcapiAuthPrompt =
        MutableStateFlow<com.ehrocha.pulsar.transport.ccapi.CanonCamera?>(null)
    val canonCcapiAuthPrompt: StateFlow<com.ehrocha.pulsar.transport.ccapi.CanonCamera?> =
        _canonCcapiAuthPrompt

    /** True while the polling loop has lost contact with the camera and is
     *  trying to reach it again — the UI keeps the session alive (no return
     *  to the scan screen) and shows a reconnecting banner. */
    internal val _canonCcapiReconnecting = MutableStateFlow(false)
    val canonCcapiReconnecting: StateFlow<Boolean> = _canonCcapiReconnecting

    // ── USB PTP transport (Phase 1: Timelapse only) ──────────────────────
    // The phone drives a Canon (or other PTP-capable) body over USB-C using
    // Android's USB Host API. Useful for bodies CCAPI can't reach — e.g. the
    // EOS R, where Pulsar otherwise has no phone-side path. Mutually
    // exclusive with BLE / CCAPI / simulator; picking one disconnects the
    // others. Bulb support comes in Phase 2.
    private val ptpDiscovery = com.ehrocha.pulsar.ptp.PtpDiscovery(app).also { it.start() }
    val ptpCameras: StateFlow<List<android.hardware.usb.UsbDevice>> = ptpDiscovery.cameras

    internal val _ptpTransport =
        MutableStateFlow<com.ehrocha.pulsar.ptp.PtpTransport?>(null)
    val ptpTransport: StateFlow<com.ehrocha.pulsar.ptp.PtpTransport?> = _ptpTransport

    private val _ptpConnecting = MutableStateFlow(false)
    val ptpConnecting: StateFlow<Boolean> = _ptpConnecting
    internal val _ptpError = MutableStateFlow<String?>(null)
    val ptpError: StateFlow<String?> = _ptpError

    /** True while a USB camera that was connected has lost its cable and
     *  the auto-reconnect logic is waiting for it to reappear. The UI
     *  shows a banner like the CCAPI Wi-Fi reconnect indicator. Cleared
     *  by either a successful reconnect or an explicit user disconnect. */
    internal val _ptpReconnecting = MutableStateFlow(false)
    val ptpReconnecting: StateFlow<Boolean> = _ptpReconnecting

    /** Called by the scan-screen Snackbar after surfacing the error. */
    fun clearPtpError() { _ptpError.value = null }

    /** Vendor + product ID of the camera we were last *intentionally*
     *  connected to over PTP. Set on successful connect, preserved across
     *  cable-unplug, cleared only on explicit user disconnect or when the
     *  user switches to a different transport. Used to auto-reconnect when
     *  the same camera reappears on the bus. */
    @Volatile private var lastPtpAutoReconnect: Pair<Int, Int>? = null

    // ── PTP/IP (Canon Wi-Fi PTP-over-TCP) — Pulsar's 5th transport ─────
    // Discovers and drives Canon EOS bodies in "Remote Control (EOS
    // Utility)" Wi-Fi mode. Same op layer as USB PTP (shared PtpClient);
    // only the wire (PtpIpWire over TCP) differs. The EOS R lead since
    // CCAPI doesn't activate there and the body has no BLE shutter.
    // Phase 1 scope: discovery + handshake + connect. Shutter / live view
    // / reconnect hardening land in later phases.
    private val ptpIpDiscovery = com.ehrocha.pulsar.ptp.PtpIpDiscovery(app)
    val ptpIpCameras: StateFlow<List<com.ehrocha.pulsar.ptp.PtpIpCamera>> =
        ptpIpDiscovery.cameras

    internal val _ptpIpTransport =
        MutableStateFlow<com.ehrocha.pulsar.ptp.PtpIpTransport?>(null)
    val ptpIpTransport: StateFlow<com.ehrocha.pulsar.ptp.PtpIpTransport?> =
        _ptpIpTransport

    private val _ptpIpConnecting = MutableStateFlow(false)
    val ptpIpConnecting: StateFlow<Boolean> = _ptpIpConnecting

    /** True between starting the PTP/IP handshake and the camera's user
     *  confirmation. UI surfaces a "Confirm on camera" prompt. */
    private val _ptpIpAwaitingConfirm = MutableStateFlow(false)
    val ptpIpAwaitingConfirm: StateFlow<Boolean> = _ptpIpAwaitingConfirm

    internal val _ptpIpError = MutableStateFlow<String?>(null)
    val ptpIpError: StateFlow<String?> = _ptpIpError
    fun clearPtpIpError() { _ptpIpError.value = null }

    /** True while we're trying to recover a dropped PTP/IP wire. The UI
     *  surfaces a "Reconnecting…" state instead of bouncing the user back
     *  to scan. */
    internal val _ptpIpReconnecting = MutableStateFlow(false)
    val ptpIpReconnecting: StateFlow<Boolean> = _ptpIpReconnecting

    /** Last camera we connected to over PTP/IP. Cleared on a user-initiated
     *  disconnect; kept on a wire drop so the auto-reconnect job knows what
     *  to re-dial. */
    @Volatile internal var lastPtpIpCamera: com.ehrocha.pulsar.ptp.PtpIpCamera? = null

    fun startPtpIpScan() = ptpIpDiscovery.start()
    fun stopPtpIpScan() = ptpIpDiscovery.stop()

    private var ptpIpConnectJob: Job? = null
    internal var ptpIpReconnectJob: Job? = null

    /** Connect to a PTP/IP camera. Mutually exclusive with the other four
     *  transports — they're torn down first. The camera shows a "Connect
     *  this device?" prompt the first time it sees our client GUID. */
    fun connectPtpIp(camera: com.ehrocha.pulsar.ptp.PtpIpCamera) {
        ptpIpConnectJob?.cancel()
        ptpIpConnectJob = viewModelScope.launch {
            _ptpIpError.value = null
            _ptpIpConnecting.value = true
            try {
                // Mutual exclusion: any other transport is torn down first.
                if (bleController.connected.value) bleController.disconnect()
                if (_canonCcapiTransport.value != null) disconnectCanonCcapi()
                disconnectCanonBle()
                if (_ptpTransport.value != null) disconnectPtp()
                if (_simulatorActive.value) disconnectSimulator()
                stopPtpIpScan()

                val result = com.ehrocha.pulsar.ptp.PtpIpTransport.openOn(
                    camera = camera,
                    clientName = "Pulsar",
                    clientGuid = ptpIpClientGuid(),
                    onAwaitConfirm = { _ptpIpAwaitingConfirm.value = true },
                )
                _ptpIpAwaitingConfirm.value = false
                val transport = when (result) {
                    is com.ehrocha.pulsar.ptp.PtpIpOpenResult.Ok -> result.transport
                    is com.ehrocha.pulsar.ptp.PtpIpOpenResult.Rejected -> {
                        _ptpIpError.value = "rejected"
                        return@launch
                    }
                    is com.ehrocha.pulsar.ptp.PtpIpOpenResult.Failed -> {
                        _ptpIpError.value = "connect_failed"
                        return@launch
                    }
                }
                if (!transport.connect()) {
                    _ptpIpError.value = "session_failed"
                    transport.release()
                    return@launch
                }
                _ptpIpTransport.value = transport
                _deviceName.value = transport.label.value
                _connected.value = true
                lastPtpIpCamera = camera
                _ptpIpReconnecting.value = false
                recordLastConnection(com.ehrocha.pulsar.model.LastConnection(
                    kind = com.ehrocha.pulsar.transport.TransportKind.PTP_IP,
                    label = transport.label.value,
                    identifier = "${camera.name}|${camera.host}|${camera.port}",
                ))
                _status.value = StatusFrame(
                    state = DeviceState.IDLE,
                    mode = TriggerMode.TIMELAPSE.id,
                    shotsTaken = 0,
                    timeRemainingMs = 0L,
                    batteryPct = 0,
                    errorCode = 0,
                    fwVersion = "",
                )
                if (transport.supportsBatteryReadout) startPtpIpBatteryPolling(transport)
                watchPtpIpWire(transport)
            } finally {
                _ptpIpConnecting.value = false
                _ptpIpAwaitingConfirm.value = false
            }
        }
    }

    // watchPtpIpWire + attemptPtpIpReconnect live in
    // ptp/PtpIpPulsarViewModelExt.kt as extensions on PulsarViewModel — see
    // imports above. Per-transport reconnect concern lives next to the
    // PtpIpTransport class it operates on.

    /** Periodic PTP/IP battery poll — same 30 s cadence as USB PTP since
     *  neither path pushes battery events. Cancelled in [disconnectPtpIp]. */
    internal var ptpIpPollJob: Job? = null
    // startPtpIpBatteryPolling lives in ptp/PtpIpPulsarViewModelExt.kt — a
    // PulsarViewModel extension that reads internal poll-job state on this
    // class. Keeps the per-transport leaf concerns out of the main file.

    internal fun disconnectPtpIp() {
        ptpIpConnectJob?.cancel()
        ptpIpConnectJob = null
        ptpIpReconnectJob?.cancel()
        ptpIpReconnectJob = null
        ptpIpPollJob?.cancel()
        ptpIpPollJob = null
        _ptpIpReconnecting.value = false
        lastPtpIpCamera = null
        val transport = _ptpIpTransport.value
        if (transport != null) {
            viewModelScope.launch { transport.release() }
            _ptpIpTransport.value = null
        }
        if (!bleController.connected.value && !_simulatorActive.value &&
            _canonCcapiTransport.value == null && _ptpTransport.value == null &&
            _canonBleTransport.value == null) {
            _connected.value = false
            _status.value = null
        }
    }

    /** Persistent client GUID for PTP/IP — the camera remembers it across
     *  reconnects so it only prompts once per device. Stored in plain
     *  SharedPrefs (not a secret, just an identifier). */
    private fun ptpIpClientGuid(): java.util.UUID {
        val prefs = getApplication<Application>().getSharedPreferences(
            "pulsar_ptpip", android.content.Context.MODE_PRIVATE,
        )
        prefs.getString("client_guid", null)?.let {
            runCatching { return java.util.UUID.fromString(it) }
        }
        val fresh = java.util.UUID.randomUUID()
        prefs.edit().putString("client_guid", fresh.toString()).apply()
        return fresh
    }

    // ── Canon BLE direct transport (Phase 1: connect + pair + single-shot
    //    + bulb via press/hold). The phone speaks Canon's BR-E1 BLE
    //    protocol directly to the camera — no Pulsar ESP32 hardware, no
    //    Wi-Fi, no cable. Capability is bulb-class: single shot, bulb,
    //    intervalometer, astro, dark frame, ramp. No live view, no lens
    //    info, no battery — those aren't in the BR-E1 protocol.
    //    See `docs/canon-ble.md` for the wire format.
    private val canonBleDiscovery =
        com.ehrocha.pulsar.canonble.CanonBleDiscovery(app)
    val canonBleCameras: StateFlow<List<android.bluetooth.BluetoothDevice>> =
        canonBleDiscovery.cameras

    internal val _canonBleTransport =
        MutableStateFlow<com.ehrocha.pulsar.canonble.CanonBleTransport?>(null)
    val canonBleTransport: StateFlow<com.ehrocha.pulsar.canonble.CanonBleTransport?> =
        _canonBleTransport

    private val _canonBleConnecting = MutableStateFlow(false)
    val canonBleConnecting: StateFlow<Boolean> = _canonBleConnecting

    /** True while the smartphone-mode handshake is waiting for the user to
     *  confirm the pairing on the camera body. The setup screen surfaces a
     *  "Confirm the pairing on your camera" prompt off this. */
    private val _canonBleAwaitingConfirm = MutableStateFlow(false)
    val canonBleAwaitingConfirm: StateFlow<Boolean> = _canonBleAwaitingConfirm

    private val _canonBleError = MutableStateFlow<String?>(null)
    val canonBleError: StateFlow<String?> = _canonBleError

    fun clearCanonBleError() { _canonBleError.value = null }

    /** Build a shareable diagnostics report — app/device header, current
     *  transport state, and the captured Canon BLE wire log. Surfaced via
     *  Tools → Collect diagnostics so debugging needs no `adb logcat`. */
    fun canonDiagnosticsText(): String {
        val transport = when {
            _canonBleTransport.value != null -> "Canon BLE"
            _canonCcapiTransport.value != null -> "Canon CCAPI"
            _ptpTransport.value != null -> "USB PTP"
            _ptpIpTransport.value != null -> "Wi-Fi PTP/IP"
            bleController.connected.value -> "Pulsar BLE"
            _simulatorActive.value -> "Simulator"
            else -> "none"
        }
        val ts = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US)
            .format(java.util.Date())
        val log = com.ehrocha.pulsar.canonble.CanonBleLog.dump()
        val crash = com.ehrocha.pulsar.canonble.CrashPersister.consume(getApplication())
        return buildString {
            appendLine("Pulsar diagnostics")
            appendLine("app: ${com.ehrocha.pulsar.BuildConfig.VERSION_NAME} " +
                "(${com.ehrocha.pulsar.BuildConfig.VERSION_CODE})")
            appendLine("device: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL} / " +
                "Android ${android.os.Build.VERSION.RELEASE} (SDK ${android.os.Build.VERSION.SDK_INT})")
            appendLine("time: $ts")
            appendLine("active transport: $transport")
            appendLine("canonBle: connecting=${_canonBleConnecting.value} " +
                "awaitingConfirm=${_canonBleAwaitingConfirm.value} " +
                "reconnecting=${_canonBleReconnecting.value} lastError=${_canonBleError.value}")
            if (crash != null) {
                appendLine()
                appendLine(crash.trim())
            }
            appendLine()
            appendLine("── Transport wire log ──")
            append(if (log.isBlank()) "(empty — no transport activity captured yet)" else log)
        }
    }

    /** True while the previously-connected Canon BLE camera has dropped
     *  the link (powered off, out of range, app backgrounded too long)
     *  and Pulsar is waiting for it to advertise again. The UI shows a
     *  reconnect banner — same pattern as PTP cable replug. */
    private val _canonBleReconnecting = MutableStateFlow(false)
    val canonBleReconnecting: StateFlow<Boolean> = _canonBleReconnecting

    /** Last Canon BLE [android.bluetooth.BluetoothDevice] we connected to, kept
     *  so a drop can fire an OS-managed autoConnect reconnect without waiting
     *  for the body to reappear in a service-UUID scan. */
    @Volatile private var lastCanonBleDevice: android.bluetooth.BluetoothDevice? = null

    /** MAC address of the last Canon BLE camera we successfully connected
     *  to. Persisted in plain SharedPrefs (the BLE bond itself is in the
     *  OS keystore and survives independently). Used to auto-reconnect
     *  on the same body's re-advertise, including across app restarts.
     *
     *  Cached in [_lastCanonBleAddressCache] so the auto-reconnect
     *  collector — which is invoked on every BLE advertisement, multiple
     *  times per second — doesn't hit disk on every emission. The setter
     *  writes through to both the in-memory cache and SharedPrefs. */
    private val canonBlePrefs = app.getSharedPreferences("pulsar_canon_ble", Context.MODE_PRIVATE)
    @Volatile private var _lastCanonBleAddressCache: String? =
        canonBlePrefs.getString("last_address", null)
    private var lastCanonBleAddress: String?
        get() = _lastCanonBleAddressCache
        set(value) {
            _lastCanonBleAddressCache = value
            canonBlePrefs.edit().apply {
                if (value == null) remove("last_address") else putString("last_address", value)
            }.apply()
        }

    init {
        // PTP discovery collector. Two flows watched on the discovery channel:
        //  (1) currently-connected camera vanishes → tear down the transport
        //      cleanly but remember the device so we can auto-reconnect.
        //  (2) a previously-connected camera reappears while we're idle →
        //      auto-reconnect so the user doesn't have to tap again.
        // Parallel collector for Canon BLE lives in the init {} block near
        // the Canon BLE section below.
        viewModelScope.launch {
            ptpDiscovery.cameras.collect { attached ->
                val active = _ptpTransport.value
                if (active != null &&
                    attached.none { it.deviceName == active.device.deviceName }) {
                    if (_flowRunning.value) {
                        // Mid-flow cable bump: don't tear down the transport.
                        // Mark reconnecting so awaitCanonReady pauses the
                        // runner, and wait for the OS to reattach the same
                        // (vid, pid) → call existing.reopen() in place.
                        Log.i(TAG, "USB camera unplugged mid-flow — soft-pausing PTP for reopen")
                        _ptpReconnecting.value = true
                        lastPtpAutoReconnect = active.device.vendorId to active.device.productId
                    } else {
                        Log.i(TAG, "USB camera unplugged — disconnecting PTP (auto-reconnect armed)")
                        _ptpReconnecting.value = true
                        disconnectPtp(clearAutoReconnect = false)
                    }
                    return@collect
                }
                val want = lastPtpAutoReconnect
                if (active != null && _ptpReconnecting.value &&
                    !active.connected.value && want != null) {
                    // Soft-pause path: a held-but-dead transport is waiting
                    // for the wire to come back. Find the freshly-enumerated
                    // device matching the original vid/pid and swap in place.
                    val match = attached.firstOrNull {
                        it.vendorId == want.first && it.productId == want.second
                    }
                    if (match != null) {
                        Log.i(TAG, "USB camera reappeared mid-flow — reopen()-ing in place")
                        viewModelScope.launch { attemptPtpReopen(active, match) }
                    }
                } else if (active == null && want != null && idleAcrossOtherTransports()) {
                    val match = attached.firstOrNull {
                        it.vendorId == want.first && it.productId == want.second
                    }
                    if (match != null) {
                        Log.i(TAG, "USB camera reappeared — auto-reconnecting PTP")
                        connectPtp(match, auto = true)
                    }
                }
            }
        }
    }

    // attemptPtpReopen lives in ptp/PtpPulsarViewModelExt.kt — extension on
    // PulsarViewModel. Keeps the per-transport reopen logic next to the
    // PtpTransport class it operates on.

    /** True iff no other transport is currently in use — gates auto-reconnect
     *  so we don't snatch the user away from a deliberate BLE / CCAPI /
     *  simulator session. */
    private fun idleAcrossOtherTransports(): Boolean =
        _canonCcapiTransport.value == null &&
            _canonBleTransport.value == null &&
            !bleController.connected.value &&
            !_simulatorActive.value

    /** Per-UDN digest credentials. Each entry lets the next connect to the
     *  same camera skip the auth prompt entirely. */
    /** Encrypted-at-rest prefs file (AES-256, key in Android Keystore) for
     *  CCAPI digest credentials. Migrated from a plain SharedPreferences
     *  file in v0.237. Falls back to the plain file if EncryptedSharedPrefs
     *  fails to initialise (extremely rare; would indicate a Keystore fault).
     *  Old plain file is read once on first launch to migrate any saved
     *  creds, then cleared. */
    private val canonCcapiCredsPrefs = buildCanonCredsPrefs(app)

    /** Per-UDN user-set nicknames for Canon cameras. Shown in the scan card
     *  and as the connected device label in place of the body's own name. */
    private val canonCcapiNicknamesPrefs =
        app.getSharedPreferences("pulsar_canon_nicks", Context.MODE_PRIVATE)
    private val _canonCcapiNicknames = MutableStateFlow(loadAllCanonNicknames())
    val canonCcapiNicknames: StateFlow<Map<String, String>> = _canonCcapiNicknames

    private fun loadAllCanonNicknames(): Map<String, String> =
        canonCcapiNicknamesPrefs.all
            .mapNotNull { (k, v) -> if (v is String && k.isNotEmpty()) k to v else null }
            .toMap()

    /** Set or clear (empty string) the user-facing nickname for a Canon camera.
     *  The new value flows through [canonCcapiNicknames]; if this camera is
     *  currently active, its [deviceName] is updated immediately. */
    fun setCanonCcapiNickname(udn: String, nickname: String) {
        val trimmed = nickname.trim()
        val edit = canonCcapiNicknamesPrefs.edit()
        if (trimmed.isEmpty()) edit.remove(udn) else edit.putString(udn, trimmed)
        edit.apply()
        _canonCcapiNicknames.value = loadAllCanonNicknames()
        val active = _canonCcapiTransport.value
        if (active != null && active.camera.udn == udn) {
            _deviceName.value = effectiveCanonName(active.camera)
        }
    }

    /** Display name for a Canon camera. Precedence: user nickname > body
     *  nickname > body friendly name. */
    fun effectiveCanonName(camera: com.ehrocha.pulsar.transport.ccapi.CanonCamera): String =
        _canonCcapiNicknames.value[camera.udn]?.takeIf { it.isNotEmpty() }
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
    internal val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected

    internal val _status = MutableStateFlow<StatusFrame?>(null)
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
    internal val _simulatorActive = MutableStateFlow(false)
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

    internal val _deviceName = MutableStateFlow("Pulsar")
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

    /** Set when a camera transport's link drops *mid-session*. The phone-driven
     *  run loop (CCAPI / PTP / Canon BLE) can't continue and can't tell the
     *  camera to stop, so the flow is aborted and the UI warns the user (a bulb
     *  exposure open at the drop may still be running on the body). */
    private val _sessionInterrupted = MutableStateFlow(false)
    val sessionInterrupted: StateFlow<Boolean> = _sessionInterrupted
    fun clearSessionInterrupted() { _sessionInterrupted.value = false }

    /** A camera transport dropped while a flow was running: abort the loop
     *  (it would otherwise keep advancing through delays while silently firing
     *  nothing) and flag the interruption for the UI warning. */
    internal fun abortFlowOnTransportDrop() {
        if (!_flowRunning.value) return
        Log.i(TAG, "camera transport dropped mid-session — aborting flow")
        flowJob?.cancel()
        flowJob = null
        _flowRunning.value = false
        _flowPaused.value = false
        _flowCurrentStep.value = -1
        _sessionInterrupted.value = true
        _status.value = _status.value?.copy(state = DeviceState.IDLE, timeRemainingMs = 0L)
    }

    /** Single derived run-state — UI consumers should prefer this over the
     *  individual `status` / `flowRunning` / `flowPaused` / `flowCurrentStep`
     *  flows. See [RunState]. */
    val runState: StateFlow<RunState> = combine(
        _status, _flowRunning, _flowPaused, _flowCurrentStep, _flowSteps,
    ) { status, running, paused, currentStep, steps ->
        RunState.from(status, running, paused, currentStep, steps)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, RunState.Idle)

    /** The FlowStep currently executing, or null when no flow is running.
     *  Exposed so the RunningView can render the step's settings (mode,
     *  exposure, interval, focal length) without each wizard needing to
     *  pass them in by hand. */
    val currentFlowStep: StateFlow<FlowStep?> = combine(
        _flowSteps, _flowCurrentStep, _flowRunning,
    ) { steps, idx, running ->
        if (running && idx in steps.indices) steps[idx] else null
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    init {
        // Forward BLE controller state into the viewmodel's writable flows.
        // [_status] is multiplexed — BLE updates land here, and the simulator
        // also writes to it directly while it's running.
        viewModelScope.launch {
            bleController.connected.collect {
                // Phone-driven transports (CCAPI / PTP / Canon BLE) take
                // priority — don't let an ESP32 BLE state change kick the
                // user out of an active phone-driven camera session.
                if (_canonCcapiTransport.value != null ||
                    _ptpTransport.value != null ||
                    _canonBleTransport.value != null) return@collect
                _connected.value = it
                if (it) {
                    sendAutoOff()
                    // Device info is requested by BleManager.initialize()
                    // after notifications are active — no need to request here.
                    val mac = pendingBleConnectMac
                    if (mac != null) {
                        recordLastConnection(com.ehrocha.pulsar.model.LastConnection(
                            kind = com.ehrocha.pulsar.transport.TransportKind.BLE_ESP,
                            label = _deviceName.value,
                            identifier = mac,
                        ))
                        pendingBleConnectMac = null
                    }
                }
            }
        }
        viewModelScope.launch {
            bleController.status.collect {
                if (!_simulatorActive.value && _canonCcapiTransport.value == null &&
                    _ptpTransport.value == null && _canonBleTransport.value == null) {
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
    /** Remembered while a BLE connect is in flight so the
     *  [bleController.connected] collector knows what MAC to attribute
     *  the connection to when recording [lastConnection]. */
    @Volatile private var pendingBleConnectMac: String? = null

    fun connectTo(device: BluetoothDevice) {
        // Hang up any active session so transports stay single-valued.
        // disconnectCanonBle runs unconditionally — see its KDoc; a Canon
        // BLE link can be torn down with the transport already null but
        // a reconnect job still in flight (post-drop state).
        if (_canonCcapiTransport.value != null) disconnectCanonCcapi()
        disconnectCanonBle()
        if (_ptpTransport.value != null) disconnectPtp()
        if (_ptpIpTransport.value != null) disconnectPtpIp()
        if (_simulatorActive.value) disconnectSimulator()
        _deviceName.value = device.name ?: "Pulsar"
        pendingBleConnectMac = device.address
        bleController.connect(device)
    }

    /** Open an HTTP session to a Canon CCAPI camera. Pins the API version and
     *  flips [connected] true on success. Mutually exclusive with BLE — any
     *  active BLE session is dropped first. Phase 2: Timelapse only. */
    /** Tracks the in-flight connectCanonCcapi coroutine so disconnectCanonCcapi (or
     *  a second connect attempt) can cancel it cleanly. Without this, a
     *  user tapping connect, then disconnect, then connect again could
     *  end up with two coroutines racing on the same `_canonCcapiConnecting`
     *  flag — the UI is already gated, but defending the viewmodel itself
     *  avoids subtle ordering bugs. Same pattern as [flowJob]. */
    private var canonCcapiConnectJob: Job? = null

    fun connectCanonCcapi(
        camera: com.ehrocha.pulsar.transport.ccapi.CanonCamera,
        credentials: com.ehrocha.pulsar.transport.ccapi.CcapiClient.Credentials? = null,
    ) {
        canonCcapiConnectJob?.cancel()
        canonCcapiConnectJob = viewModelScope.launch {
            _canonCcapiError.value = null
            _canonCcapiConnecting.value = true
            // Hang up any existing session so the UI's notion of "what's
            // connected" stays single-valued.
            if (bleController.connected.value) bleController.disconnect()
            if (_ptpTransport.value != null) disconnectPtp()
            if (_ptpIpTransport.value != null) disconnectPtpIp()
            disconnectCanonBle()
            if (_simulatorActive.value) disconnectSimulator()
            stopScan()

            // Reuse saved digest creds for this camera if the caller didn't
            // provide explicit ones (e.g. on app restart, user picks the same
            // camera — we shouldn't prompt again).
            val effectiveCreds = credentials ?: loadCanonCreds(camera.udn)
            val transport = com.ehrocha.pulsar.transport.ccapi.CcapiTransport(camera, effectiveCreds)
            val result = transport.connect()
            _canonCcapiConnecting.value = false
            when (result) {
                is com.ehrocha.pulsar.transport.ccapi.CcapiClient.Result.Ok -> {
                    _canonCcapiTransport.value = transport
                    _canonCcapiAuthPrompt.value = null
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
                    // Record for the Scan-landing reconnect CTA. We pack
                    // enough to rebuild a CanonCamera without re-running
                    // SSDP — UDN as primary key plus the connection-time
                    // accessUrl / IP / port / friendly name.
                    recordLastConnection(com.ehrocha.pulsar.model.LastConnection(
                        kind = com.ehrocha.pulsar.transport.TransportKind.CCAPI,
                        label = effectiveCanonName(camera),
                        identifier = listOf(
                            camera.udn, camera.accessUrl, camera.ipAddress,
                            camera.port.toString(), camera.friendlyName,
                        ).joinToString("|"),
                    ))
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
                    if (effectiveCreds != null) clearCanonCcapiCreds(camera.udn)
                    _canonCcapiAuthPrompt.value = camera
                    _canonCcapiError.value = "auth_required"
                }
                is com.ehrocha.pulsar.transport.ccapi.CcapiClient.Result.Http ->
                    _canonCcapiError.value = "http_${result.code}"
                is com.ehrocha.pulsar.transport.ccapi.CcapiClient.Result.Network ->
                    _canonCcapiError.value = "network"
            }
        }
    }

    /** Called from the credentials dialog. Cleans the prior error state then
     *  re-runs [connectCanonCcapi] with the supplied digest credentials. */
    fun submitCanonCredentials(
        camera: com.ehrocha.pulsar.transport.ccapi.CanonCamera,
        username: String,
        password: String,
    ) {
        _canonCcapiError.value = null
        connectCanonCcapi(
            camera,
            com.ehrocha.pulsar.transport.ccapi.CcapiClient.Credentials(username, password),
        )
    }

    fun cancelCanonCcapiAuth() {
        _canonCcapiAuthPrompt.value = null
        _canonCcapiError.value = null
    }

    fun clearCanonCcapiError() { _canonCcapiError.value = null }

    private val _canonCcapiManualAdding = MutableStateFlow(false)
    val canonCcapiManualAdding: StateFlow<Boolean> = _canonCcapiManualAdding
    private val _canonCcapiManualError = MutableStateFlow<String?>(null)
    val canonCcapiManualError: StateFlow<String?> = _canonCcapiManualError

    /** Manually probe `http://<host>[:<port>]/ccapi` and add the camera to
     *  the scan list if it responds. Bypasses SSDP and the UPnP device
     *  descriptor entirely — many Canon bodies (notably the EOS RP) don't
     *  serve `/upnp/CameraDevDesc.xml`, but every CCAPI-active body answers
     *  `GET /ccapi` directly. Metadata (model + serial → stable UDN) comes
     *  from `/deviceinformation` once the probe succeeds. */
    fun addCanonCcapiByHost(rawInput: String, onResult: (Boolean) -> Unit) {
        val trimmed = rawInput.trim()
            .removePrefix("http://").removePrefix("https://")
            .substringBefore('/')
        if (trimmed.isEmpty()) {
            _canonCcapiManualError.value = "invalid"
            onResult(false)
            return
        }
        val (host, explicitPort) = if (':' in trimmed) {
            val (h, p) = trimmed.split(':', limit = 2)
            h to p.toIntOrNull()
        } else trimmed to null
        val portsToTry = explicitPort?.let { listOf(it) } ?: listOf(8080, 80, 8612)

        viewModelScope.launch {
            _canonCcapiManualError.value = null
            _canonCcapiManualAdding.value = true
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
                _canonCcapiManualError.value = "not_found"
                onResult(false)
            } finally {
                _canonCcapiManualAdding.value = false
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

    fun clearCanonCcapiManualError() { _canonCcapiManualError.value = null }

    /** Fire-and-forget stop of any running Canon live-view session. Safe to
     *  call from non-suspending contexts (e.g. `DisposableEffect.onDispose`)
     *  — runs in the viewmodel scope. */
    fun stopCanonLiveView() {
        val transport = _canonCcapiTransport.value ?: return
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
        val user = canonCcapiCredsPrefs.getString("u:$udn", null) ?: return null
        val pass = canonCcapiCredsPrefs.getString("p:$udn", null) ?: return null
        return com.ehrocha.pulsar.transport.ccapi.CcapiClient.Credentials(user, pass)
    }

    private fun saveCanonCreds(
        udn: String,
        creds: com.ehrocha.pulsar.transport.ccapi.CcapiClient.Credentials,
    ) {
        canonCcapiCredsPrefs.edit()
            .putString("u:$udn", creds.username)
            .putString("p:$udn", creds.password)
            .apply()
    }

    private fun clearCanonCcapiCreds(udn: String) {
        canonCcapiCredsPrefs.edit().remove("u:$udn").remove("p:$udn").apply()
    }

    internal var canonCcapiPollJob: Job? = null

    /** Long-poll `/event/polling` for live battery + shot count. On a streak of
     *  failures the loop enters reconnect mode and re-probes `/ccapi` for up
     *  to 2 minutes (see [com.ehrocha.pulsar.transport.ccapi.attemptCanonCcapiReconnect])
     *  — covers the common case of the camera briefly napping or the phone
     *  roaming. Persistent failure drops the session for real. The poll is
     *  independent of the run loop — it runs whether or not a flow is active. */
    private fun startCanonPolling(transport: com.ehrocha.pulsar.transport.ccapi.CcapiTransport) {
        canonCcapiPollJob?.cancel()
        canonCcapiPollJob = viewModelScope.launch {
            var consecutiveFails = 0
            // Tracks shot count via `addedcontents` so the camera's own
            // counter — including any shots fired with the body's hardware
            // button — feeds back into the UI.
            var observedShots = 0
            while (currentCoroutineContext().isActive &&
                   _canonCcapiTransport.value === transport) {
                when (val r = transport.pollEvents()) {
                    is com.ehrocha.pulsar.transport.ccapi.CcapiClient.Result.Ok -> {
                        // Healthy poll: clear any prior failure / reconnect state.
                        if (_canonCcapiReconnecting.value) _canonCcapiReconnecting.value = false
                        consecutiveFails = 0
                        observedShots = applyCanonPollUpdate(r.value, observedShots)
                    }
                    else -> {
                        consecutiveFails += 1
                        if (consecutiveFails >= MAX_CANON_POLL_FAILS) {
                            Log.w(TAG, "Canon polling failed $consecutiveFails× — entering reconnect")
                            val recovered = attemptCanonCcapiReconnect(transport)
                            if (recovered) {
                                consecutiveFails = 0
                                continue
                            }
                            // Soft recovery gave up — the session is dead.
                            abortFlowOnTransportDrop()
                            _canonCcapiError.value = "dropped"
                            disconnectCanonCcapi()
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

    // attemptCanonCcapiReconnect lives in transport/ccapi/CcapiPulsarViewModelExt.kt
    // as an extension on PulsarViewModel — see imports.

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

    internal fun disconnectCanonCcapi() {
        val transport = _canonCcapiTransport.value ?: return
        canonCcapiConnectJob?.cancel()
        canonCcapiConnectJob = null
        canonCcapiPollJob?.cancel()
        canonCcapiPollJob = null
        _canonCcapiReconnecting.value = false
        viewModelScope.launch { transport.release() }
        _canonCcapiTransport.value = null
        if (!bleController.connected.value && !_simulatorActive.value &&
            _ptpTransport.value == null) {
            _connected.value = false
            _status.value = null
        }
    }

    /** Connect to a USB-attached PTP camera. Requests USB permission if
     *  needed, opens the PTP interface, calls `OpenSession`. Mutually
     *  exclusive with BLE / CCAPI — both are dropped first. Phase 1 only
     *  supports Timelapse-mode runs (camera owns exposure). */
    /** Connect to a USB-attached camera over PTP. [auto] is true when the
     *  call originates from the auto-reconnect collector (cable replug);
     *  false when the user explicitly tapped a USB camera card. The
     *  distinction matters for simulator interplay:
     *   - Auto-reconnect must NOT override an active simulator session
     *     (the user picked simulator deliberately).
     *   - An explicit user tap on a USB card SHOULD override the
     *     simulator (same as picking any other transport). */
    /** In-flight connectPtp coroutine; cancelled on disconnectPtp / on a
     *  fresh connectPtp call to avoid concurrent USB permission flows. */
    private var ptpConnectJob: Job? = null

    fun connectPtp(device: android.hardware.usb.UsbDevice, auto: Boolean = false) {
        ptpConnectJob?.cancel()
        ptpConnectJob = viewModelScope.launch {
            if (auto && _simulatorActive.value) {
                Log.i(TAG, "connectPtp(auto): simulator active, skipping auto-reconnect")
                return@launch
            }
            _ptpError.value = null
            _ptpConnecting.value = true
            try {
                // Hang up any existing transport so the UI's notion of
                // "what's connected" stays single-valued.
                if (bleController.connected.value) bleController.disconnect()
                if (_canonCcapiTransport.value != null) disconnectCanonCcapi()
                if (_ptpIpTransport.value != null) disconnectPtpIp()
                disconnectCanonBle()
                if (_simulatorActive.value) disconnectSimulator()
                stopScan()

                val appCtx = getApplication<Application>()
                val granted = com.ehrocha.pulsar.ptp.requestUsbPermission(
                    appCtx,
                    appCtx.getSystemService(Context.USB_SERVICE) as android.hardware.usb.UsbManager,
                    device,
                )
                if (!granted) {
                    _ptpError.value = "permission_denied"
                    return@launch
                }
                val transport = com.ehrocha.pulsar.ptp.PtpTransport.openOn(appCtx, device)
                if (transport == null) {
                    _ptpError.value = "open_failed"
                    return@launch
                }
                val ok = transport.connect()
                if (!ok) {
                    transport.release()
                    _ptpError.value = "session_failed"
                    return@launch
                }
                _ptpTransport.value = transport
                lastPtpAutoReconnect = device.vendorId to device.productId
                _ptpReconnecting.value = false
                _deviceName.value = transport.label.value
                _connected.value = true
                recordLastConnection(com.ehrocha.pulsar.model.LastConnection(
                    kind = com.ehrocha.pulsar.transport.TransportKind.PTP_USB,
                    label = transport.label.value,
                    identifier = "${device.vendorId}:${device.productId}",
                ))
                _status.value = StatusFrame(
                    state = DeviceState.IDLE,
                    mode = TriggerMode.TIMELAPSE.id,
                    shotsTaken = 0,
                    timeRemainingMs = 0L,
                    batteryPct = 0,
                    errorCode = 0,
                    fwVersion = "",
                )
                // Seed the battery chip immediately + poll periodically.
                if (transport.supportsBatteryReadout) startPtpBatteryPolling(transport)
            } finally {
                _ptpConnecting.value = false
            }
        }
    }

    /** Job handle for the USB PTP battery poll loop — implementation lives
     *  in [com.ehrocha.pulsar.ptp.startPtpBatteryPolling]. Cancelled in
     *  [disconnectPtp]. */
    internal var ptpPollJob: Job? = null

    // ── Canon BLE direct: connect / disconnect / auto-reconnect ────────

    private var canonBleConnectJob: Job? = null

    init {
        // Canon BLE discovery collector. Watches the Canon BLE service-scan
        // for our last-paired body reappearing while we're idle. Mirrors
        // the PTP cable-replug pattern in the earlier init {} block:
        // spontaneous link drop arms `_canonBleReconnecting`, discovery
        // fires when the body advertises again, this collector reconnects
        // automatically.
        viewModelScope.launch {
            canonBleDiscovery.cameras.collect { cameras ->
                val want = lastCanonBleAddress ?: return@collect
                if (!_canonBleReconnecting.value) return@collect
                if (_canonBleTransport.value != null) return@collect
                // Don't cancel an OS-managed autoConnect that's already
                // in-flight (started by onCanonBleLinkDropped) — restarting
                // with autoConnect=false would churn the connect attempt.
                if (_canonBleConnecting.value) return@collect
                if (!idleAcrossOtherTransports()) return@collect
                val match = cameras.firstOrNull { it.address == want } ?: return@collect
                Log.i(TAG, "Canon BLE re-advertise from $want — auto-reconnecting")
                connectCanonBle(match, auto = true)
            }
        }
    }

    /** Begin / refresh the Canon BLE service scan. Called when ScanScreen
     *  becomes visible so the "Canon BLE remotes" section populates
     *  alongside Pulsar ESP32 + Canon Wi-Fi + USB cameras. */
    fun startCanonBleScan() = canonBleDiscovery.start()

    /** Stop the Canon BLE scan. Called when ScanScreen exits or when a
     *  connect is in-flight. */
    fun stopCanonBleScan() = canonBleDiscovery.stop()

    /** Connect to a Canon BLE camera. First-time pairing triggers the OS
     *  pair dialog; subsequent connects reuse the bond. Mutually exclusive
     *  with the other transports — they're dropped first. [auto] is true
     *  when the call originates from the auto-reconnect collector (the
     *  previously-bonded body just re-advertised); false on explicit user
     *  taps. Auto-reconnect must not override an active simulator. */
    fun connectCanonBle(
        device: android.bluetooth.BluetoothDevice,
        auto: Boolean = false,
        /** Reconnect to a previously-bonded body: use OS-managed autoConnect
         *  (waits for the camera to become available — a directed-advertising
         *  body never shows in the service-UUID scan) + a longer window. */
        autoReconnect: Boolean = false,
    ) {
        canonBleConnectJob?.cancel()
        lastCanonBleDevice = device
        canonBleConnectJob = viewModelScope.launch {
            if (auto && _simulatorActive.value) {
                Log.i(TAG, "connectCanonBle(auto): simulator active, skipping")
                return@launch
            }
            _canonBleError.value = null
            _canonBleConnecting.value = true
            if (autoReconnect) _canonBleReconnecting.value = true
            try {
                if (bleController.connected.value) bleController.disconnect()
                if (_canonCcapiTransport.value != null) disconnectCanonCcapi()
                if (_ptpTransport.value != null) disconnectPtp()
                if (_ptpIpTransport.value != null) disconnectPtpIp()
                if (_simulatorActive.value) disconnectSimulator()
                stopScan()
                // Stop the BLE scan once connected — Android shouldn't be
                // scanning and connected at the same time on the same radio
                // (battery + scan-callback noise). The disconnect handler
                // re-arms scanning so the auto-reconnect collector picks
                // up the body's next advertisement.
                stopCanonBleScan()

                val appCtx = getApplication<Application>()
                val result = com.ehrocha.pulsar.canonble.CanonBleTransport.connect(
                    appCtx, device,
                    onSpontaneousDisconnect = { onCanonBleLinkDropped() },
                    onAwaitConfirm = { _canonBleAwaitingConfirm.value = true },
                    autoConnect = autoReconnect,
                    connectTimeoutMs = if (autoReconnect) 120_000L else 30_000L,
                )
                _canonBleAwaitingConfirm.value = false
                val transport = when (result) {
                    is com.ehrocha.pulsar.canonble.CanonBleConnectResult.Ok -> result.transport
                    com.ehrocha.pulsar.canonble.CanonBleConnectResult.NoBleShutter -> {
                        // Smartphone-mode body with no BLE shutter (2018 EOS R):
                        // steer the user to USB / Wi-Fi instead of "failed".
                        _canonBleError.value = "no_ble_shutter"
                        return@launch
                    }
                    com.ehrocha.pulsar.canonble.CanonBleConnectResult.Failed -> {
                        _canonBleError.value = "connect_failed"
                        return@launch
                    }
                }
                _canonBleTransport.value = transport
                lastCanonBleAddress = device.address
                _canonBleReconnecting.value = false
                _deviceName.value = transport.label.value
                _connected.value = true
                recordLastConnection(com.ehrocha.pulsar.model.LastConnection(
                    kind = com.ehrocha.pulsar.transport.TransportKind.CANON_BLE,
                    label = transport.label.value,
                    identifier = device.address,
                ))
                _status.value = StatusFrame(
                    state = DeviceState.IDLE,
                    mode = TriggerMode.TIMELAPSE.id,
                    shotsTaken = 0,
                    timeRemainingMs = 0L,
                    batteryPct = 0,
                    errorCode = 0,
                    fwVersion = "",
                )
            } finally {
                _canonBleConnecting.value = false
                _canonBleAwaitingConfirm.value = false
            }
        }
    }

    /** Called from the BLE GATT callback when a previously-good link
     *  drops. Flips the reconnecting banner on and arms the auto-reconnect
     *  collector. We DON'T release the transport here — `connectCanonBle`
     *  will overwrite the StateFlow with a fresh transport on
     *  re-advertise, and the old transport's `release()` is idempotent.
     *
     *  **Thread**: invoked from Android's GATT binder thread (the BLE
     *  stack runs `BluetoothGattCallback`s off-main). Everything we touch
     *  here must be thread-safe: `MutableStateFlow.value` setters are,
     *  `canonBleDiscovery.start()` is, and `disconnectCanonBle()` launches
     *  release work on `viewModelScope` rather than running it inline. */
    private fun onCanonBleLinkDropped() {
        Log.i(TAG, "Canon BLE link dropped — arming auto-reconnect")
        abortFlowOnTransportDrop()
        _canonBleReconnecting.value = true
        val device = lastCanonBleDevice
        // Tear down the dropped transport's GATT resources, but preserve
        // lastCanonBleAddress / lastCanonBleDevice for the reconnect.
        disconnectCanonBle(clearAutoReconnect = false)
        if (device != null) {
            // OS-managed autoConnect: the stack re-establishes when the body
            // is next available (including directed advertisements a scan can't
            // see). More reliable than waiting for the service-UUID scan.
            connectCanonBle(device, auto = true, autoReconnect = true)
        } else {
            // No cached device (e.g. reconnect target only known by MAC) —
            // fall back to the scan + collector path.
            canonBleDiscovery.start()
        }
    }

    private fun disconnectCanonBle(clearAutoReconnect: Boolean = true) {
        // Cancel any in-flight connect / reconnect FIRST — must run even
        // when `_canonBleTransport.value` is already null. After a
        // spontaneous link drop the transport is cleared but the
        // OS-managed autoConnect job started by `onCanonBleLinkDropped`
        // is still in flight; without this cancel, switching to a
        // sibling transport (simulator, Pulsar BLE, CCAPI, PTP) leaves
        // the camera trying to reconnect in the background and racing
        // to overwrite the chosen transport when the camera comes back.
        canonBleConnectJob?.cancel()
        canonBleConnectJob = null
        val transport = _canonBleTransport.value
        if (transport != null) {
            viewModelScope.launch { transport.release() }
            _canonBleTransport.value = null
        }
        if (clearAutoReconnect) {
            lastCanonBleAddress = null
            lastCanonBleDevice = null
            _canonBleReconnecting.value = false
        }
        if (!bleController.connected.value && !_simulatorActive.value &&
            _canonCcapiTransport.value == null && _ptpTransport.value == null) {
            _connected.value = false
            _status.value = null
        }
    }

    internal fun disconnectPtp(clearAutoReconnect: Boolean = true) {
        val transport = _ptpTransport.value ?: return
        ptpConnectJob?.cancel()
        ptpConnectJob = null
        ptpPollJob?.cancel()
        ptpPollJob = null
        viewModelScope.launch { transport.release() }
        _ptpTransport.value = null
        if (clearAutoReconnect) {
            lastPtpAutoReconnect = null
            _ptpReconnecting.value = false
        }
        if (!bleController.connected.value && !_simulatorActive.value &&
            _canonCcapiTransport.value == null) {
            _connected.value = false
            _status.value = null
        }
    }

    fun disconnect() {
        if (_canonCcapiTransport.value != null) {
            // Cancel any running flow first so the Canon loop stops firing.
            if (_flowRunning.value) {
                flowJob?.cancel()
                flowJob = null
                _flowRunning.value = false
                _flowPaused.value = false
                _flowCurrentStep.value = -1
            }
            disconnectCanonCcapi()
            return
        }
        if (_ptpTransport.value != null) {
            if (_flowRunning.value) {
                flowJob?.cancel()
                flowJob = null
                _flowRunning.value = false
                _flowPaused.value = false
                _flowCurrentStep.value = -1
            }
            disconnectPtp()
            return
        }
        if (_canonBleTransport.value != null) {
            if (_flowRunning.value) {
                flowJob?.cancel()
                flowJob = null
                _flowRunning.value = false
                _flowPaused.value = false
                _flowCurrentStep.value = -1
            }
            disconnectCanonBle()
            return
        }
        if (_ptpIpTransport.value != null) {
            if (_flowRunning.value) {
                flowJob?.cancel()
                flowJob = null
                _flowRunning.value = false
                _flowPaused.value = false
                _flowCurrentStep.value = -1
            }
            disconnectPtpIp()
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

    /** Build and start a fixed 5-step diagnostic flow that exercises every
     *  mode: Timelapse, Intervalometer (bulb), Astro, DarkFrame, Ramp. Five
     *  shots in each, 4 s exposure / 2 s interval where applicable. Astro
     *  uses 125 mm at crop 1.0 with the 500-rule, which yields exactly
     *  4 s. Timelapse runs first to dodge bulb-state contamination from the
     *  later steps. */
    /** True iff the active transport can run bulb-style modes. ESP32 BLE
     *  and simulator always can (firmware / fake-loop owns timing).
     *  CCAPI / PTP are body-conditional. Canon BLE is hard-coded true.
     *  When false, the bulb-based wizards (Astro / DarkFrame / Ramp /
     *  Intervalometer-bulb / Custom Flow) are dimmed in MainMenu and
     *  [runCameraTest] fires Timelapse only. Drives the Tools-tab tile
     *  copy too. */
    val activeTransportSupportsBulb: StateFlow<Boolean> = combine(
        _canonCcapiTransport, _ptpTransport, _canonBleTransport, _ptpIpTransport,
    ) { ccapi, ptp, ble, ptpIp ->
        // First non-null transport wins. ESP32 BLE + simulator paths don't
        // appear here — neither implements CameraTransport, and both own
        // their own bulb timing, so the "no phone-side transport" fallback
        // is true.
        when {
            ccapi != null -> ccapi.supportsBulb
            ptp != null -> ptp.supportsBulb
            ble != null -> ble.supportsBulb
            ptpIp != null -> ptpIp.supportsBulb
            else -> true
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, true)

    /** Number of steps the next [runCameraTest] would fire against the
     *  currently active transport. 1 (Timelapse only) when bulb isn't
     *  supported, 5 (all modes) when it is. */
    val cameraTestStepCount: StateFlow<Int> = activeTransportSupportsBulb
        .map { if (it) 5 else 1 }
        .stateIn(viewModelScope, SharingStarted.Eagerly, 5)

    /** True when the active camera transport honors the per-shot AF flag on
     *  the wire. Wizards gate the AutofocusToggle on this so it doesn't show
     *  on transports where it would be cosmetic (PTP/IP, BR-E1 smart-mode). */
    val activeTransportSupportsAf: StateFlow<Boolean> = combine(
        _canonCcapiTransport, _ptpTransport, _canonBleTransport, _ptpIpTransport,
    ) { ccapi, ptp, ble, ptpIp ->
        when {
            ccapi != null -> ccapi.supportsAfToggle
            ptp != null -> ptp.supportsAfToggle
            ble != null -> ble.supportsAfToggle
            ptpIp != null -> ptpIp.supportsAfToggle
            else -> false
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, false)


    /** Phase 1 of the Tools → Test Camera wizard: single Timelapse shot
     *  using the press/release shutter pattern. The user must set the
     *  camera dial to **M** before tapping Continue — single-shot path
     *  uses different shutter codes from bulb on most transports. Also
     *  runs the compat-report + settings-probe preflight on Canon. */
    fun runCameraTestManualPhase() {
        val test = listOf<FlowStep>(
            FlowStep.Intervalometer(
                intervalMs = 2_000L,
                exposureMs = AppConfig.TIMELAPSE_PULSE_MS,
                shotCount = 1, delayMs = 0L, useAutofocus = false,
            ),
        )
        saveFlowSteps(test)
        val transport = activeCameraTransport()
        if (transport != null) {
            viewModelScope.launch {
                com.ehrocha.pulsar.transport.runCompatibilityReport(transport)
                probeCameraSettings(transport)
                startFlow()
            }
        } else {
            startFlow()
        }
    }

    /** Phase 2 of the Tools → Test Camera wizard: four bulb-style steps
     *  (Intervalometer-bulb, Astro, DarkFrame, Ramp) in sequence. The
     *  user must set the camera dial to **Bulb** before tapping Continue.
     *  No preflight here — phase 1 already ran it. No-op when the active
     *  transport doesn't advertise bulb. */
    fun runCameraTestBulbPhase() {
        if (!activeTransportSupportsBulb.value) return
        val test = listOf<FlowStep>(
            FlowStep.Intervalometer(
                intervalMs = 2_000L, exposureMs = 4_000L,
                shotCount = 1, delayMs = 0L, useAutofocus = false,
            ),
            FlowStep.Astro(
                focalLength = 125, cropFactor = 1.0f, ruleDivisor = 500,
                gapMs = 2_000L, shotCount = 1, delayMs = 0L, useAutofocus = false,
            ),
            FlowStep.DarkFrame(
                gapMs = 2_000L, exposureMs = 4_000L,
                shotCount = 1, useAutofocus = false,
            ),
            FlowStep.Ramp(
                startExposureMs = 4_000L, endExposureMs = 4_000L,
                steps = 1, intervalMs = 2_000L, useAutofocus = false,
            ),
        )
        saveFlowSteps(test)
        startFlow()
    }

    /** Reads the cached dashboard snapshot (the same one that drives the
     *  home-screen widget) and projects the fields the ShotLog cares about
     *  — moon phase, cloud cover, dew, Bortle, etc. — into a small
     *  [ConditionSnapshot]. Returns null when no snapshot is cached or it
     *  fails to deserialize. */
    private fun captureCurrentConditions(): com.ehrocha.pulsar.model.ConditionSnapshot? {
        val ctx = getApplication<Application>()
        val cached = com.ehrocha.pulsar.widget.DashboardSnapshotStore.load(ctx) ?: return null
        val mgr = com.ehrocha.pulsar.astro.AstroDashboardManager(ctx)
        if (!mgr.restoreState(cached.json)) return null
        val s = mgr.state.value
        return com.ehrocha.pulsar.model.ConditionSnapshot(
            cityName = s.location?.cityName,
            moonPhase = s.moon?.phaseName,
            moonIlluminationPct = s.moon?.illuminationPct,
            moonGoodForAstro = s.moon?.goodForAstro,
            cloudCoverPct = s.weather?.cloudCoverPct,
            temperatureC = s.weather?.temperatureC,
            dewPointC = s.dewPoint?.dewPointC,
            dewRisk = s.dewPoint?.risk?.name,
            bortleClass = s.bortle?.bortleClass,
            mwVisible = s.milkyWay?.visible,
        )
    }

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
            // Snapshot the current dashboard conditions so the ShotLogEntry
            // records what the sky / weather looked like at run start.
            // Null on first install or when the user hasn't opened the
            // Dashboard tab yet (no cached snapshot to read).
            val conditions = captureCurrentConditions()
            var threw = false
            flowJob = launch {
                try {
                    for (i in steps.indices) {
                        _flowCurrentStep.value = i
                        executeFlowStep(steps[i])
                    }
                } catch (t: Throwable) {
                    threw = true
                    // CancellationException is the structured-cancel signal —
                    // rethrow so the parent scope sees the cancel cleanly.
                    // Anything else (IllegalState, IO, NPE…) is a run failure:
                    // log it for diagnostics and stop the run, but don't let
                    // it propagate to viewModelScope where it would crash the
                    // app on Android's default coroutine exception handler.
                    if (t is kotlinx.coroutines.CancellationException) throw t
                    com.ehrocha.pulsar.canonble.CanonBleLog.e(
                        "FlowJob",
                        "${t.javaClass.simpleName}: ${t.message}\n" +
                            t.stackTrace.take(6).joinToString("\n  ") { "at $it" },
                    )
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
                            conditions = conditions,
                        )
                    )
                    // Tell the user the run ended — they're often away from
                    // the phone (in a tent, asleep, on a tripod-mount climb).
                    com.ehrocha.pulsar.notify.RunCompleteNotifier.post(
                        context = getApplication<Application>(),
                        modeLabel = modeLabel,
                        completedShots = completed,
                        plannedShots = plannedShots,
                        durationMs = endedAt - startedAt,
                        status = status,
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

    /** The active phone-driven camera transport, or null if none. First
     *  non-null of CCAPI / PTP / Canon BLE wins — these are mutually
     *  exclusive (see `connect*` mutual-exclusion teardowns), so at most
     *  one is non-null at a time. ESP32 BLE and the simulator don't
     *  implement [CameraTransport] and are handled in their own branches
     *  of [executeFlowStep]. */
    private fun activeCameraTransport(): com.ehrocha.pulsar.transport.CameraTransport? {
        val cc = _canonCcapiTransport.value
        val pu = _ptpTransport.value
        val cb = _canonBleTransport.value
        val pi = _ptpIpTransport.value
        if (com.ehrocha.pulsar.BuildConfig.DEBUG) {
            // Mutual exclusion invariant — every connect* path is supposed to
            // tear down the other four. A debug-only assertion catches any
            // teardown that drops a step (e.g. a future new transport whose
            // connect path forgets to disconnect one of the others).
            val live = listOfNotNull(cc, pu, cb, pi)
            check(live.size <= 1) {
                "Mutual exclusion violated: ${live.size} transports live " +
                    "(${live.joinToString { it.kind.name }})"
            }
        }
        return cc ?: pu ?: cb ?: pi
    }

    private suspend fun probeCameraSettings(transport: com.ehrocha.pulsar.transport.CameraTransport) {
        val log = com.ehrocha.pulsar.canonble.CanonBleLog
        try {
            log.i("probe", "=== camera-settings probe: ${transport.kind.name} ===")
            log.i("probe", "supports iso=${transport.supportsIso} av=${transport.supportsAperture} tv=${transport.supportsShutterSpeed}")
            if (transport.supportsIso) {
                val v = transport.listIsoValues()
                log.i("probe", "iso values (${v.size}): ${v.take(8).joinToString()}${if (v.size > 8) " …" else ""}")
            }
            if (transport.supportsAperture) {
                val v = transport.listApertureValues()
                log.i("probe", "av values (${v.size}): ${v.take(8).joinToString()}${if (v.size > 8) " …" else ""}")
            }
            if (transport.supportsShutterSpeed) {
                val v = transport.listShutterSpeedValues()
                log.i("probe", "tv values (${v.size}): ${v.take(8).joinToString()}${if (v.size > 8) " …" else ""}")
            }
            val cur = transport.readCurrentSettings()
            log.i("probe", "current iso=${cur.iso ?: "-"} av=${cur.aperture ?: "-"} tv=${cur.shutterSpeed ?: "-"}")
            if (cur.hasAny) {
                val r = transport.applySettings(cur)
                log.i("probe", "round-trip applied=${r.applied.iso ?: "-"}/${r.applied.aperture ?: "-"}/${r.applied.shutterSpeed ?: "-"} " +
                    "skipped=${r.skipped.iso ?: "-"}/${r.skipped.aperture ?: "-"}/${r.skipped.shutterSpeed ?: "-"}")
            }
            log.i("probe", "=== done ===")
        } catch (t: Throwable) {
            log.e("probe", "failed: ${t.message}")
        }
    }

    fun stopFlow() {
        // Hand the BLE stop off immediately (don't wait on cancellation) so
        // the firmware halts ASAP; then await the flow's own finally to settle
        // _flowRunning / _flowCurrentStep before we stomp _status.
        if (!_simulatorActive.value && _canonCcapiTransport.value == null &&
            _ptpTransport.value == null && _canonBleTransport.value == null) {
            bleController.sendCommand(CommandBuilder.stop())
        }
        viewModelScope.launch {
            _canonCcapiTransport.value?.stop()
            _ptpTransport.value?.stop()
            _canonBleTransport.value?.stop()
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
                val transport = activeCameraTransport()
                when {
                    transport != null -> {
                        if (step.cameraSettings.hasAny) {
                            val result = transport.applySettings(step.cameraSettings)
                            com.ehrocha.pulsar.canonble.CanonBleLog.i(
                                "applySettings",
                                "applied=${result.applied.iso ?: "-"}/${result.applied.aperture ?: "-"}/${result.applied.shutterSpeed ?: "-"} " +
                                    "skipped=${result.skipped.iso ?: "-"}/${result.skipped.aperture ?: "-"}/${result.skipped.shutterSpeed ?: "-"}"
                            )
                        }
                        // Timelapse wizard stores its pulse-length sentinel as
                        // exposureMs; the camera owns timing in that path. Any
                        // other exposureMs means a bulb-style run. awaitReady
                        // is a no-op for non-CCAPI transports (see
                        // [awaitCanonReady] — early-returns for PTP / Canon BLE).
                        if (step.exposureMs == AppConfig.TIMELAPSE_PULSE_MS) {
                            com.ehrocha.pulsar.transport.runCanonTimelapse(
                                transport, step.shotCount, step.intervalMs, step.delayMs,
                                af = step.useAutofocus, status = _status,
                                awaitReady = { awaitCanonReady(transport) },
                            )
                        } else {
                            com.ehrocha.pulsar.transport.runCanonBulb(
                                transport, step.shotCount, step.exposureMs,
                                step.intervalMs, step.delayMs, af = step.useAutofocus,
                                status = _status,
                                awaitReady = { awaitCanonReady(transport) },
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
                val transport = activeCameraTransport()
                val expMs = AppConfig.astroExposureMs(step.focalLength, step.cropFactor, step.ruleDivisor)
                when {
                    transport != null -> com.ehrocha.pulsar.transport.runCanonBulb(
                        transport, step.shotCount, expMs,
                        step.gapMs, step.delayMs, af = step.useAutofocus,
                        status = _status, awaitReady = { awaitCanonReady(transport) },
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
                val transport = activeCameraTransport()
                when {
                    transport != null -> com.ehrocha.pulsar.transport.runCanonBulb(
                        transport, step.shotCount, step.exposureMs,
                        step.gapMs, 0L, af = step.useAutofocus,
                        status = _status, awaitReady = { awaitCanonReady(transport) },
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
                val transport = activeCameraTransport()
                val rampSteps = step.steps.coerceAtLeast(2)
                if (transport != null) {
                    com.ehrocha.pulsar.transport.runCanonRamp(
                        transport, step, rampSteps, af = step.useAutofocus,
                        status = _status, awaitReady = { awaitCanonReady(transport) },
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
     *  returns. Currently covers CCAPI (re-probes `/ccapi` after a Wi-Fi blip)
     *  and PTP/IP (re-runs the handshake against the same transport via
     *  [PtpIpTransport.reopen]). Throws if the transport was dropped entirely
     *  so the caller bails instead of firing shots into the void. The flow's
     *  [DeviceState] is flipped to WAITING while paused so the RunningView
     *  shows the paused affordance rather than RUNNING. */
    /** Dispatches the runner-pause to the right per-transport extension —
     *  see [com.ehrocha.pulsar.transport.ccapi.awaitCcapiReady],
     *  [com.ehrocha.pulsar.ptp.awaitPtpUsbReady], and
     *  [com.ehrocha.pulsar.ptp.awaitPtpIpReady] for the actual hold logic.
     *  ESP-BLE + Canon BLE direct don't have a pause-on-reconnect concept,
     *  so they return immediately. */
    /** Dispatches the runner-pause to the right per-transport extension —
     *  see [com.ehrocha.pulsar.transport.ccapi.awaitCcapiReady],
     *  [com.ehrocha.pulsar.ptp.awaitPtpUsbReady], and
     *  [com.ehrocha.pulsar.ptp.awaitPtpIpReady] for the actual hold logic.
     *  ESP-BLE + Canon BLE direct don't have a pause-on-reconnect concept,
     *  so they return immediately. */
    private suspend fun awaitCanonReady(
        transport: com.ehrocha.pulsar.transport.CameraTransport,
    ) {
        when (transport) {
            is com.ehrocha.pulsar.transport.ccapi.CcapiTransport ->
                awaitCcapiReady(transport)
            is com.ehrocha.pulsar.ptp.PtpIpTransport ->
                awaitPtpIpReady(transport)
            is com.ehrocha.pulsar.ptp.PtpTransport ->
                awaitPtpUsbReady(transport)
            else -> return
        }
    }

    // The CCAPI run loops live in `transport/CanonRunner.kt` — extracted
    // so they can be exercised in unit tests without an Application context.
    // The viewmodel wires the status flow and `awaitCanonReady` pause hook
    // in to the top-level funcs at each call site.

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
        if (activeCameraTransport() != null) {
            // Direct start() is the legacy single-mode (ESP) path. Every
            // CameraTransport (CCAPI / PTP / Canon BLE) runs via the flow
            // runner instead (the wizards → startFlow()).
            Log.w(TAG, "start() ignored on a camera transport — use the wizard / startFlow()")
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
        if (activeCameraTransport() != null) return
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
        // Any CameraTransport (CCAPI / PTP / Canon BLE) drives the manual
        // press-and-hold via startBulb; only the ESP32 path uses the BLE
        // start/stop commands. Previously only CCAPI was handled, so manual
        // mode silently no-opped on PTP and Canon BLE.
        val transport = activeCameraTransport()
        if (transport != null) {
            _status.value = _status.value?.copy(state = DeviceState.RUNNING)
            viewModelScope.launch { transport.startBulb(af = true) }
            return
        }
        if (_simulatorActive.value) {
            _status.value = _status.value?.copy(state = DeviceState.RUNNING)
            return
        }
        sendConfig()
        bleController.sendCommand(CommandBuilder.start())
    }

    /** Single shot: fire one frame immediately. Transport-aware — a
     *  CameraTransport (CCAPI / PTP / Canon BLE) does a press+release via
     *  [fireShutter]; the ESP path sends the single-shot SHUTTER opcode. */
    fun fireSingle() {
        val transport = activeCameraTransport()
        if (transport != null) {
            viewModelScope.launch {
                _status.value = _status.value?.copy(state = DeviceState.RUNNING)
                val fired = runCatching { transport.fireShutter(af = false) }.isSuccess
                _status.value = _status.value?.copy(
                    state = DeviceState.IDLE,
                    shotsTaken = (_status.value?.shotsTaken ?: 0) + if (fired) 1 else 0,
                )
            }
            return
        }
        if (_simulatorActive.value) return
        bleController.sendCommand(CommandBuilder.shutter())
    }

    /** Press & Hold: shutter close on up */
    fun shutterUp() {
        val transport = activeCameraTransport()
        if (transport != null) {
            viewModelScope.launch { transport.stopBulb() }
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
        // Mutual exclusion: simulator is a transport like the others. Tear
        // down any real session that's running (or armed for auto-reconnect)
        // so the user's deliberate "simulator" tap isn't immediately
        // overridden by an in-flight or auto-rearming hardware connect.
        if (bleController.connected.value) bleController.disconnect()
        if (_canonCcapiTransport.value != null) disconnectCanonCcapi()
        disconnectCanonBle()
        if (_ptpTransport.value != null) disconnectPtp()
        if (_ptpIpTransport.value != null) disconnectPtpIp()
        lastPtpAutoReconnect = null
        _ptpReconnecting.value = false
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
