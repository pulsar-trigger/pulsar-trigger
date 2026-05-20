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
 * Thin Kotlin client for the PTP-over-USB wire protocol (PIMA 15740 +
 * USB Still Image Capture Device Class Spec 1.0). Built directly on
 * Android's [UsbDeviceConnection] — no native deps, no JNI.
 *
 * Construction expects the caller to have already opened the USB device,
 * claimed the PTP interface, and located the bulk-in / bulk-out endpoints.
 * See [PtpClient.openOn] for a helper that does all of that.
 *
 * The client is *not* thread-safe — guard transactions externally if you
 * call from multiple coroutines. In practice Pulsar serialises every
 * transport call through the run loop, so this isn't an issue today.
 */
class PtpClient(
    private val connection: UsbDeviceConnection,
    private val bulkIn: UsbEndpoint,
    private val bulkOut: UsbEndpoint,
    /** Tag used in [Log] for tracing the wire. */
    private val tag: String = "PtpClient",
) {

    /** Monotonically increasing transaction ID — every command gets a new one. */
    private var transactionId: Int = 0

    /** Set after [openSession]; cleared by [closeSession]. */
    var sessionId: Int = 0
        private set

    val sessionOpen: Boolean get() = sessionId != 0

    // ── Public high-level operations ────────────────────────────────────

    /** PTP `OpenSession` — required before any non-discovery operation. */
    suspend fun openSession(id: Int = 1): Response {
        val r = transact(OP_OPEN_SESSION, intArrayOf(id), expectDataIn = false)
        if (r.code == RC_OK) sessionId = id
        return r
    }

    /** PTP `CloseSession`. Idempotent — quietly returns if no session is open. */
    suspend fun closeSession(): Response {
        if (sessionId == 0) return Response(RC_OK)
        val r = transact(OP_CLOSE_SESSION, intArrayOf(), expectDataIn = false)
        sessionId = 0
        return r
    }

    /** PTP `GetDeviceInfo` — can be called without an open session. Returns
     *  the parsed DeviceInfo dataset, or null on protocol failure. */
    suspend fun getDeviceInfo(): DeviceInfo? {
        val r = transact(OP_GET_DEVICE_INFO, intArrayOf(), expectDataIn = true)
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
        return transact(OP_INITIATE_CAPTURE, intArrayOf(storageId, formatCode), expectDataIn = false)
    }

    /** Generic transaction — exposed for callers that need to send a vendor
     *  operation we haven't wrapped yet (Canon RemoteRelease, etc.). */
    suspend fun runCommand(
        opCode: Int,
        params: IntArray = IntArray(0),
        dataOut: ByteArray? = null,
        expectDataIn: Boolean = false,
    ): Response = transact(opCode, params, dataOut, expectDataIn)

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
        transact(OP_CANON_SET_REMOTE_MODE, intArrayOf(mode), expectDataIn = false)

    /** Canon: enable the event channel so the body accepts subsequent
     *  remote operations. Pulsar doesn't currently consume the events. */
    suspend fun canonSetEventMode(mode: Int = 1): Response =
        transact(OP_CANON_EVENT_MODE, intArrayOf(mode), expectDataIn = false)

    /** Canon: start a remote shutter press. The camera holds the shutter
     *  open until [canonRemoteReleaseOff] for bulb-mode exposures (body
     *  must be set to Bulb on its dial). `mode=3` is "full press + AF"
     *  in Canon's lexicon — the value gphoto2 uses for bulb. */
    suspend fun canonRemoteReleaseOn(mode: Int = 3): Response =
        transact(OP_CANON_REMOTE_RELEASE_ON, intArrayOf(mode), expectDataIn = false)

    /** Canon: release the remote shutter press. Pair with [canonRemoteReleaseOn]. */
    suspend fun canonRemoteReleaseOff(mode: Int = 3): Response =
        transact(OP_CANON_REMOTE_RELEASE_OFF, intArrayOf(mode), expectDataIn = false)

    /** PTP `GetDevicePropValue` — read the current value of a device
     *  property (battery level, focal length, etc.). Caller decodes the
     *  data payload based on the property's known type. */
    suspend fun getDevicePropValue(propCode: Int): Response =
        transact(OP_GET_DEVICE_PROP_VALUE, intArrayOf(propCode), expectDataIn = true)

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

    // ── Wire-protocol guts ──────────────────────────────────────────────

    /** Run one PTP transaction: command -> [optional data] -> response. */
    private fun transact(
        opCode: Int,
        params: IntArray,
        dataOut: ByteArray? = null,
        expectDataIn: Boolean = false,
    ): Response {
        val txId = ++transactionId
        sendCommand(opCode, params, txId)
        if (dataOut != null) sendData(opCode, dataOut, txId)
        val data = if (expectDataIn) readData(txId) else null
        return readResponse(txId).copy(data = data)
    }

    private fun sendCommand(opCode: Int, params: IntArray, txId: Int) {
        require(params.size <= 5) { "PTP commands take at most 5 parameters" }
        val len = 12 + params.size * 4
        val buf = ByteBuffer.allocate(len).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(len)
            .putShort(CONTAINER_COMMAND.toShort())
            .putShort(opCode.toShort())
            .putInt(txId)
        for (p in params) buf.putInt(p)
        val sent = connection.bulkTransfer(bulkOut, buf.array(), len, TIMEOUT_DEFAULT)
        if (sent != len) {
            throw ProtocolException("send-command",
                "bulkTransfer sent $sent / $len for op 0x${"%04X".format(opCode)}")
        }
    }

    private fun sendData(opCode: Int, payload: ByteArray, txId: Int) {
        val len = 12 + payload.size
        val buf = ByteBuffer.allocate(len).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(len)
            .putShort(CONTAINER_DATA.toShort())
            .putShort(opCode.toShort())
            .putInt(txId)
            .put(payload)
        val sent = connection.bulkTransfer(bulkOut, buf.array(), len, TIMEOUT_DEFAULT)
        if (sent != len) {
            throw ProtocolException("send-data",
                "bulkTransfer sent $sent / $len for op 0x${"%04X".format(opCode)}")
        }
    }

    /** Read a DATA container. Loops until [declared length] bytes received. */
    private fun readData(expectedTxId: Int): ByteArray {
        val first = ByteArray(BULK_READ_CHUNK)
        val firstLen = connection.bulkTransfer(bulkIn, first, first.size, TIMEOUT_LONG)
        if (firstLen < 12) throw ProtocolException("recv-data", "short read: $firstLen")
        val header = ByteBuffer.wrap(first, 0, 12).order(ByteOrder.LITTLE_ENDIAN)
        val declared = header.int
        val type = header.short.toInt() and 0xFFFF
        header.short // opCode echo
        val txId = header.int
        if (type != CONTAINER_DATA) {
            throw ProtocolException("recv-data", "expected DATA container, got $type")
        }
        if (txId != expectedTxId) {
            throw ProtocolException("recv-data",
                "transaction id mismatch: expected $expectedTxId, got $txId")
        }
        val out = ByteArray(declared - 12)
        val available = (firstLen - 12).coerceAtMost(out.size)
        System.arraycopy(first, 12, out, 0, available)
        var copied = available
        while (copied < out.size) {
            val n = connection.bulkTransfer(bulkIn, first, first.size, TIMEOUT_DEFAULT)
            if (n <= 0) break
            val take = n.coerceAtMost(out.size - copied)
            System.arraycopy(first, 0, out, copied, take)
            copied += take
        }
        return out
    }

    private fun readResponse(expectedTxId: Int): Response {
        val buf = ByteArray(64)
        val n = connection.bulkTransfer(bulkIn, buf, buf.size, TIMEOUT_DEFAULT)
        if (n < 12) throw ProtocolException("recv-response", "short read: $n")
        val header = ByteBuffer.wrap(buf, 0, n).order(ByteOrder.LITTLE_ENDIAN)
        val declared = header.int
        val type = header.short.toInt() and 0xFFFF
        val code = header.short.toInt() and 0xFFFF
        val txId = header.int
        if (type != CONTAINER_RESPONSE) {
            throw ProtocolException("recv-response", "expected RESPONSE container, got $type")
        }
        if (txId != expectedTxId) {
            throw ProtocolException("recv-response",
                "transaction id mismatch: expected $expectedTxId, got $txId")
        }
        val paramCount = ((declared - 12).coerceAtLeast(0) / 4).coerceAtMost(5)
        val params = IntArray(paramCount) { header.int }
        return Response(code, params)
    }

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

    class ProtocolException(val stage: String, msg: String) : Exception("[$stage] $msg")

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

        // ── PTP standard device properties (subset) ──────────────────────
        const val PROP_BATTERY_LEVEL = 0x5001  // UINT8 percentage 0-100

        // ── Canon EOS vendor operations (vendor extension ID 11) ─────────
        // Documented in Canon's PTP Reference; Pulsar may use these in Phase 2.
        const val OP_CANON_REMOTE_RELEASE_ON = 0x9128
        const val OP_CANON_REMOTE_RELEASE_OFF = 0x9129
        const val OP_CANON_SET_REMOTE_MODE = 0x9114
        const val OP_CANON_EVENT_MODE = 0x9115

        // ── PTP response codes ───────────────────────────────────────────
        const val RC_OK = 0x2001
        const val RC_SESSION_NOT_OPEN = 0x2003
        const val RC_INVALID_TRANSACTION_ID = 0x2004
        const val RC_OPERATION_NOT_SUPPORTED = 0x2005
        const val RC_DEVICE_BUSY = 0x2019

        // ── Vendor extension IDs ─────────────────────────────────────────
        const val VENDOR_EXT_CANON_EOS = 11L
        const val VENDOR_EXT_MTP = 0xFFFFL

        // ── Timeouts (ms) ────────────────────────────────────────────────
        private const val TIMEOUT_DEFAULT = 3_000
        private const val TIMEOUT_LONG = 10_000
        /** Default buffer size for the first chunk of a data-in read. 4 KB
         *  is enough to swallow most DeviceInfo + standard property reads
         *  in one bulk transfer. */
        private const val BULK_READ_CHUNK = 4096
    }
}
