/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Copyright (C) 2026 Pulsar Trigger contributors
 */

package com.ehrocha.pulsar.ptp

import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Canon / PTP high-level operations layer, transport-agnostic. The actual
 * bytes-on-the-wire framing lives in [PtpWire] implementations:
 *  - [BulkPtpWire] — PTP-over-USB (PIMA 15740 + USB Still Image class).
 *  - [PtpIpWire]  — PTP-over-TCP-IP (ISO 15740 / Wi-Fi).
 *
 * Canon EOS bodies speak the same vendor op set over USB cable and Wi-Fi,
 * which is why this client doesn't care which wire it's sitting on.
 *
 * Not thread-safe — guard transactions externally. Pulsar serialises every
 * transport call through the camera run loop, so this isn't an issue today.
 */
class PtpClient(
    private val wire: PtpWire,
    private val tag: String = "PtpClient",
) {

    /** Set after [openSession]; cleared by [closeSession]. */
    var sessionId: Int = 0
        private set

    val sessionOpen: Boolean get() = sessionId != 0

    /** PtpClient with a USB-bulk wire — convenience for the existing
     *  USB transport. New TCP/IP-backed callers construct [PtpIpWire]
     *  themselves and use the primary constructor. */
    constructor(
        connection: UsbDeviceConnection,
        bulkIn: UsbEndpoint,
        bulkOut: UsbEndpoint,
        tag: String = "PtpClient",
    ) : this(BulkPtpWire(connection, bulkIn, bulkOut), tag)

    // ── Public high-level operations ────────────────────────────────────

    /** PTP `OpenSession` — required before any non-discovery operation. */
    suspend fun openSession(id: Int = 1): Response {
        val r = wire.transact(OP_OPEN_SESSION, intArrayOf(id), expectDataIn = false)
        if (r.code == RC_OK) sessionId = id
        return r
    }

    /** PTP `CloseSession`. Idempotent — quietly returns if no session is open. */
    suspend fun closeSession(): Response {
        if (sessionId == 0) return Response(RC_OK)
        val r = wire.transact(OP_CLOSE_SESSION, intArrayOf(), expectDataIn = false)
        sessionId = 0
        return r
    }

    /** PTP `GetDeviceInfo` — can be called without an open session. Returns
     *  the parsed DeviceInfo dataset, or null on protocol failure. */
    suspend fun getDeviceInfo(): DeviceInfo? {
        val r = wire.transact(OP_GET_DEVICE_INFO, intArrayOf(), expectDataIn = true)
        if (r.code != RC_OK || r.data == null) {
            Log.w(tag, "GetDeviceInfo failed: rc=0x${"%04X".format(r.code)}")
            return null
        }
        return runCatching { parseDeviceInfo(r.data) }
            .onFailure { Log.w(tag, "GetDeviceInfo parse failed", it) }
            .getOrNull()
    }

    /** PTP `InitiateCapture` — fires the shutter using the camera's current
     *  settings. Storage and format both default to 0 (use camera defaults). */
    suspend fun initiateCapture(storageId: Int = 0, formatCode: Int = 0): Response {
        require(sessionOpen) { "InitiateCapture requires an open session" }
        return wire.transact(OP_INITIATE_CAPTURE, intArrayOf(storageId, formatCode), expectDataIn = false)
    }

    /** Generic transaction — exposed for callers that need to send a vendor
     *  operation we haven't wrapped yet (Canon RemoteRelease, etc.). */
    suspend fun runCommand(
        opCode: Int,
        params: IntArray = IntArray(0),
        dataOut: ByteArray? = null,
        expectDataIn: Boolean = false,
    ): Response = wire.transact(opCode, params, dataOut, expectDataIn)

    // ── Canon EOS vendor operations ─────────────────────────────────────
    // Canon EOS bodies require the camera to be put into "PC remote
    // control" mode before they accept RemoteRelease commands. The dance
    // is: SetRemoteMode(1) -> EventMode(1) at connect time, then
    // RemoteReleaseOn(3) / RemoteReleaseOff(3) bracket each bulb shot.
    //
    // For non-bulb single-shot capture, plain InitiateCapture (PIMA-1001)
    // still works without this setup.

    /** Canon: enable (mode=1) or disable (mode=0) PC remote-control mode.
     *  Required before any RemoteRelease op. */
    suspend fun canonSetRemoteMode(mode: Int = 1): Response =
        wire.transact(OP_CANON_SET_REMOTE_MODE, intArrayOf(mode), expectDataIn = false)

    /** Canon: enable the event channel so the body accepts subsequent
     *  remote operations. Pulsar doesn't currently consume the events. */
    suspend fun canonSetEventMode(mode: Int = 1): Response =
        wire.transact(OP_CANON_EVENT_MODE, intArrayOf(mode), expectDataIn = false)

    /** Canon: start a remote shutter press. The camera holds the shutter
     *  open until [canonRemoteReleaseOff] for bulb-mode exposures (body
     *  must be set to Bulb on its dial). `mode=3` is "full press + AF"
     *  in Canon's lexicon — the value gphoto2 uses for bulb. */
    suspend fun canonRemoteReleaseOn(mode: Int = 3): Response =
        wire.transact(OP_CANON_REMOTE_RELEASE_ON, intArrayOf(mode), expectDataIn = false)

    /** Canon: release the remote shutter press. Pair with [canonRemoteReleaseOn]. */
    suspend fun canonRemoteReleaseOff(mode: Int = 3): Response =
        wire.transact(OP_CANON_REMOTE_RELEASE_OFF, intArrayOf(mode), expectDataIn = false)

    /** Canon: read one live-view frame. Returns the raw response payload —
     *  the caller is responsible for finding the JPEG SOI / EOI markers in
     *  the body-specific wrapper Canon embeds the frame inside. */
    suspend fun canonGetViewFinderData(): Response =
        wire.transact(OP_CANON_GET_VIEWFINDER_DATA, intArrayOf(0x00100000), expectDataIn = true)

    /** Canon: step the focus motor. `value` encodes direction and magnitude:
     *  `0x0001..0x0003` = far (small / medium / large step),
     *  `0x8001..0x8003` = near. Source: libgphoto2's canon driver. */
    suspend fun canonDriveLens(value: Int): Response =
        wire.transact(OP_CANON_DRIVE_LENS, intArrayOf(value), expectDataIn = false)

    /** PTP `GetDevicePropValue` — read the current value of a device
     *  property (battery level, focal length, etc.). Caller decodes the
     *  data payload based on the property's known type. */
    suspend fun getDevicePropValue(propCode: Int): Response =
        wire.transact(OP_GET_DEVICE_PROP_VALUE, intArrayOf(propCode), expectDataIn = true)

    /** PTP `SetDevicePropValue` — write a new value to a device property.
     *  Caller pre-encodes the value bytes for the property's known type
     *  (UINT8, UINT16, STR, etc.). Returns the response code. */
    suspend fun setDevicePropValue(propCode: Int, valueBytes: ByteArray): Response =
        wire.transact(OP_SET_DEVICE_PROP_VALUE, intArrayOf(propCode),
                 dataOut = valueBytes, expectDataIn = false)

    // ── Object enumeration / transfer (photo-transfer gallery) ──────────
    // Standard PTP object ops (PIMA 15740 §5.5). Transport-agnostic, so the
    // same calls drive USB and Wi-Fi PTP image transfer.

    /** PTP `GetStorageIDs` — IDs of the stores (cards) present. */
    suspend fun getStorageIds(): List<Int> {
        val r = wire.transact(OP_GET_STORAGE_IDS, expectDataIn = true)
        return if (r.ok && r.data != null) parsePtpU32Array(r.data) else emptyList()
    }

    /** PTP `GetObjectHandles` — handles of objects matching the filter. The
     *  defaults enumerate every object on every store (flat list). */
    suspend fun getObjectHandles(
        storageId: Int = STORAGE_ALL,
        objectFormat: Int = 0,
        parent: Int = HANDLE_ALL,
    ): List<Int> {
        val r = wire.transact(
            OP_GET_OBJECT_HANDLES, intArrayOf(storageId, objectFormat, parent),
            expectDataIn = true,
        )
        return if (r.ok && r.data != null) parsePtpU32Array(r.data) else emptyList()
    }

    /** PTP `GetObjectInfo` — metadata (format, size, filename, capture date)
     *  for one handle. Null on protocol failure / unparseable dataset. */
    suspend fun getObjectInfo(handle: Int): PtpObjectInfo? {
        val r = wire.transact(OP_GET_OBJECT_INFO, intArrayOf(handle), expectDataIn = true)
        return if (r.ok && r.data != null) runCatching { parsePtpObjectInfo(r.data) }.getOrNull() else null
    }

    /** PTP `GetThumb` — the embedded thumbnail JPEG for one handle, or null.
     *  Small enough to buffer (the gallery grid uses these). */
    suspend fun getThumb(handle: Int): ByteArray? {
        val r = wire.transact(OP_GET_THUMB, intArrayOf(handle), expectDataIn = true)
        return if (r.ok) r.data else null
    }

    /** PTP `GetObject` — stream the full file for one handle into [sink]
     *  (never buffered — RAWs are tens of MB). Returns true on `RC_OK`. */
    suspend fun getObject(
        handle: Int,
        sink: java.io.OutputStream,
        onProgress: (Long, Long) -> Unit = { _, _ -> },
    ): Boolean = wire.transactStream(OP_GET_OBJECT, intArrayOf(handle), sink, onProgress).ok

    // ── Result types ────────────────────────────────────────────────────

    /** A complete transaction's outcome: response code, any returned params,
     *  and (for data-in operations) the dataset payload. */
    data class Response(
        val code: Int,
        val params: IntArray = IntArray(0),
        val data: ByteArray? = null,
    ) {
        val ok: Boolean get() = code == RC_OK
        override fun toString() = "Response(code=0x${"%04X".format(code)}, " +
            "params=${params.toList()}, data=${data?.size ?: 0}B)"
    }

    /** Parsed `GetDeviceInfo` dataset — subset of fields Pulsar cares about. */
    data class DeviceInfo(
        val standardVersion: Int,
        val vendorExtensionId: Long,
        val vendorExtensionVersion: Int,
        val vendorExtensionDesc: String,
        val supportedOperations: List<Int>,
        val supportedEvents: List<Int>,
        val supportedDeviceProperties: List<Int>,
        val manufacturer: String,
        val model: String,
        val deviceVersion: String,
        val serialNumber: String,
    )

    // ── DeviceInfo parser (PIMA 15740 §5.5.1) ───────────────────────────

    private fun parseDeviceInfo(data: ByteArray): DeviceInfo {
        val buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        val standardVersion = buf.short.toInt() and 0xFFFF
        val vendorExtensionId = buf.int.toLong() and 0xFFFFFFFFL
        val vendorExtensionVersion = buf.short.toInt() and 0xFFFF
        val vendorExtensionDesc = readPtpString(buf)
        buf.short                                                         // FunctionalMode
        val supportedOps = readU16Array(buf)
        val supportedEvents = readU16Array(buf)
        val supportedDeviceProps = readU16Array(buf)
        readU16Array(buf)                                                 // CaptureFormats
        readU16Array(buf)                                                 // ImageFormats
        val manufacturer = readPtpString(buf)
        val model = readPtpString(buf)
        val deviceVersion = readPtpString(buf)
        val serialNumber = readPtpString(buf)
        return DeviceInfo(
            standardVersion = standardVersion,
            vendorExtensionId = vendorExtensionId,
            vendorExtensionVersion = vendorExtensionVersion,
            vendorExtensionDesc = vendorExtensionDesc,
            supportedOperations = supportedOps,
            supportedEvents = supportedEvents,
            supportedDeviceProperties = supportedDeviceProps,
            manufacturer = manufacturer,
            model = model,
            deviceVersion = deviceVersion,
            serialNumber = serialNumber,
        )
    }

    /** PTP string: 1 byte count (UTF-16 code units incl. NUL), then UTF-16LE. */
    private fun readPtpString(buf: ByteBuffer): String {
        val units = buf.get().toInt() and 0xFF
        if (units == 0) return ""
        val bytes = ByteArray(units * 2)
        buf.get(bytes)
        val s = String(bytes, Charsets.UTF_16LE)
        return if (s.isNotEmpty() && s.last() == ' ') s.dropLast(1) else s
    }

    private fun readU16Array(buf: ByteBuffer): List<Int> {
        val n = buf.int
        val out = ArrayList<Int>(n.coerceAtMost(1024))
        for (i in 0 until n) out.add(buf.short.toInt() and 0xFFFF)
        return out
    }

    companion object {
        // ── PTP container types (PIMA 15740 §13.2.1) ─────────────────────
        const val CONTAINER_COMMAND = 1
        const val CONTAINER_DATA = 2
        const val CONTAINER_RESPONSE = 3
        const val CONTAINER_EVENT = 4

        // ── PTP standard operation codes (subset Pulsar uses) ────────────
        const val OP_GET_DEVICE_INFO = 0x1001
        const val OP_OPEN_SESSION = 0x1002
        const val OP_CLOSE_SESSION = 0x1003
        const val OP_INITIATE_CAPTURE = 0x100E
        const val OP_GET_DEVICE_PROP_VALUE = 0x1015
        const val OP_SET_DEVICE_PROP_VALUE = 0x1016
        // Object enumeration / transfer (photo-transfer gallery).
        const val OP_GET_STORAGE_IDS = 0x1004
        const val OP_GET_OBJECT_HANDLES = 0x1007
        const val OP_GET_OBJECT_INFO = 0x1008
        const val OP_GET_OBJECT = 0x1009
        const val OP_GET_THUMB = 0x100A
        /** GetObjectHandles wildcards: all stores / all objects (flat list).
         *  0xFFFFFFFF as a signed Int. */
        const val STORAGE_ALL = -1
        const val HANDLE_ALL = -1

        // ── PTP standard device properties (subset) ──────────────────────
        const val PROP_BATTERY_LEVEL = 0x5001  // UINT8 percentage 0-100

        // ── Canon-specific device properties ─────────────────────────────
        // Lens model name (PTP STR). Used by the Astro wizard to auto-fill
        // focal length the same way CCAPI does via `/devicestatus/lens`.
        const val PROP_CANON_LENS_NAME = 0xD157
        // Shutter speed (UINT16). The "Bulb" code is body-specific; on
        // R-class bodies it is typically 0x000C — verified empirically per
        // body. Pulsar tries this value as a best-effort to flip Bulb on
        // before a bulb-mode flow; if it fails the user falls back to
        // selecting Bulb on the camera dial.
        const val PROP_CANON_SHUTTER_SPEED = 0xD102
        const val CANON_SHUTTER_SPEED_BULB = 0x000C

        // EVF output device. Set to 0x02 = PC to redirect the EVF stream
        // to USB so subsequent GetViewFinderData calls return frames; set
        // back to 0x00 = none on release. Property is UINT32 on R-class.
        const val PROP_CANON_EVF_OUTPUT = 0xD1B0
        const val CANON_EVF_OUTPUT_PC = 0x02
        const val CANON_EVF_OUTPUT_OFF = 0x00

        // ── Canon EOS vendor operations (vendor extension ID 11) ─────────
        // Documented in Canon's PTP Reference; Pulsar may use these in Phase 2.
        const val OP_CANON_REMOTE_RELEASE_ON = 0x9128
        const val OP_CANON_REMOTE_RELEASE_OFF = 0x9129
        const val OP_CANON_SET_REMOTE_MODE = 0x9114
        const val OP_CANON_EVENT_MODE = 0x9115
        const val OP_CANON_GET_VIEWFINDER_DATA = 0x9153
        const val OP_CANON_DRIVE_LENS = 0x9155

        // ── PTP response codes ───────────────────────────────────────────
        const val RC_OK = 0x2001
        const val RC_SESSION_NOT_OPEN = 0x2003
        const val RC_INVALID_TRANSACTION_ID = 0x2004
        const val RC_OPERATION_NOT_SUPPORTED = 0x2005
        const val RC_DEVICE_BUSY = 0x2019

        // ── Vendor extension IDs ─────────────────────────────────────────
        const val VENDOR_EXT_CANON_EOS = 11L
        const val VENDOR_EXT_MTP = 0xFFFFL
    }
}
