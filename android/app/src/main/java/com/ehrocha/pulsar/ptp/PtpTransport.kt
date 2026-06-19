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
import com.ehrocha.pulsar.canonble.CanonBleLog
import kotlinx.coroutines.delay
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
    initialDevice: UsbDevice,
    initialConnection: UsbDeviceConnection,
    initialIface: UsbInterface,
    initialClient: PtpClient,
    initialDeviceInfo: PtpClient.DeviceInfo,
) : CameraTransport {

    /** Most-recent `GetDeviceInfo` payload. Re-fetched once after the Canon
     *  PC-remote handshake so post-PC-remote ops/props appear here too. */
    var deviceInfo: PtpClient.DeviceInfo = initialDeviceInfo
        private set

    // Mutable so [reopen] can swap the underlying USB handle after a
    // cable-unplug → replug. The outer [PtpTransport] reference stays the
    // same — runners that captured it keep working after the swap. The
    // (vendorId, productId) pair is the match key the viewmodel uses to
    // route the USB ATTACHED broadcast back to this transport.
    var device: UsbDevice = initialDevice
        private set
    private var connection: UsbDeviceConnection = initialConnection
    private var iface: UsbInterface = initialIface
    private var client: PtpClient = initialClient

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
            PtpTransport(
                initialDevice = device,
                initialConnection = connection,
                initialIface = iface,
                initialClient = client,
                initialDeviceInfo = info,
            )
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

    /** True iff `GetDeviceInfo` reports a Canon body, regardless of the
     *  reported `vendorExtensionId` (which is 6 on the EOS R/RP under
     *  the firmware versions we've seen, despite Canon's spec listing 11).
     *  Use this to gate Canon-vendor PC-remote setup and shutter ops. */
    private val isCanon: Boolean =
        deviceInfo.manufacturer.startsWith("Canon", ignoreCase = true)

    /** Whether the camera advertises Canon's bulb operations. Determines
     *  the capability flag — actual bulb wiring lives in
     *  [startBulb] / [stopBulb]. */
    val advertisesCanonBulb: Boolean = run {
        val ops = deviceInfo.supportedOperations
        PtpClient.OP_CANON_REMOTE_RELEASE_ON in ops ||
            0x9125 in ops || 0x9128 in ops
    }

    /** True iff the body advertises the Canon RemoteRelease vendor ops we
     *  need for bulb. Phase 2 wires startBulb/stopBulb to those ops; the
     *  wizards gate bulb-based tiles on this flag. */
    /** USB PTP honors the per-shot AF flag at the wire via Canon's
     *  `RemoteReleaseOn` mode parameter (`2` = no AF, `3` = with AF). */
    override val supportsAfToggle: Boolean = true

    override val supportsBulb: Boolean = advertisesCanonBulb
    /** PTP DeviceInfo lists settings as device-properties. Whether Pulsar
     *  exposes a settings UI is a separate question (camera-params tab is
     *  parked) — the *transport* can support it. */
    override val supportsSettings: Boolean = deviceInfo.supportedDeviceProperties.isNotEmpty()
    /** Initial value comes from the advertised op set; **downgraded at
     *  runtime** when [startLiveView] sees `rc=0x200A` (the EOS R lists
     *  GetViewFinderData but rejects SetEvfOutput, for example). Star Focus
     *  gates on this; PTP-capable bodies without it fall through to the
     *  existing CCAPI-only Star Focus path. */
    private val _liveViewSupported = MutableStateFlow(
        PtpClient.OP_CANON_GET_VIEWFINDER_DATA in deviceInfo.supportedOperations
    )
    override val supportsLiveView: Boolean get() = _liveViewSupported.value
    override val liveViewSupportedFlow: StateFlow<Boolean> = _liveViewSupported

    /** True iff the body advertises the Canon LensName device property
     *  (`0xD157`). Reading it gives us a string like "RF16mm F2.8 STM" that
     *  Pulsar parses for the Astro wizard's focal-length auto-fill. */
    override val supportsLensInfo: Boolean =
        PtpClient.PROP_CANON_LENS_NAME in deviceInfo.supportedDeviceProperties

    private val _batterySupported = MutableStateFlow(
        PtpClient.PROP_BATTERY_LEVEL in deviceInfo.supportedDeviceProperties
    )
    override val supportsBatteryReadout: Boolean get() = _batterySupported.value
    override val batterySupportedFlow: StateFlow<Boolean> = _batterySupported

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
                if (isCanon) {
                    val rm = runCatching { client.canonSetRemoteMode(1) }.getOrNull()
                    val em = runCatching { client.canonSetEventMode(1) }.getOrNull()
                    pcRemoteActive = rm?.ok == true && em?.ok == true
                    Log.i(TAG, "Canon PC-remote setup: " +
                        "SetRemoteMode=${rm?.code?.let { "0x%04X".format(it) }} " +
                        "EventMode=${em?.code?.let { "0x%04X".format(it) }} " +
                        "active=$pcRemoteActive")
                    // Canon vendor ops appear in GetDeviceInfo only after
                    // PC-remote is active — re-fetch so the cached
                    // [deviceInfo] reflects the real surface, and upgrade
                    // capability flows for ops that just became visible.
                    if (pcRemoteActive) refreshDeviceInfoAfterPcRemote()
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

    /** Re-runs `GetDeviceInfo` and stores the result in [deviceInfo].
     *  Called once after PC-remote activates Canon vendor ops. Upgrades
     *  the runtime-mutable capability flows when an op/prop newly appears
     *  in the post-PC-remote payload; runtime downgrade still handles the
     *  false-positive case. */
    private suspend fun refreshDeviceInfoAfterPcRemote() {
        val fresh = runCatching { client.getDeviceInfo() }.getOrNull() ?: return
        val deltaOps = fresh.supportedOperations.size - deviceInfo.supportedOperations.size
        val deltaProps = fresh.supportedDeviceProperties.size - deviceInfo.supportedDeviceProperties.size
        deviceInfo = fresh
        Log.i(TAG, "DeviceInfo refresh after PC-remote: " +
            "ops=${fresh.supportedOperations.size} " +
            "(Δ${if (deltaOps >= 0) "+" else ""}$deltaOps), " +
            "props=${fresh.supportedDeviceProperties.size} " +
            "(Δ${if (deltaProps >= 0) "+" else ""}$deltaProps)")
        if (!_liveViewSupported.value &&
            PtpClient.OP_CANON_GET_VIEWFINDER_DATA in fresh.supportedOperations) {
            _liveViewSupported.value = true
            Log.i(TAG, "supportsLiveView upgraded to true (op appeared after PC-remote)")
        }
        if (!_batterySupported.value &&
            PtpClient.PROP_BATTERY_LEVEL in fresh.supportedDeviceProperties) {
            _batterySupported.value = true
            Log.i(TAG, "supportsBatteryReadout upgraded to true (prop appeared after PC-remote)")
        }
    }

    /** Re-open against a freshly-attached [newDevice] (same vid/pid as the
     *  old one, but a new OS handle after a cable-unplug → replug). Closes
     *  the dead connection, claims the new interface, builds a new [PtpClient],
     *  re-runs `OpenSession` + Canon PC-remote setup, and swaps everything
     *  in-place so any runner holding this transport reference keeps working.
     *  Returns true on full recovery. */
    internal suspend fun reopen(ctx: Context, newDevice: UsbDevice): Boolean = wireMutex.withLock {
        withContext(Dispatchers.IO) {
            // Tear down the dead handle (best-effort — the device is gone).
            runCatching { connection.releaseInterface(iface) }
            runCatching { connection.close() }
            pcRemoteActive = false
            _connected.value = false

            val usb = ctx.getSystemService(Context.USB_SERVICE) as UsbManager
            if (!usb.hasPermission(newDevice)) {
                Log.w(TAG, "reopen: no permission for ${newDevice.deviceName}")
                return@withContext false
            }
            val newIface = findPtpInterface(newDevice) ?: run {
                Log.w(TAG, "reopen: no PTP interface on ${newDevice.deviceName}"); return@withContext false
            }
            val endpoints = findBulkEndpoints(newIface) ?: run {
                Log.w(TAG, "reopen: no bulk endpoints"); return@withContext false
            }
            val newConn = usb.openDevice(newDevice) ?: run {
                Log.w(TAG, "reopen: usb.openDevice returned null"); return@withContext false
            }
            if (!newConn.claimInterface(newIface, true)) {
                Log.w(TAG, "reopen: claimInterface failed")
                newConn.close(); return@withContext false
            }
            val (bulkIn, bulkOut) = endpoints
            val newClient = PtpClient(newConn, bulkIn, bulkOut)
            device = newDevice
            connection = newConn
            iface = newIface
            client = newClient
            try {
                val r = client.openSession(1)
                if (!r.ok) {
                    Log.w(TAG, "reopen: OpenSession rc=0x${"%04X".format(r.code)}")
                    runCatching { newConn.releaseInterface(newIface); newConn.close() }
                    return@withContext false
                }
                _connected.value = true
                if (isCanon) {
                    val rm = runCatching { client.canonSetRemoteMode(1) }.getOrNull()
                    val em = runCatching { client.canonSetEventMode(1) }.getOrNull()
                    pcRemoteActive = rm?.ok == true && em?.ok == true
                    if (pcRemoteActive) refreshDeviceInfoAfterPcRemote()
                }
                Log.i(TAG, "reopen: USB wire restored (pcRemote=$pcRemoteActive)")
                true
            } catch (e: Throwable) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                Log.w(TAG, "reopen: session open threw ${e.javaClass.simpleName}: ${e.message}")
                runCatching { newConn.releaseInterface(newIface); newConn.close() }
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
            // A single shot on a Canon EOS body in PC-remote mode is a
            // RemoteRelease full-press + release — NOT generic InitiateCapture,
            // which Canon rejects once SetRemoteMode(1) is active. Using
            // InitiateCapture here is why USB timelapse + cable release silently
            // did nothing while bulb / intervalometer (RemoteRelease) worked;
            // the PTP/IP sibling was already on RemoteRelease. mode=3 (full
            // press + AF) — R-series rejects mode=2 with DEVICE_BUSY, and the
            // body's own AF/MF switch governs whether focus actually runs.
            // Falls back to InitiateCapture for non-Canon / non-RemoteRelease
            // bodies.
            if (supportsBulb) {
                val mode = MODE_FULL_PRESS_AF
                CanonBleLog.i(TAG, "fireShutter af=$af mode=$mode → RemoteRelease pair")
                try {
                    val on = client.canonRemoteReleaseOn(mode = mode)
                    if (!on.ok) {
                        CanonBleLog.w(TAG, "fireShutter RemoteReleaseOn(mode=$mode) " +
                            "rc=0x${"%04X".format(on.code)}")
                        return@withContext
                    }
                    delay(80)  // brief press-hold so the body registers one frame
                               // (matches the PTP/IP value verified on R-series)
                    val off = client.canonRemoteReleaseOff(mode = mode)
                    if (!off.ok) CanonBleLog.w(TAG, "fireShutter RemoteReleaseOff(mode=$mode) " +
                        "rc=0x${"%04X".format(off.code)}")
                    else CanonBleLog.d(TAG, "fireShutter done")
                } catch (e: PtpProtocolException) {
                    CanonBleLog.w(TAG, "fireShutter RemoteRelease threw: ${e.message}")
                }
            } else {
                CanonBleLog.i(TAG, "fireShutter → InitiateCapture (no RemoteRelease support)")
                try {
                    val r = client.initiateCapture()
                    if (!r.ok) CanonBleLog.w(TAG, "InitiateCapture rc=0x${"%04X".format(r.code)}")
                } catch (e: PtpProtocolException) {
                    CanonBleLog.w(TAG, "InitiateCapture threw: ${e.message}")
                }
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
                    CanonBleLog.w(TAG, "SetShutterSpeed→Bulb rc=0x${"%04X".format(r.code)} — " +
                              "user may need to set Bulb on body dial")
                } else CanonBleLog.d(TAG, "setShutterMode→Bulb ok")
            } catch (e: PtpProtocolException) {
                CanonBleLog.w(TAG, "setShutterMode threw (non-fatal): ${e.message}")
            }
        }
    }

    /** Tracks which Canon RemoteRelease mode value started the current bulb
     *  exposure, so [stopBulb] releases the same mode. Some bodies require
     *  the On / Off mode parameters to match; tracking it explicitly avoids
     *  surprises. */
    private var lastBulbMode: Int = MODE_FULL_PRESS_NO_AF

    /** Set once an R-series body rejects mode=2 (no-AF) with DEVICE_BUSY, so
     *  subsequent shots in the run skip straight to mode=3 instead of eating a
     *  rejected round-trip each time. */
    @Volatile private var noAfModeUnsupported = false

    override suspend fun startBulb(af: Boolean) = wireMutex.withLock {
        // The Canon RemoteRelease mode parameter directly controls AF:
        //   mode 2 = full press, no AF (camera holds whatever focus it has)
        //   mode 3 = full press + AF (body fires AF before the exposure)
        // Astro / Dark Frame / long-exposure modes default `af=false` so we
        // don't hunt for focus on stars. BUT R-series bodies reject mode=2
        // with DEVICE_BUSY (0x2019) — so af=false bulb (intervalometer / astro
        // / dark-frame / ramp) silently failed on the EOS R/RP over USB while
        // manual hold (af=true → mode=3) worked. We fall back to mode=3 on that
        // rejection: with the lens in MF the AF step is a no-op, so it neither
        // hunts nor fires the assist beam. The PTP/IP path always uses mode=3
        // for the same reason.
        withContext(Dispatchers.IO) {
            if (!_connected.value) {
                CanonBleLog.w(TAG, "startBulb: not connected — ignored")
                return@withContext
            }
            try {
                var mode = if (af || noAfModeUnsupported) MODE_FULL_PRESS_AF else MODE_FULL_PRESS_NO_AF
                var r = client.canonRemoteReleaseOn(mode = mode)
                if (!r.ok && mode == MODE_FULL_PRESS_NO_AF && r.code == 0x2019) {
                    CanonBleLog.i(TAG, "startBulb: mode=2 rejected (DEVICE_BUSY) — retrying mode=3")
                    noAfModeUnsupported = true
                    mode = MODE_FULL_PRESS_AF
                    r = client.canonRemoteReleaseOn(mode = mode)
                }
                lastBulbMode = mode
                if (!r.ok) CanonBleLog.w(TAG, "RemoteReleaseOn(mode=$mode) rc=0x${"%04X".format(r.code)}")
                else CanonBleLog.d(TAG, "startBulb mode=$mode ok")
            } catch (e: PtpProtocolException) {
                CanonBleLog.w(TAG, "startBulb threw: ${e.message}")
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
                if (!r.ok) CanonBleLog.w(TAG, "RemoteReleaseOff(mode=$lastBulbMode) " +
                                       "rc=0x${"%04X".format(r.code)}")
            } catch (e: PtpProtocolException) {
                CanonBleLog.w(TAG, "stopBulb threw: ${e.message}")
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
            } catch (e: PtpProtocolException) {
                Log.w(TAG, "getLensInfo threw: ${e.message}")
                null
            }
        }
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
                    CanonBleLog.w(TAG, "startLiveView: $lastLiveViewError")
                    // Body advertised the op but rejects the underlying
                    // prop write — downgrade so the Star Focus tile gate
                    // and subsequent calls reflect reality.
                    if (r.code == 0x200A || r.code == 0x2005) {
                        _liveViewSupported.value = false
                        CanonBleLog.i(TAG, "supportsLiveView downgraded to false (advertised but rejects)")
                    }
                    false
                }
            } catch (e: PtpProtocolException) {
                lastLiveViewError = e.message ?: "protocol error"
                CanonBleLog.w(TAG, "startLiveView threw: ${e.message}")
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
            } catch (e: PtpProtocolException) {
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
            } catch (e: PtpProtocolException) {
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
            } catch (e: PtpProtocolException) {
                Log.w(TAG, "driveFocus threw: ${e.message}")
            }
        }
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
                    CanonBleLog.w(TAG, "GetDevicePropValue(0x5001 battery) rc=0x${"%04X".format(r.code)}")
                    if (r.code == 0x2005 || r.code == 0x200A) {
                        _batterySupported.value = false
                        CanonBleLog.i(TAG, "supportsBatteryReadout downgraded to false (advertised but rejects)")
                    }
                    return@withContext null
                }
                val pct = (r.data[0].toInt() and 0xFF).coerceIn(0, 100)
                pct
            } catch (e: PtpProtocolException) {
                Log.w(TAG, "readBatteryPercent threw: ${e.message}")
                null
            }
        }
    }

    override suspend fun stop() {
        // PTP has no cheap "abort" primitive. Bulb mode would call stopBulb
        // here; Timelapse mode has no in-flight state to abort.
    }

    // ── Photo transfer ──────────────────────────────────────────────────
    // Hold wireMutex across each call so an interleaving battery poll can't
    // corrupt the in-flight data phase (critical for the multi-MB GetObject
    // stream). The actual ops live in PtpContent.kt, shared with PTP/IP.
    override val supportsContentTransfer: Boolean = true

    override suspend fun listContents(): List<CameraImage> =
        wireMutex.withLock { client.listImageContents() }

    override suspend fun getThumbnail(image: CameraImage): ByteArray? =
        wireMutex.withLock { client.thumbnailFor(image) }

    override suspend fun downloadImage(
        image: CameraImage,
        sink: java.io.OutputStream,
        onProgress: (Long, Long) -> Unit,
    ): Boolean = wireMutex.withLock { client.downloadObject(image, sink, onProgress) }
}
