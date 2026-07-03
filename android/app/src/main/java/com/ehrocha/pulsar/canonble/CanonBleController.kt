/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Copyright (C) 2026 Pulsar Trigger contributors
 */

package com.ehrocha.pulsar.canonble

import android.bluetooth.BluetoothDevice
import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Owns the Canon BLE **connection lifecycle** — service discovery, the active
 * transport, the connecting/awaiting-confirm/error/reconnecting UI flags,
 * last-address persistence, and the connect / disconnect / spontaneous-drop /
 * auto-reconnect state machine. Extracted from `PulsarViewModel` (audit H1
 * slice 2) so the connection code — the largest remaining Canon-BLE chunk in
 * the VM — lives in one cohesive place.
 *
 * The cross-transport concerns stay in the ViewModel behind [Host]: mutual
 * exclusion (dropping the sibling transports before we take the radio), the
 * shared connected/status/device-name state, the last-connection hint, and the
 * running-flow abort. Behaviour is a verbatim move of the former VM methods —
 * same ordering, same thread-safety contract.
 */
class CanonBleController(
    private val scope: CoroutineScope,
    private val context: Context,
    private val prefs: SharedPreferences,
    private val settings: CanonBleSettings,
    private val host: Host,
) {
    /** Cross-transport hooks the controller can't own — implemented by the VM,
     *  which coordinates all six transports and the shared connection state. */
    interface Host {
        /** True while the on-device simulator transport is active. */
        val simulatorActive: Boolean
        /** Drop every sibling transport + stop the Pulsar scan before a Canon
         *  BLE connect takes the radio (mutual exclusion). */
        fun tearDownSiblingsForCanonBle()
        /** A Canon BLE transport just connected — set the shared
         *  connected/status/device-name state + record the reconnect hint. */
        fun onCanonBleConnected(transport: CanonBleTransport, device: BluetoothDevice)
        /** A Canon BLE transport was released — clear the shared
         *  connected/status state if no transport is left active. */
        fun onCanonBleReleased()
        /** A live link dropped mid-run — abort the running flow. */
        fun abortFlowOnTransportDrop()
        /** True when NO transport (any kind) is currently active. */
        fun noTransportActive(): Boolean
    }

    private val discovery = CanonBleDiscovery(context)
    val cameras: StateFlow<List<BluetoothDevice>> = discovery.cameras

    private val _transport = MutableStateFlow<CanonBleTransport?>(null)
    val transport: StateFlow<CanonBleTransport?> = _transport.asStateFlow()

    private val _connecting = MutableStateFlow(false)
    val connecting: StateFlow<Boolean> = _connecting.asStateFlow()

    /** True while the smartphone-mode handshake is waiting for the user to
     *  confirm the pairing on the camera body. */
    private val _awaitingConfirm = MutableStateFlow(false)
    val awaitingConfirm: StateFlow<Boolean> = _awaitingConfirm.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()
    fun clearError() { _error.value = null }

    /** True while a previously-connected body has dropped and Pulsar is waiting
     *  for it to advertise again (reconnect banner). */
    private val _reconnecting = MutableStateFlow(false)
    val reconnecting: StateFlow<Boolean> = _reconnecting.asStateFlow()

    /** Last [BluetoothDevice] connected to, kept so a drop can fire an
     *  OS-managed autoConnect without waiting for a service-UUID scan. */
    @Volatile private var lastDevice: BluetoothDevice? = null

    /** MAC of the last successfully-connected body. Persisted in plain prefs
     *  (the bond itself lives in the OS keystore). Cached in memory so the
     *  auto-reconnect collector — invoked per advertisement — doesn't hit disk
     *  on every emission. Exposed for Manage Devices / Forget. */
    @Volatile private var lastAddressCache: String? = prefs.getString(KEY_LAST_ADDRESS, null)
    var lastAddress: String?
        get() = lastAddressCache
        set(value) {
            lastAddressCache = value
            prefs.edit().apply {
                if (value == null) remove(KEY_LAST_ADDRESS) else putString(KEY_LAST_ADDRESS, value)
            }.apply()
        }

    private var connectJob: Job? = null

    init {
        // Auto-reconnect collector: watch the Canon BLE service scan for our
        // last-paired body reappearing while we're idle, and reconnect.
        scope.launch {
            discovery.cameras.collect { cameras ->
                val want = lastAddress ?: return@collect
                if (!_reconnecting.value) return@collect
                if (_transport.value != null) return@collect
                // Don't cancel an OS-managed autoConnect already in-flight
                // (started by onLinkDropped) — restarting with autoConnect=false
                // would churn the attempt.
                if (_connecting.value) return@collect
                if (!host.noTransportActive()) return@collect
                val match = cameras.firstOrNull { it.address == want } ?: return@collect
                Log.i(TAG, "Canon BLE re-advertise from $want — auto-reconnecting")
                connect(match, auto = true)
            }
        }
    }

    /** Begin / refresh the Canon BLE service scan (ScanScreen visible). */
    fun startScan() = discovery.start()
    /** Stop the Canon BLE scan (ScanScreen exits, or a connect is in-flight). */
    fun stopScan() = discovery.stop()

    /** Connect to a Canon BLE camera. First-time pairing triggers the OS pair
     *  dialog; later connects reuse the bond. Mutually exclusive with the other
     *  transports (dropped first). [auto] is true from the auto-reconnect
     *  collector; auto-reconnect must not override an active simulator.
     *  [autoReconnect] uses OS-managed autoConnect + a longer window for a
     *  previously-bonded body that may only advertise directedly. */
    fun connect(device: BluetoothDevice, auto: Boolean = false, autoReconnect: Boolean = false) {
        connectJob?.cancel()
        lastDevice = device
        connectJob = scope.launch {
            if (auto && host.simulatorActive) {
                Log.i(TAG, "connect(auto): simulator active, skipping")
                return@launch
            }
            _error.value = null
            _connecting.value = true
            if (autoReconnect) _reconnecting.value = true
            try {
                // Mutual exclusion: drop siblings + stop the Pulsar scan, then
                // stop our own Canon scan (Android shouldn't scan + connect on
                // the same radio; the disconnect handler re-arms scanning).
                host.tearDownSiblingsForCanonBle()
                stopScan()

                val result = CanonBleTransport.connect(
                    context, device,
                    onSpontaneousDisconnect = { onLinkDropped() },
                    onAwaitConfirm = { _awaitingConfirm.value = true },
                    autoConnect = autoReconnect,
                    connectTimeoutMs = if (autoReconnect) 120_000L else 30_000L,
                    bre1Name = settings.nameRemote.value,
                    smartName = settings.nameSmart.value,
                )
                _awaitingConfirm.value = false
                val transport = when (result) {
                    is CanonBleConnectResult.Ok -> result.transport
                    CanonBleConnectResult.NoBleShutter -> {
                        // Smartphone-mode body with no BLE shutter (2018 EOS R):
                        // steer the user to USB / Wi-Fi instead of "failed".
                        _error.value = "no_ble_shutter"
                        _reconnecting.value = false
                        return@launch
                    }
                    CanonBleConnectResult.Failed -> {
                        _error.value = "connect_failed"
                        // Clear the reconnecting banner — a timed-out patient
                        // reconnect must not leave it stuck on forever.
                        _reconnecting.value = false
                        return@launch
                    }
                }
                transport.postFrameCooldownMs = settings.cooldownMs.value
                _transport.value = transport
                lastAddress = device.address
                _reconnecting.value = false
                host.onCanonBleConnected(transport, device)
            } finally {
                _connecting.value = false
                _awaitingConfirm.value = false
            }
        }
    }

    /** Called from the BLE GATT callback when a previously-good link drops.
     *  Flips the reconnecting banner on and arms auto-reconnect. We DON'T
     *  release here — [connect] overwrites the StateFlow with a fresh transport
     *  on re-advertise and the old transport's `release()` is idempotent.
     *
     *  **Thread**: invoked from Android's GATT binder thread. Everything touched
     *  here is thread-safe: `MutableStateFlow.value` setters are,
     *  `discovery.start()` is, and [disconnect] launches release on [scope]. */
    private fun onLinkDropped() {
        Log.i(TAG, "Canon BLE link dropped — arming auto-reconnect")
        host.abortFlowOnTransportDrop()
        _reconnecting.value = true
        val device = lastDevice
        // Tear down the dropped transport's GATT resources, but preserve
        // lastAddress / lastDevice for the reconnect.
        disconnect(clearAutoReconnect = false)
        if (device != null) {
            connect(device, auto = true, autoReconnect = true)
        } else {
            discovery.start()
        }
    }

    fun disconnect(clearAutoReconnect: Boolean = true) {
        // Cancel any in-flight connect / reconnect FIRST — must run even when
        // the transport is already null. After a spontaneous drop the transport
        // is cleared but the OS-managed autoConnect started by onLinkDropped is
        // still in flight; without this cancel, switching to a sibling transport
        // leaves the camera racing to overwrite the chosen transport.
        connectJob?.cancel()
        connectJob = null
        val transport = _transport.value
        if (transport != null) {
            scope.launch { transport.release() }
            _transport.value = null
        }
        if (clearAutoReconnect) {
            lastAddress = null
            lastDevice = null
            _reconnecting.value = false
        }
        host.onCanonBleReleased()
    }

    companion object {
        private const val TAG = "CanonBleController"
        private const val KEY_LAST_ADDRESS = "last_address"
    }
}
