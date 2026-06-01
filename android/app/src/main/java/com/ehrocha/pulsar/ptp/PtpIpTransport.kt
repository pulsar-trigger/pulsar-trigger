/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Copyright (C) 2026 Pulsar Trigger contributors
 */

package com.ehrocha.pulsar.ptp

import android.util.Log
import com.ehrocha.pulsar.canonble.CanonBleLog
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
    private val wire: PtpIpWire,
    private val client: PtpClient,
    val deviceInfo: PtpClient.DeviceInfo,
) : CameraTransport {

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
                    Log.w(TAG, "openOn: rejected — ${handshake.reason}")
                    return@withContext PtpIpOpenResult.Rejected(handshake.reason)
                }
                is PtpIpConnectResult.Failed -> {
                    Log.w(TAG, "openOn: failed — ${handshake.reason}")
                    return@withContext PtpIpOpenResult.Failed(handshake.reason)
                }
            }
            val client = PtpClient(wire)
            val info = try {
                client.getDeviceInfo()
            } catch (e: Throwable) {
                Log.w(TAG, "openOn: GetDeviceInfo threw", e)
                null
            }
            if (info == null) {
                wire.close()
                return@withContext PtpIpOpenResult.Failed("GetDeviceInfo returned null")
            }
            CanonBleLog.i(TAG, "openOn: ${info.manufacturer} ${info.model} " +
                "(vendorExt=${info.vendorExtensionId}, ops=${info.supportedOperations.size})")
            PtpIpOpenResult.Ok(PtpIpTransport(camera, wire, client, info))
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
    // Canon reports vendorExtensionId = 11 ("Canon EOS") over USB but 6 ("MTP")
    // over PTP/IP — same body, different transport. Gate on the manufacturer
    // string so both paths recognize Canon and expose RemoteRelease / bulb.
    private val canonExtension: Boolean =
        deviceInfo.manufacturer.startsWith("Canon", ignoreCase = true)
    override val supportsBulb: Boolean = canonExtension
    override val supportsSettings: Boolean = deviceInfo.supportedDeviceProperties.isNotEmpty()
    override val supportsLiveView: Boolean =
        PtpClient.OP_CANON_GET_VIEWFINDER_DATA in deviceInfo.supportedOperations
    override val supportsLensInfo: Boolean =
        PtpClient.PROP_CANON_LENS_NAME in deviceInfo.supportedDeviceProperties
    override val supportsBatteryReadout: Boolean =
        PtpClient.PROP_BATTERY_LEVEL in deviceInfo.supportedDeviceProperties

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
                if (deviceInfo.vendorExtensionId == PtpClient.VENDOR_EXT_CANON_EOS) {
                    val rm = runCatching { client.canonSetRemoteMode(1) }.getOrNull()
                    val em = runCatching { client.canonSetEventMode(1) }.getOrNull()
                    pcRemoteActive = rm?.ok == true && em?.ok == true
                    CanonBleLog.i(TAG, "Canon PC-remote setup: " +
                        "SetRemoteMode=${rm?.code?.let { "0x%04X".format(it) }} " +
                        "EventMode=${em?.code?.let { "0x%04X".format(it) }} " +
                        "active=$pcRemoteActive")
                }
                true
            } catch (e: PtpProtocolException) {
                Log.w(TAG, "Connect threw: ${e.message}")
                false
            }
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
}

/** Outcome of [PtpIpTransport.openOn] — distinguishes user-rejection on the
 *  camera prompt from connect/protocol failures. */
sealed interface PtpIpOpenResult {
    data class Ok(val transport: PtpIpTransport) : PtpIpOpenResult
    data class Failed(val reason: String) : PtpIpOpenResult
    data class Rejected(val reason: String) : PtpIpOpenResult
}
