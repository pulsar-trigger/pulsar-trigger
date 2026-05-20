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

    /** True iff the body advertises the Canon RemoteRelease vendor ops we
     *  need for bulb. Phase 2 wires startBulb/stopBulb to those ops; the
     *  wizards gate bulb-based tiles on this flag. */
    override val supportsBulb: Boolean = advertisesCanonBulb
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

    /** Whether we successfully entered PC-remote mode at connect time.
     *  If false, only basic InitiateCapture works (no bulb / settings). */
    private var pcRemoteActive: Boolean = false

    /** Open the PTP session. Required before any shutter operation. On
     *  Canon EOS bodies we also enable PC-remote + event mode so the
     *  RemoteRelease ops become available. */
    suspend fun connect(): Boolean = wireMutex.withLock {
        withContext(Dispatchers.IO) {
            try {
                val r = client.openSession(1)
                if (!r.ok) {
                    Log.w(TAG, "OpenSession failed: rc=0x${"%04X".format(r.code)}")
                    return@withContext false
                }
                _connected.value = true
                Log.i(TAG, "Session opened")
                if (deviceInfo.vendorExtensionId == PtpClient.VENDOR_EXT_CANON_EOS) {
                    val rm = runCatching { client.canonSetRemoteMode(1) }.getOrNull()
                    val em = runCatching { client.canonSetEventMode(1) }.getOrNull()
                    pcRemoteActive = rm?.ok == true && em?.ok == true
                    Log.i(TAG, "Canon PC-remote setup: " +
                        "SetRemoteMode=${rm?.code?.let { "0x%04X".format(it) }} " +
                        "EventMode=${em?.code?.let { "0x%04X".format(it) }} " +
                        "active=$pcRemoteActive")
                }
                true
            } catch (e: PtpClient.ProtocolException) {
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
                    pcRemoteActive = false
                }
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

    /** Phase 2: no-op. The Canon PTP "set shutter speed to Bulb" property
     *  value is body-specific (typically 0x0C for R-series but unverified
     *  across the lineup), so the user is expected to set Bulb on the body
     *  before running a bulb-based flow. The wire-level press/release in
     *  [startBulb] / [stopBulb] is what actually fires the exposure;
     *  setShutterMode is informational only. */
    override suspend fun setShutterMode(bulb: Boolean) {
        // Programmatic shutter-speed change is a Phase 3 polish item.
    }

    override suspend fun startBulb(af: Boolean) = wireMutex.withLock {
        // The `af` flag is honoured by CCAPI's bulb endpoint; on Canon PTP
        // the AF behaviour is set body-side (AF/MF switch + autofocus mode).
        withContext(Dispatchers.IO) {
            if (!_connected.value) {
                Log.w(TAG, "startBulb: not connected — ignored")
                return@withContext
            }
            try {
                val r = client.canonRemoteReleaseOn(mode = 3)
                if (!r.ok) Log.w(TAG, "RemoteReleaseOn rc=0x${"%04X".format(r.code)}")
            } catch (e: PtpClient.ProtocolException) {
                Log.w(TAG, "startBulb threw: ${e.message}")
            }
        }
    }

    override suspend fun stopBulb() = wireMutex.withLock<Unit> {
        withContext(Dispatchers.IO) {
            if (!_connected.value) return@withContext
            try {
                val r = client.canonRemoteReleaseOff(mode = 3)
                if (!r.ok) Log.w(TAG, "RemoteReleaseOff rc=0x${"%04X".format(r.code)}")
            } catch (e: PtpClient.ProtocolException) {
                Log.w(TAG, "stopBulb threw: ${e.message}")
            }
        }
    }

    override suspend fun stop() {
        // PTP has no cheap "abort" primitive. Bulb mode would call stopBulb
        // here; Timelapse mode has no in-flight state to abort.
    }
}
