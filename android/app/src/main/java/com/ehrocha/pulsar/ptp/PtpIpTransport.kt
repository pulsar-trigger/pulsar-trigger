/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Copyright (C) 2026 Pulsar Trigger contributors
 */

package com.ehrocha.pulsar.ptp

import android.util.Log
import com.ehrocha.pulsar.canonble.CanonBleLog
import com.ehrocha.pulsar.transport.CameraImage
import com.ehrocha.pulsar.transport.CameraTransport
import com.ehrocha.pulsar.transport.TransportKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * `CameraTransport` over PTP/IP — Pulsar's 5th transport (Canon EOS Wi-Fi,
 * the path that finally drives the EOS R remotely without USB or BLE).
 *
 * Sister of [PtpTransport] (USB) — same Canon PTP op layer ([PtpClient])
 * over a different wire ([PtpIpWire]). Capability detection from
 * `GetDeviceInfo` is identical to the USB path.
 *
 * Lifecycle mirrors [PtpTransport]'s two-step pattern:
 *  1. [openOn] runs the PTP/IP handshake (4-msg init), opens a session,
 *     and pulls `GetDeviceInfo` — returns a pre-populated transport.
 *  2. [connect] enables Canon PC-remote mode so RemoteRelease ops work.
 *
 * Bulb / single-shot ops mirror the USB transport line-for-line — the
 * client layer is shared, only the wire differs.
 */
class PtpIpTransport private constructor(
    val camera: PtpIpCamera,
    private val clientName: String,
    private val clientGuid: java.util.UUID,
    initialWire: PtpIpWire,
    initialClient: PtpClient,
    initialDeviceInfo: PtpClient.DeviceInfo,
) : CameraTransport {

    // Mutable so [reopen] can swap them after a wire drop. The outer
    // [PtpIpTransport] reference stays the same — runners that captured
    // it keep working after reconnect.
    private var wire: PtpIpWire = initialWire
    private var client: PtpClient = initialClient

    /** The most-recent `GetDeviceInfo` payload. Re-fetched once after the
     *  Canon PC-remote handshake activates the vendor ops (RemoteReleaseOn /
     *  Off etc.) so a Compatibility Report run AFTER PC-remote reflects
     *  reality. External readers see the latest snapshot. */
    var deviceInfo: PtpClient.DeviceInfo = initialDeviceInfo
        private set

    companion object {
        private const val TAG = "PtpIpTransport"

        // Canon RemoteRelease mode parameter values — match PtpTransport.
        //   2 = full press, no AF
        //   3 = full press + AF
        private const val MODE_FULL_PRESS_NO_AF = 2
        private const val MODE_FULL_PRESS_AF = 3

        /** Connect to [camera] over Wi-Fi PTP/IP: run the init handshake
         *  (camera prompts the user to allow the connection — see
         *  [onAwaitConfirm]), open a session, fetch DeviceInfo. Returns
         *  null on any failure; sockets are closed internally so the
         *  caller has nothing to release. */
        suspend fun openOn(
            camera: PtpIpCamera,
            clientName: String = "Pulsar",
            clientGuid: java.util.UUID,
            onAwaitConfirm: () -> Unit = {},
        ): PtpIpOpenResult = withContext(Dispatchers.IO) {
            val res = doHandshake(camera, clientName, clientGuid, onAwaitConfirm)
            when (res) {
                is HandshakeResult.Ok -> {
                    CanonBleLog.i(TAG, "openOn: ${res.info.manufacturer} ${res.info.model} " +
                        "(vendorExt=${res.info.vendorExtensionId}, " +
                        "ops=${res.info.supportedOperations.size})")
                    PtpIpOpenResult.Ok(PtpIpTransport(
                        camera = camera,
                        clientName = clientName,
                        clientGuid = clientGuid,
                        initialWire = res.wire,
                        initialClient = res.client,
                        initialDeviceInfo = res.info,
                    ))
                }
                is HandshakeResult.Rejected -> PtpIpOpenResult.Rejected(res.reason)
                is HandshakeResult.Failed -> PtpIpOpenResult.Failed(res.reason)
            }
        }

        private suspend fun doHandshake(
            camera: PtpIpCamera,
            clientName: String,
            clientGuid: java.util.UUID,
            onAwaitConfirm: () -> Unit,
        ): HandshakeResult {
            val handshake = PtpIpWire.connect(
                host = camera.host,
                port = camera.port,
                clientName = clientName,
                clientGuid = clientGuid,
                onAwaitConfirm = onAwaitConfirm,
            )
            val wire = when (handshake) {
                is PtpIpConnectResult.Ok -> handshake.wire
                is PtpIpConnectResult.Rejected -> {
                    Log.w(TAG, "handshake rejected — ${handshake.reason}")
                    return HandshakeResult.Rejected(handshake.reason)
                }
                is PtpIpConnectResult.Failed -> {
                    Log.w(TAG, "handshake failed — ${handshake.reason}")
                    return HandshakeResult.Failed(handshake.reason)
                }
            }
            val client = PtpClient(wire)
            val info = try {
                client.getDeviceInfo()
            } catch (e: Throwable) {
                Log.w(TAG, "handshake: GetDeviceInfo threw", e)
                null
            }
            if (info == null) {
                wire.close()
                return HandshakeResult.Failed("GetDeviceInfo returned null")
            }
            return HandshakeResult.Ok(wire, client, info)
        }

        private sealed interface HandshakeResult {
            data class Ok(
                val wire: PtpIpWire,
                val client: PtpClient,
                val info: PtpClient.DeviceInfo,
            ) : HandshakeResult
            data class Rejected(val reason: String) : HandshakeResult
            data class Failed(val reason: String) : HandshakeResult
        }
    }

    override val kind = TransportKind.PTP_IP

    private val _label = MutableStateFlow(
        deviceInfo.model.ifBlank { camera.name }
    )
    override val label: StateFlow<String> = _label

    private val _connected = MutableStateFlow(false)
    override val connected: StateFlow<Boolean> = _connected

    /** Serialises wire access — every transact goes through this. PtpClient
     *  isn't thread-safe and the run loop calls into multiple methods
     *  concurrently (status polling racing with fireShutter). */
    private val wireMutex = Mutex()

    // ── Capability detection mirrors PtpTransport (USB) — same DeviceInfo
    //    fields, same vendor op codes.

    /** **Hardcoded true** on PTP/IP because Canon doesn't advertise vendor
     *  ops (`0x9128` `RemoteReleaseOn` / `0x9129` `RemoteReleaseOff`) in the
     *  initial `GetDeviceInfo` over Wi-Fi — they only appear after PC-remote
     *  mode is enabled, and re-fetching DeviceInfo at that point would be a
     *  bigger refactor (cached `val`). All R-series bodies that speak
     *  PTP/IP support bulb. If a non-Canon PTP/IP body ever appears the wire
     *  call simply returns OperationNotSupported and the runner reports a
     *  failed shot. */
    // Canon's vendorExtensionId varies by body + firmware — the 2018 EOS R
    // reports 6 (MTP) over both USB and Wi-Fi PTP, other R-series bodies
    // may report 11 (Canon EOS). Gate on the manufacturer string so both
    // values are accepted as Canon.
    private val canonExtension: Boolean =
        deviceInfo.manufacturer.startsWith("Canon", ignoreCase = true)
    override val supportsBulb: Boolean = canonExtension
    override val supportsSettings: Boolean = deviceInfo.supportedDeviceProperties.isNotEmpty()

    // [_liveViewSupported] / [_batterySupported] start true if the body
    // advertises the relevant op / prop, but get **downgraded at runtime**
    // when the first call returns "not supported" (rc=0x200A / 0x2005).
    // The EOS R/RP are the discovered cases: they list GetViewFinderData
    // in GetDeviceInfo but reject SetEvfOutput, and list BatteryLevel but
    // reject the read. Backed by StateFlow so Compose tile gates update
    // without a restart.
    private val _liveViewSupported = MutableStateFlow(
        PtpClient.OP_CANON_GET_VIEWFINDER_DATA in deviceInfo.supportedOperations
    )
    override val supportsLiveView: Boolean get() = _liveViewSupported.value
    override val liveViewSupportedFlow: StateFlow<Boolean> = _liveViewSupported

    override val supportsLensInfo: Boolean =
        PtpClient.PROP_CANON_LENS_NAME in deviceInfo.supportedDeviceProperties

    private val _batterySupported = MutableStateFlow(
        PtpClient.PROP_BATTERY_LEVEL in deviceInfo.supportedDeviceProperties
    )
    override val supportsBatteryReadout: Boolean get() = _batterySupported.value
    override val batterySupportedFlow: StateFlow<Boolean> = _batterySupported

    // EOS R rejects RemoteRelease mode=2 (no-AF) over Wi-Fi with DEVICE_BUSY,
    // so [fireShutter] / [startBulb] force mode=3 — the af flag has no wire
    // effect on PTP/IP. Wizards hide the toggle.
    override val supportsAfToggle: Boolean = false

    private var pcRemoteActive: Boolean = false
    private var lastBulbMode: Int = MODE_FULL_PRESS_NO_AF

    /** Open the PTP session + enable Canon PC-remote mode. After this the
     *  transport is ready for shutter / bulb ops. */
    suspend fun connect(): Boolean = wireMutex.withLock {
        withContext(Dispatchers.IO) {
            try {
                val r = client.openSession(1)
                if (!r.ok) {
                    Log.w(TAG, "OpenSession failed: rc=0x${"%04X".format(r.code)}")
                    return@withContext false
                }
                _connected.value = true
                CanonBleLog.i(TAG, "Session opened")
                // canonExtension is gated on the manufacturer string — EOS R/RP
                // report vendorExtensionId=6 despite being Canon, so the
                // earlier vendorExt==11 gate skipped PC-remote setup on them.
                if (canonExtension) {
                    val rm = runCatching { client.canonSetRemoteMode(1) }.getOrNull()
                    val em = runCatching { client.canonSetEventMode(1) }.getOrNull()
                    pcRemoteActive = rm?.ok == true && em?.ok == true
                    CanonBleLog.i(TAG, "Canon PC-remote setup: " +
                        "SetRemoteMode=${rm?.code?.let { "0x%04X".format(it) }} " +
                        "EventMode=${em?.code?.let { "0x%04X".format(it) }} " +
                        "active=$pcRemoteActive")
                    // Canon vendor ops only appear in GetDeviceInfo after
                    // PC-remote is active. Re-fetch so deviceInfo reflects
                    // the post-PC-remote op + prop list — Compatibility
                    // Report and any later capability re-derivation see
                    // the real surface.
                    if (pcRemoteActive) refreshDeviceInfoAfterPcRemote()
                }
                true
            } catch (e: PtpProtocolException) {
                Log.w(TAG, "Connect threw: ${e.message}")
                false
            }
        }
    }

    /** Re-runs `GetDeviceInfo` and stores the result in [deviceInfo].
     *  Called once after PC-remote activates the Canon vendor ops.
     *  Capability flags that started false (e.g. live view / battery
     *  initially absent on a particular body) can flip true here when
     *  the post-PC-remote DeviceInfo reveals new ops/props — runtime
     *  downgrade still kicks in on a real failed call. */
    private suspend fun refreshDeviceInfoAfterPcRemote() {
        val fresh = runCatching { client.getDeviceInfo() }.getOrNull() ?: return
        val newOps = fresh.supportedOperations.size
        val newProps = fresh.supportedDeviceProperties.size
        val deltaOps = newOps - deviceInfo.supportedOperations.size
        val deltaProps = newProps - deviceInfo.supportedDeviceProperties.size
        deviceInfo = fresh
        CanonBleLog.i(TAG, "DeviceInfo refresh after PC-remote: " +
            "ops=$newOps (Δ${if (deltaOps >= 0) "+" else ""}$deltaOps), " +
            "props=$newProps (Δ${if (deltaProps >= 0) "+" else ""}$deltaProps)")
        // Upgrade capability flows if a new op/prop appeared. Never
        // downgrade here — runtime-rejection downgrade is the
        // authoritative path for false-positive advertisements.
        if (!_liveViewSupported.value &&
            PtpClient.OP_CANON_GET_VIEWFINDER_DATA in fresh.supportedOperations) {
            _liveViewSupported.value = true
            CanonBleLog.i(TAG, "supportsLiveView upgraded to true (op appeared after PC-remote)")
        }
        if (!_batterySupported.value &&
            PtpClient.PROP_BATTERY_LEVEL in fresh.supportedDeviceProperties) {
            _batterySupported.value = true
            CanonBleLog.i(TAG, "supportsBatteryReadout upgraded to true (prop appeared after PC-remote)")
        }
    }

    override suspend fun release() = wireMutex.withLock<Unit> {
        withContext(Dispatchers.IO) {
            if (_connected.value) {
                if (pcRemoteActive) {
                    runCatching { client.canonSetRemoteMode(0) }
                        .onFailure { Log.d(TAG, "release: SetRemoteMode(0) failed (link likely gone): ${it.message}") }
                    pcRemoteActive = false
                }
                runCatching { client.closeSession() }
                    .onFailure { Log.d(TAG, "release: CloseSession failed: ${it.message}") }
                _connected.value = false
            }
            wire.close()
            Log.i(TAG, "Released")
        }
    }

    /** Re-run the PTP/IP handshake against the same camera and swap the
     *  underlying wire/client in place. The outer [PtpIpTransport] reference
     *  stays valid — any runner that captured it keeps working after the
     *  swap. Caller is the viewmodel's reconnect job; runners just await
     *  [_connected] flipping back to true.
     *
     *  Returns true on full recovery (wire up + session open + PC-remote
     *  re-armed). On failure the transport is left disconnected and the
     *  caller decides whether to retry or give up. */
    internal suspend fun reopen(): Boolean = wireMutex.withLock {
        withContext(Dispatchers.IO) {
            runCatching { wire.close() }
            pcRemoteActive = false
            _connected.value = false
            val res = doHandshake(camera, clientName, clientGuid) { /* silent */ }
            if (res !is Companion.HandshakeResult.Ok) {
                CanonBleLog.w(TAG, "reopen: handshake failed (${(res as? Companion.HandshakeResult.Failed)?.reason})")
                return@withContext false
            }
            wire = res.wire
            client = res.client
            // deviceInfo is val — same camera so the fields are unchanged in
            // practice. If a body somehow returned different ops we'd miss
            // it; treat that as out-of-scope for now.
            try {
                val r = client.openSession(1)
                if (!r.ok) {
                    CanonBleLog.w(TAG, "reopen: OpenSession rc=0x${"%04X".format(r.code)}")
                    runCatching { wire.close() }
                    return@withContext false
                }
                _connected.value = true
                if (canonExtension) {
                    val rm = runCatching { client.canonSetRemoteMode(1) }.getOrNull()
                    val em = runCatching { client.canonSetEventMode(1) }.getOrNull()
                    pcRemoteActive = rm?.ok == true && em?.ok == true
                    if (pcRemoteActive) refreshDeviceInfoAfterPcRemote()
                }
                CanonBleLog.i(TAG, "reopen: wire restored (pcRemote=$pcRemoteActive)")
                true
            } catch (e: Throwable) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                CanonBleLog.w(TAG, "reopen: session open threw ${e.javaClass.simpleName}: ${e.message}")
                runCatching { wire.close() }
                _connected.value = false
                false
            }
        }
    }

    override suspend fun fireShutter(af: Boolean) = wireMutex.withLock {
        withContext(Dispatchers.IO) {
            if (!_connected.value) {
                CanonBleLog.w(TAG, "fireShutter: not connected — ignored")
                return@withContext
            }
            // R-series rejects mode=2 (no-AF) with DEVICE_BUSY (0x2019)
            // (verified on EOS R v0.308 diag log) — only mode=3 (full press
            // with AF) is accepted. The `af` param is honoured at the camera
            // setting level; if the user has the lens in MF the AF step is
            // a no-op anyway. So we always send mode=3 on PTP/IP and let the
            // camera decide what to do with AF based on its own settings.
            val mode = MODE_FULL_PRESS_AF
            CanonBleLog.i(TAG, "fireShutter af=$af mode=$mode → RemoteRelease pair")
            try {
                val on = client.canonRemoteReleaseOn(mode = mode)
                if (!on.ok) {
                    CanonBleLog.w(TAG, "fireShutter RemoteReleaseOn(mode=$mode) " +
                        "rc=0x${"%04X".format(on.code)}")
                    return@withContext
                }
                // 80 ms gives the body enough time to register the press
                // on PTP/IP (the wire-round-trip + camera processing eats
                // more time than USB), while keeping the window short
                // enough that continuous-drive bodies catch at most one
                // extra frame. v0.308's 20 ms was too short for some bodies.
                kotlinx.coroutines.delay(80)
                val off = client.canonRemoteReleaseOff(mode = mode)
                if (!off.ok) CanonBleLog.w(TAG, "fireShutter RemoteReleaseOff(mode=$mode) " +
                    "rc=0x${"%04X".format(off.code)}")
                else CanonBleLog.d(TAG, "fireShutter done")
            } catch (e: PtpProtocolException) {
                CanonBleLog.w(TAG, "fireShutter PtpProtocolException: ${e.message}")
            } catch (e: java.io.IOException) {
                CanonBleLog.w(TAG, "fireShutter IOException — link likely lost: ${e.message}")
                _connected.value = false
            }
        }
    }

    override suspend fun setShutterMode(bulb: Boolean) = wireMutex.withLock<Unit> {
        if (!_connected.value || !bulb) return@withLock
        withContext(Dispatchers.IO) {
            CanonBleLog.i(TAG, "setShutterMode(bulb=true) → set Canon shutter speed property")
            try {
                val v = PtpClient.CANON_SHUTTER_SPEED_BULB
                val data = byteArrayOf((v and 0xFF).toByte(), ((v ushr 8) and 0xFF).toByte())
                val r = client.setDevicePropValue(PtpClient.PROP_CANON_SHUTTER_SPEED, data)
                if (!r.ok) CanonBleLog.w(TAG, "SetShutterSpeed→Bulb rc=0x${"%04X".format(r.code)} — " +
                    "user may need to set Bulb on body dial")
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                CanonBleLog.w(TAG, "setShutterMode ${e.javaClass.simpleName} (non-fatal): ${e.message}")
                if (e is java.io.IOException) _connected.value = false
            }
        }
    }

    override suspend fun startBulb(af: Boolean) = wireMutex.withLock {
        withContext(Dispatchers.IO) {
            if (!_connected.value) {
                CanonBleLog.w(TAG, "startBulb: not connected — ignored")
                return@withContext
            }
            // EOS R over PTP/IP rejects mode=2 (full-press no-AF) with
            // 0x2019 DEVICE_BUSY — same quirk as fireShutter. Only mode=3
            // is accepted. The AF toggle then has no wire effect; users
            // who don't want per-shot AF must switch the lens to MF.
            lastBulbMode = MODE_FULL_PRESS_AF
            CanonBleLog.i(TAG, "startBulb af=$af → RemoteReleaseOn(mode=$lastBulbMode)")
            try {
                val r = client.canonRemoteReleaseOn(mode = lastBulbMode)
                if (!r.ok) CanonBleLog.w(TAG, "RemoteReleaseOn(mode=$lastBulbMode) " +
                    "rc=0x${"%04X".format(r.code)}")
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                CanonBleLog.w(TAG, "startBulb ${e.javaClass.simpleName}: ${e.message}")
                if (e is java.io.IOException) _connected.value = false
            }
        }
    }

    override suspend fun stopBulb() = wireMutex.withLock<Unit> {
        withContext(Dispatchers.IO) {
            if (!_connected.value) return@withContext
            CanonBleLog.i(TAG, "stopBulb → RemoteReleaseOff(mode=$lastBulbMode)")
            try {
                val r = client.canonRemoteReleaseOff(mode = lastBulbMode)
                if (!r.ok) CanonBleLog.w(TAG, "RemoteReleaseOff(mode=$lastBulbMode) " +
                    "rc=0x${"%04X".format(r.code)}")
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                CanonBleLog.w(TAG, "stopBulb ${e.javaClass.simpleName}: ${e.message}")
                if (e is java.io.IOException) _connected.value = false
            }
        }
    }

    override suspend fun stop() {
        runCatching { stopBulb() }
    }

    /** Read the mounted lens via Canon `LensName` prop. Mirrors the USB
     *  [PtpTransport.getLensInfo] — shared parser, just a different wire. */
    override suspend fun getLensInfo(): com.ehrocha.pulsar.transport.LensInfo? = wireMutex.withLock {
        if (!supportsLensInfo) return@withLock null
        withContext(Dispatchers.IO) {
            if (!_connected.value) return@withContext null
            try {
                val r = client.getDevicePropValue(PtpClient.PROP_CANON_LENS_NAME)
                if (!r.ok || r.data == null || r.data.isEmpty()) {
                    CanonBleLog.w(TAG, "GetDevicePropValue(LensName) rc=0x${"%04X".format(r.code)}")
                    return@withContext null
                }
                val name = decodePtpString(r.data) ?: return@withContext null
                val (focal, range) = com.ehrocha.pulsar.transport.parseFocalFromName(name)
                com.ehrocha.pulsar.transport.LensInfo(
                    mounted = name.isNotBlank(),
                    name = name,
                    focalMm = focal,
                    zoomRangeMm = range,
                )
            } catch (e: Throwable) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                CanonBleLog.w(TAG, "getLensInfo ${e.javaClass.simpleName}: ${e.message}")
                if (e is java.io.IOException) _connected.value = false
                null
            }
        }
    }

    // ── Live view + drive-focus (Star Focus wizard) ─────────────────────

    @Volatile override var lastLiveViewError: String? = null
        private set

    override suspend fun startLiveView(): Boolean = wireMutex.withLock {
        if (!_connected.value) {
            lastLiveViewError = "not connected"
            return@withLock false
        }
        withContext(Dispatchers.IO) {
            try {
                val v = PtpClient.CANON_EVF_OUTPUT_PC
                val data = byteArrayOf(
                    (v and 0xFF).toByte(),
                    ((v ushr 8) and 0xFF).toByte(),
                    ((v ushr 16) and 0xFF).toByte(),
                    ((v ushr 24) and 0xFF).toByte(),
                )
                val r = client.setDevicePropValue(PtpClient.PROP_CANON_EVF_OUTPUT, data)
                if (r.ok) {
                    lastLiveViewError = null
                    true
                } else {
                    lastLiveViewError = "SetEvfOutput rc=0x${"%04X".format(r.code)}"
                    CanonBleLog.w(TAG, "startLiveView: $lastLiveViewError")
                    // 0x200A PARAMETER_NOT_SUPPORTED / 0x2005 DEVICE_PROP_NOT_SUPPORTED
                    // = body advertised the op but rejected the underlying prop.
                    // Downgrade so subsequent attempts short-circuit and the
                    // Star Focus tile reflects reality (EOS R does this).
                    if (r.code == 0x200A || r.code == 0x2005) {
                        _liveViewSupported.value = false
                        CanonBleLog.i(TAG, "supportsLiveView downgraded to false " +
                            "(body advertised but rejects)")
                    }
                    false
                }
            } catch (e: Throwable) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                lastLiveViewError = e.message ?: e.javaClass.simpleName
                CanonBleLog.w(TAG, "startLiveView ${e.javaClass.simpleName}: ${e.message}")
                if (e is java.io.IOException) _connected.value = false
                false
            }
        }
    }

    override suspend fun stopLiveView() = wireMutex.withLock<Unit> {
        if (!_connected.value) return@withLock
        withContext(Dispatchers.IO) {
            try {
                val v = PtpClient.CANON_EVF_OUTPUT_OFF
                val data = byteArrayOf(
                    (v and 0xFF).toByte(),
                    ((v ushr 8) and 0xFF).toByte(),
                    ((v ushr 16) and 0xFF).toByte(),
                    ((v ushr 24) and 0xFF).toByte(),
                )
                client.setDevicePropValue(PtpClient.PROP_CANON_EVF_OUTPUT, data)
            } catch (e: Throwable) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                CanonBleLog.w(TAG, "stopLiveView ${e.javaClass.simpleName}: ${e.message}")
                if (e is java.io.IOException) _connected.value = false
            }
        }
    }

    override suspend fun getLiveViewFrame(): ByteArray? = wireMutex.withLock {
        if (!_connected.value) return@withLock null
        withContext(Dispatchers.IO) {
            try {
                val r = client.canonGetViewFinderData()
                if (!r.ok || r.data == null) {
                    CanonBleLog.w(TAG, "GetViewFinderData rc=0x${"%04X".format(r.code)}")
                    return@withContext null
                }
                extractJpeg(r.data)
            } catch (e: Throwable) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                CanonBleLog.w(TAG, "getLiveViewFrame ${e.javaClass.simpleName}: ${e.message}")
                if (e is java.io.IOException) _connected.value = false
                null
            }
        }
    }

    override suspend fun driveFocus(action: String) = wireMutex.withLock<Unit> {
        if (!_connected.value) return@withLock
        val value = when (action) {
            "near1" -> 0x8001
            "near2" -> 0x8002
            "near3" -> 0x8003
            "far1" -> 0x0001
            "far2" -> 0x0002
            "far3" -> 0x0003
            else -> {
                CanonBleLog.w(TAG, "driveFocus: unknown action '$action'")
                return@withLock
            }
        }
        withContext(Dispatchers.IO) {
            try {
                val r = client.canonDriveLens(value)
                if (!r.ok) CanonBleLog.w(TAG, "DriveLens($action=$value) rc=0x${"%04X".format(r.code)}")
            } catch (e: Throwable) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                CanonBleLog.w(TAG, "driveFocus ${e.javaClass.simpleName}: ${e.message}")
                if (e is java.io.IOException) _connected.value = false
            }
        }
    }

    /** Read battery percentage via standard PTP `BatteryLevel` (0x5001).
     *  Null when the prop isn't advertised or the read fails. */
    suspend fun readBatteryPercent(): Int? = wireMutex.withLock {
        if (!supportsBatteryReadout) return@withLock null
        withContext(Dispatchers.IO) {
            if (!_connected.value) return@withContext null
            try {
                val r = client.getDevicePropValue(PtpClient.PROP_BATTERY_LEVEL)
                if (!r.ok || r.data == null || r.data.isEmpty()) {
                    CanonBleLog.w(TAG, "GetDevicePropValue(0x5001) rc=0x${"%04X".format(r.code)}")
                    if (r.code == 0x2005 || r.code == 0x200A) {
                        _batterySupported.value = false
                        CanonBleLog.i(TAG, "supportsBatteryReadout downgraded to false " +
                            "(body advertised but rejects)")
                    }
                    return@withContext null
                }
                (r.data[0].toInt() and 0xFF).coerceIn(0, 100)
            } catch (e: Throwable) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                CanonBleLog.w(TAG, "readBatteryPercent ${e.javaClass.simpleName}: ${e.message}")
                if (e is java.io.IOException) _connected.value = false
                null
            }
        }
    }

    // ── Photo transfer ──────────────────────────────────────────────────
    // Shares PtpContent.kt with USB PTP; wireMutex held across each call so a
    // battery poll can't interleave the GetObject data phase over TCP.
    override val supportsContentTransfer: Boolean = true

    override suspend fun listContents(): List<CameraImage> =
        wireMutex.withLock { client.listImageContents() }

    override suspend fun getThumbnail(image: CameraImage): ByteArray? =
        wireMutex.withLock { client.thumbnailFor(image) }

    // JPEG rendition = the embedded preview extracted from the CR3 (PTP has no
    // display-rendition op like CCAPI's ?kind=display).
    override val supportsJpegRendition: Boolean get() = true

    override suspend fun downloadImage(
        image: CameraImage,
        sink: java.io.OutputStream,
        onProgress: (Long, Long) -> Unit,
        asJpeg: Boolean,
    ): Boolean = wireMutex.withLock {
        if (asJpeg && image.isRaw) client.downloadObjectAsJpeg(image, sink, onProgress)
        else client.downloadObject(image, sink, onProgress)
    }
}

/** Outcome of [PtpIpTransport.openOn] — distinguishes user-rejection on the
 *  camera prompt from connect/protocol failures. */
sealed interface PtpIpOpenResult {
    data class Ok(val transport: PtpIpTransport) : PtpIpOpenResult
    data class Failed(val reason: String) : PtpIpOpenResult
    data class Rejected(val reason: String) : PtpIpOpenResult
}
