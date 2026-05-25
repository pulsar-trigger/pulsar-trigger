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

        // Canon RemoteRelease mode parameter values. Documented in Canon's
        // PTP spec; verified against gphoto2's canon driver.
        //   0 = idle / release everything
        //   1 = half press (AF only)
        //   2 = full press, no AF
        //   3 = full press + AF
        private const val MODE_FULL_PRESS_NO_AF = 2
        private const val MODE_FULL_PRESS_AF = 3

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
    /** True iff the body advertises Canon's `GetViewFinderData` op — the
     *  ability to stream live-view JPEG frames over USB. Star Focus Assist
     *  gates on this; PTP-capable bodies without it (PowerShots, older
     *  DSLRs) fall through to the existing CCAPI-only Star Focus path. */
    override val supportsLiveView: Boolean =
        PtpClient.OP_CANON_GET_VIEWFINDER_DATA in deviceInfo.supportedOperations
    /** True iff the body advertises the Canon LensName device property
     *  (`0xD157`). Reading it gives us a string like "RF16mm F2.8 STM" that
     *  Pulsar parses for the Astro wizard's focal-length auto-fill. */
    override val supportsLensInfo: Boolean =
        PtpClient.PROP_CANON_LENS_NAME in deviceInfo.supportedDeviceProperties
    /** True iff the body advertises the standard PTP BatteryLevel
     *  property (`0x5001`). Some Canon bodies expose battery through a
     *  vendor-specific property instead — those would report false here
     *  even though battery info is technically available; covering them
     *  is a future polish item. */
    override val supportsBatteryReadout: Boolean =
        PtpClient.PROP_BATTERY_LEVEL in deviceInfo.supportedDeviceProperties

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
                        .onFailure { Log.d(TAG, "release: SetRemoteMode(0) failed (cable likely gone): ${it.message}") }
                    pcRemoteActive = false
                }
                runCatching { client.closeSession() }
                    .onFailure { Log.d(TAG, "release: CloseSession failed: ${it.message}") }
                _connected.value = false
            }
            runCatching { connection.releaseInterface(iface) }
                .onFailure { Log.d(TAG, "release: releaseInterface failed: ${it.message}") }
            runCatching { connection.close() }
                .onFailure { Log.d(TAG, "release: connection.close failed: ${it.message}") }
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

    /** Best-effort programmatic Bulb selection. Canon's shutter-speed
     *  property (`0xD102`) takes a body-specific UINT16 code for "Bulb"
     *  (`0x000C` on R-class). We try that code; if the body rejects it
     *  (different value table, body not in Manual mode, etc.), we log and
     *  fall back to assuming the user pre-selected Bulb on the dial — the
     *  press / release in [startBulb] / [stopBulb] still fires the
     *  exposure, just with whatever shutter speed the body has set. */
    override suspend fun setShutterMode(bulb: Boolean) = wireMutex.withLock<Unit> {
        if (!_connected.value || !bulb) return@withLock
        withContext(Dispatchers.IO) {
            try {
                // UINT16, little-endian: low byte first.
                val v = PtpClient.CANON_SHUTTER_SPEED_BULB
                val data = byteArrayOf((v and 0xFF).toByte(), ((v ushr 8) and 0xFF).toByte())
                val r = client.setDevicePropValue(PtpClient.PROP_CANON_SHUTTER_SPEED, data)
                if (!r.ok) {
                    Log.w(TAG, "SetShutterSpeed→Bulb rc=0x${"%04X".format(r.code)} — " +
                              "user may need to set Bulb on body dial")
                }
            } catch (e: PtpClient.ProtocolException) {
                Log.w(TAG, "setShutterMode threw (non-fatal): ${e.message}")
            }
        }
    }

    /** Tracks which Canon RemoteRelease mode value started the current bulb
     *  exposure, so [stopBulb] releases the same mode. Some bodies require
     *  the On / Off mode parameters to match; tracking it explicitly avoids
     *  surprises. */
    private var lastBulbMode: Int = MODE_FULL_PRESS_NO_AF

    override suspend fun startBulb(af: Boolean) = wireMutex.withLock {
        // The Canon RemoteRelease mode parameter directly controls AF:
        //   mode 2 = full press, no AF (camera holds whatever focus it has)
        //   mode 3 = full press + AF (body fires AF before the exposure)
        // Astro / Dark Frame / long-exposure modes default `af=false` from
        // the wizards so we don't hunt for focus on stars (would drift mid-
        // run + waste battery on Canon's AF assist beam). Daylight modes
        // pass `af=true` per the user's per-preset toggle.
        withContext(Dispatchers.IO) {
            if (!_connected.value) {
                Log.w(TAG, "startBulb: not connected — ignored")
                return@withContext
            }
            try {
                lastBulbMode = if (af) MODE_FULL_PRESS_AF else MODE_FULL_PRESS_NO_AF
                val r = client.canonRemoteReleaseOn(mode = lastBulbMode)
                if (!r.ok) Log.w(TAG, "RemoteReleaseOn(mode=$lastBulbMode) " +
                                       "rc=0x${"%04X".format(r.code)}")
            } catch (e: PtpClient.ProtocolException) {
                Log.w(TAG, "startBulb threw: ${e.message}")
            }
        }
    }

    override suspend fun stopBulb() = wireMutex.withLock<Unit> {
        withContext(Dispatchers.IO) {
            if (!_connected.value) return@withContext
            try {
                // Release with the same mode value we pressed with — some
                // bodies require the pair to match.
                val r = client.canonRemoteReleaseOff(mode = lastBulbMode)
                if (!r.ok) Log.w(TAG, "RemoteReleaseOff(mode=$lastBulbMode) " +
                                       "rc=0x${"%04X".format(r.code)}")
            } catch (e: PtpClient.ProtocolException) {
                Log.w(TAG, "stopBulb threw: ${e.message}")
            }
        }
    }

    /** Read the Canon LensName property (`0xD157`) and parse the focal
     *  length from the model string. Reuses the same name-parsing helper
     *  the CCAPI lens path uses for older bodies that don't report focal
     *  length natively. Returns null if the body doesn't expose the prop,
     *  if the read fails, or if no lens is mounted. */
    override suspend fun getLensInfo(): com.ehrocha.pulsar.transport.LensInfo? = wireMutex.withLock {
        if (!supportsLensInfo) return@withLock null
        withContext(Dispatchers.IO) {
            if (!_connected.value) return@withContext null
            try {
                val r = client.getDevicePropValue(PtpClient.PROP_CANON_LENS_NAME)
                if (!r.ok || r.data == null || r.data.isEmpty()) {
                    Log.w(TAG, "GetDevicePropValue(LensName) rc=0x${"%04X".format(r.code)}")
                    return@withContext null
                }
                val name = decodePtpString(r.data) ?: return@withContext null
                val mounted = name.isNotBlank()
                val (focal, range) = com.ehrocha.pulsar.transport.parseFocalFromName(name)
                com.ehrocha.pulsar.transport.LensInfo(
                    mounted = mounted,
                    name = name,
                    focalMm = focal,
                    zoomRangeMm = range,
                )
            } catch (e: PtpClient.ProtocolException) {
                Log.w(TAG, "getLensInfo threw: ${e.message}")
                null
            }
        }
    }

    /** Decode the data payload from a STR-typed device property:
     *  1-byte length (UTF-16 code units incl trailing NUL), then UTF-16LE.
     *  Returns null on a malformed/empty buffer. */
    private fun decodePtpString(data: ByteArray): String? {
        if (data.isEmpty()) return null
        val units = data[0].toInt() and 0xFF
        if (units == 0) return ""
        val byteCount = units * 2
        if (data.size < 1 + byteCount) return null
        val s = String(data, 1, byteCount, Charsets.UTF_16LE)
        return if (s.isNotEmpty() && s.last() == ' ') s.dropLast(1) else s
    }

    // ── Live view + drive-focus (Star Focus wizard) ─────────────────────

    /** Last error from a [startLiveView] attempt. Mirrors [CcapiTransport]
     *  so the wizard can surface the same diagnostic copy on either path. */
    @Volatile override var lastLiveViewError: String? = null
        private set

    /** Switch the body's EVF stream to the USB host so subsequent
     *  [getLiveViewFrame] calls return JPEG frames. Returns true on success.
     *  Sends `SetDevicePropValue(0xD1B0, 0x02)` — body must be in a mode
     *  that allows live view (Manual is fine; **Bulb is not** — Canon
     *  disables EVF in Bulb). */
    override suspend fun startLiveView(): Boolean = wireMutex.withLock {
        if (!_connected.value) {
            lastLiveViewError = "not connected"
            return@withLock false
        }
        withContext(Dispatchers.IO) {
            try {
                // EVF output device is a UINT32, little-endian.
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
                    Log.w(TAG, "startLiveView: $lastLiveViewError")
                    false
                }
            } catch (e: PtpClient.ProtocolException) {
                lastLiveViewError = e.message ?: "protocol error"
                Log.w(TAG, "startLiveView threw: ${e.message}")
                false
            }
        }
    }

    /** Stop the EVF stream by setting the output device back to "none". */
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
            } catch (e: PtpClient.ProtocolException) {
                Log.w(TAG, "stopLiveView threw: ${e.message}")
            }
        }
    }

    /** Fetch one JPEG frame from the live-view stream. Canon's
     *  `GetViewFinderData` returns a proprietary wrapper around the JPEG;
     *  we scan for the JPEG SOI marker (`0xFFD8`) and EOI marker
     *  (`0xFFD9`) and return that slice. Returns null on transport error
     *  or if no JPEG was found in the response. */
    override suspend fun getLiveViewFrame(): ByteArray? = wireMutex.withLock {
        if (!_connected.value) return@withLock null
        withContext(Dispatchers.IO) {
            try {
                val r = client.canonGetViewFinderData()
                if (!r.ok || r.data == null) {
                    Log.w(TAG, "GetViewFinderData rc=0x${"%04X".format(r.code)}")
                    return@withContext null
                }
                extractJpeg(r.data)
            } catch (e: PtpClient.ProtocolException) {
                Log.w(TAG, "getLiveViewFrame threw: ${e.message}")
                null
            }
        }
    }

    /** Drive the focus motor a step. `action` matches CCAPI's vocabulary
     *  so the wizard can pass through identical strings regardless of
     *  transport: `near1`/`near2`/`near3` (1 = fine, 3 = coarse),
     *  `far1`/`far2`/`far3`. */
    override suspend fun driveFocus(action: String) = wireMutex.withLock<Unit> {
        if (!_connected.value) return@withLock
        // Canon's DriveLens param encodes direction + magnitude:
        //   0x0001..0x0003 = far (small / medium / large)
        //   0x8001..0x8003 = near
        // Mapping is from libgphoto2's canon driver.
        val value = when (action) {
            "near1" -> 0x8001
            "near2" -> 0x8002
            "near3" -> 0x8003
            "far1" -> 0x0001
            "far2" -> 0x0002
            "far3" -> 0x0003
            else -> {
                Log.w(TAG, "driveFocus: unknown action '$action'")
                return@withLock
            }
        }
        withContext(Dispatchers.IO) {
            try {
                val r = client.canonDriveLens(value)
                if (!r.ok) Log.w(TAG, "DriveLens($action=$value) rc=0x${"%04X".format(r.code)}")
            } catch (e: PtpClient.ProtocolException) {
                Log.w(TAG, "driveFocus threw: ${e.message}")
            }
        }
    }

    /** Find the JPEG payload inside Canon's GetViewFinderData wrapper.
     *  The frame is preceded by body-specific TLV-ish headers; we don't
     *  parse them — we just scan for the JPEG Start-Of-Image marker
     *  (`0xFFD8`) and the matching End-Of-Image (`0xFFD9`). Robust enough
     *  for the Star Focus wizard which only needs a decodable JPEG. */
    private fun extractJpeg(buf: ByteArray): ByteArray? {
        val start = indexOfJpegSoi(buf) ?: return null
        val end = indexOfJpegEoi(buf, start) ?: return null
        // Slice end+2 to include the EOI marker bytes (FF D9).
        return buf.copyOfRange(start, end + 2)
    }

    private fun indexOfJpegSoi(buf: ByteArray): Int? {
        var i = 0
        val last = buf.size - 1
        while (i < last) {
            if (buf[i] == 0xFF.toByte() && buf[i + 1] == 0xD8.toByte()) return i
            i++
        }
        return null
    }

    private fun indexOfJpegEoi(buf: ByteArray, from: Int): Int? {
        var i = from + 2
        val last = buf.size - 1
        while (i < last) {
            if (buf[i] == 0xFF.toByte() && buf[i + 1] == 0xD9.toByte()) return i
            i++
        }
        return null
    }

    /** Read current battery percentage via PTP property `0x5001`. Returns
     *  null if the body doesn't expose the standard battery prop or if
     *  the read fails — caller treats null as "unknown". Called from the
     *  viewmodel's PTP polling loop. */
    suspend fun readBatteryPercent(): Int? = wireMutex.withLock {
        if (!supportsBatteryReadout) return@withLock null
        withContext(Dispatchers.IO) {
            if (!_connected.value) return@withContext null
            try {
                val r = client.getDevicePropValue(PtpClient.PROP_BATTERY_LEVEL)
                if (!r.ok || r.data == null || r.data.isEmpty()) {
                    Log.w(TAG, "GetDevicePropValue(0x5001) rc=0x${"%04X".format(r.code)}")
                    return@withContext null
                }
                val pct = (r.data[0].toInt() and 0xFF).coerceIn(0, 100)
                pct
            } catch (e: PtpClient.ProtocolException) {
                Log.w(TAG, "readBatteryPercent threw: ${e.message}")
                null
            }
        }
    }

    override suspend fun stop() {
        // PTP has no cheap "abort" primitive. Bulb mode would call stopBulb
        // here; Timelapse mode has no in-flight state to abort.
    }
}
