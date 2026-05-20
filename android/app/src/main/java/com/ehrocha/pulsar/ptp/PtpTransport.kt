/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Copyright (C) 2026 Pulsar Trigger contributors
 */

package com.ehrocha.pulsar.ptp

import android.content.Context
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.util.Log
import com.ehrocha.pulsar.transport.CameraTransport
import com.ehrocha.pulsar.transport.TransportKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * `CameraTransport` over USB PTP. Phase 1 covers connect / disconnect /
 * `fireShutter` (single-shot Timelapse-mode runs where the camera owns
 * exposure timing). Bulb-style operations come in Phase 2.
 *
 * Construction is a two-step dance because each step can fail in distinct
 * ways and Pulsar wants to report which:
 *   1. [openOn] opens the USB device, claims the PTP interface, finds
 *      bulk endpoints, sends `GetDeviceInfo`. Returns the transport already
 *      pre-populated with the device label and capability flags.
 *   2. [connect] calls `OpenSession`. After this, [fireShutter] etc work.
 *
 * Cleanly stopping is `release()` — it sends `CloseSession`, releases the
 * interface, and closes the USB connection.
 */
class PtpTransport private constructor(
    val device: UsbDevice,
    private val connection: UsbDeviceConnection,
    private val iface: UsbInterface,
    private val client: PtpClient,
    val deviceInfo: PtpClient.DeviceInfo,
) : CameraTransport {

    companion object {
        private const val TAG = "PtpTransport"

        /** Open the USB device, claim its PTP interface, fetch DeviceInfo.
         *  Returns null if any step fails; the caller has nothing to clean up
         *  in that case (we close internally on failure). */
        suspend fun openOn(ctx: Context, device: UsbDevice): PtpTransport? = withContext(Dispatchers.IO) {
            val usb = ctx.getSystemService(Context.USB_SERVICE) as UsbManager
            if (!usb.hasPermission(device)) {
                Log.w(TAG, "openOn: no permission for ${device.deviceName}")
                return@withContext null
            }
            val iface = findPtpInterface(device) ?: run {
                Log.w(TAG, "openOn: ${device.deviceName} has no PTP interface")
                return@withContext null
            }
            val endpoints = findBulkEndpoints(iface) ?: run {
                Log.w(TAG, "openOn: PTP interface has no bulk-in/out pair")
                return@withContext null
            }
            val connection = usb.openDevice(device) ?: run {
                Log.w(TAG, "openOn: usb.openDevice returned null")
                return@withContext null
            }
            if (!connection.claimInterface(iface, true)) {
                Log.w(TAG, "openOn: claimInterface failed")
                connection.close()
                return@withContext null
            }
            val (bulkIn, bulkOut) = endpoints
            val client = PtpClient(connection, bulkIn, bulkOut)
            val info = try {
                client.getDeviceInfo()
            } catch (e: Throwable) {
                Log.w(TAG, "openOn: GetDeviceInfo threw", e)
                null
            }
            if (info == null) {
                Log.w(TAG, "openOn: GetDeviceInfo returned null")
                connection.releaseInterface(iface)
                connection.close()
                return@withContext null
            }
            Log.i(TAG, "openOn: ${info.manufacturer} ${info.model} " +
                       "(vendorExt=${info.vendorExtensionId}, " +
                       "ops=${info.supportedOperations.size})")
            PtpTransport(device, connection, iface, client, info)
        }

        private fun findPtpInterface(device: UsbDevice): UsbInterface? =
            (0 until device.interfaceCount)
                .map(device::getInterface)
                .firstOrNull {
                    it.interfaceClass == UsbConstants.USB_CLASS_STILL_IMAGE &&
                    it.interfaceSubclass == 0x01 &&
                    it.interfaceProtocol == 0x01
                }

        private fun findBulkEndpoints(iface: UsbInterface): Pair<UsbEndpoint, UsbEndpoint>? {
            var bulkIn: UsbEndpoint? = null
            var bulkOut: UsbEndpoint? = null
            for (i in 0 until iface.endpointCount) {
                val ep = iface.getEndpoint(i)
                if (ep.type != UsbConstants.USB_ENDPOINT_XFER_BULK) continue
                if (ep.direction == UsbConstants.USB_DIR_IN) bulkIn = ep
                else bulkOut = ep
            }
            return if (bulkIn != null && bulkOut != null) bulkIn to bulkOut else null
        }
    }

    override val kind = TransportKind.PTP_USB

    private val _label = MutableStateFlow(
        deviceInfo.model.ifBlank { deviceInfo.manufacturer.ifBlank { "USB Camera" } }
    )
    override val label: StateFlow<String> = _label

    private val _connected = MutableStateFlow(false)
    override val connected: StateFlow<Boolean> = _connected

    /** Serialises wire access — every transact goes through this. PtpClient
     *  isn't thread-safe and the run loop calls into multiple methods
     *  concurrently (e.g. status polling racing with fireShutter). */
    private val wireMutex = Mutex()

    /** Whether the camera advertises Canon's bulb operations. Determines
     *  the capability flag but the actual bulb wiring is Phase 2 — we
     *  report `false` until that's implemented. */
    val advertisesCanonBulb: Boolean = run {
        val ops = deviceInfo.supportedOperations
        PtpClient.OP_CANON_REMOTE_RELEASE_ON in ops ||
            0x9125 in ops || 0x9128 in ops
    }

    val advertisesCanonShutter: Boolean = run {
        val ops = deviceInfo.supportedOperations
        PtpClient.OP_INITIATE_CAPTURE in ops ||
            0x9008 in ops  // CanonRemoteReleaseOn
    }

    /** Phase 1: no bulb yet. Wire it on when Phase 2 lands. */
    override val supportsBulb: Boolean = false
    /** PTP DeviceInfo lists settings as device-properties. Whether Pulsar
     *  exposes a settings UI is a separate question (camera-params tab is
     *  parked) — the *transport* can support it. */
    override val supportsSettings: Boolean = deviceInfo.supportedDeviceProperties.isNotEmpty()
    /** Live view over PTP is a Canon vendor op — Phase 3 work. */
    override val supportsLiveView: Boolean = false
    /** Lens info via Canon vendor properties — Phase 3 work. */
    override val supportsLensInfo: Boolean = false
    /** Battery is PTP property 0x5001 — Phase 3 work. */
    override val supportsBatteryReadout: Boolean = false

    /** Open the PTP session. Required before any shutter operation. */
    suspend fun connect(): Boolean = wireMutex.withLock {
        withContext(Dispatchers.IO) {
            try {
                val r = client.openSession(1)
                if (r.ok) {
                    _connected.value = true
                    Log.i(TAG, "Session opened")
                    true
                } else {
                    Log.w(TAG, "OpenSession failed: rc=0x${"%04X".format(r.code)}")
                    false
                }
            } catch (e: PtpClient.ProtocolException) {
                Log.w(TAG, "OpenSession threw: ${e.message}")
                false
            }
        }
    }

    override suspend fun release() = wireMutex.withLock<Unit> {
        withContext(Dispatchers.IO) {
            if (_connected.value) {
                runCatching { client.closeSession() }
                _connected.value = false
            }
            runCatching { connection.releaseInterface(iface) }
            runCatching { connection.close() }
            Log.i(TAG, "Released")
        }
    }

    override suspend fun fireShutter(af: Boolean) = wireMutex.withLock {
        // PTP `InitiateCapture` doesn't take an AF flag — the camera uses
        // its own AF mode setting. The `af` param is honoured by CCAPI;
        // we accept and ignore it here.
        withContext(Dispatchers.IO) {
            if (!_connected.value) {
                Log.w(TAG, "fireShutter: not connected — ignored")
                return@withContext
            }
            try {
                val r = client.initiateCapture()
                if (!r.ok) {
                    Log.w(TAG, "InitiateCapture rc=0x${"%04X".format(r.code)}")
                }
            } catch (e: PtpClient.ProtocolException) {
                Log.w(TAG, "InitiateCapture threw: ${e.message}")
            }
        }
    }

    /** Phase 1: no-op. Bulb wiring lands in Phase 2. */
    override suspend fun setShutterMode(bulb: Boolean) {
        // Implemented in Phase 2 via Canon RemoteRelease / SetDeviceProperty.
    }

    override suspend fun startBulb(af: Boolean) {
        throw UnsupportedOperationException(
            "PTP bulb mode is Phase 2 work — use Timelapse mode for now"
        )
    }

    override suspend fun stopBulb() {
        // No-op so a `finally { stopBulb() }` from cancelled bulb runs is safe.
    }

    override suspend fun stop() {
        // PTP has no cheap "abort" primitive. Bulb mode would call stopBulb
        // here; Timelapse mode has no in-flight state to abort.
    }
}
