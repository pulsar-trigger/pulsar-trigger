/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Copyright (C) 2026 Pulsar Trigger contributors
 */

package com.ehrocha.pulsar.ptp

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.os.Build
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.coroutines.resume

/**
 * One-shot USB PTP feasibility probe — enumerates USB devices, finds a Canon
 * PTP-capable camera, opens a session, sends `GetDeviceInfo`, parses the
 * response. The result tells us whether the phone's USB host stack +
 * the camera + Pulsar's PTP code path all play nicely together, which is
 * the gate before committing to a full PTP transport.
 *
 * Spec references:
 *   - PIMA 15740 (PTP base protocol)
 *   - USB Still Image Capture Device Class Spec 1.0 (PTP-over-USB framing)
 *   - Canon EDSDK / PTP extension docs for vendor-specific operations
 *
 * Keep this file self-contained — no Pulsar runtime dependencies, easy to
 * delete or evolve into the real transport once we know it works.
 */
object PtpProbe {

    private const val TAG = "PtpProbe"

    /** PTP interface descriptor: Image class (0x06), subclass 0x01, protocol 0x01. */
    private const val USB_CLASS_PTP = UsbConstants.USB_CLASS_STILL_IMAGE  // = 0x06
    private const val USB_SUBCLASS_PTP = 0x01
    private const val USB_PROTOCOL_PTP = 0x01

    /** Vendor IDs Pulsar might encounter. We probe broadly — most cameras
     *  speak PTP regardless of brand. */
    private val KNOWN_CAMERA_VENDORS = mapOf(
        0x04A9 to "Canon",
        0x04B0 to "Nikon",
        0x054C to "Sony",
        0x04CB to "Fujifilm",
        0x06BB to "Olympus",
        0x04A5 to "Pentax",
    )

    /** PTP transaction container types. */
    private const val PTP_CONTAINER_COMMAND = 1
    private const val PTP_CONTAINER_DATA = 2
    private const val PTP_CONTAINER_RESPONSE = 3

    /** PTP standard operation codes (subset). */
    private const val PTP_OP_GET_DEVICE_INFO = 0x1001
    private const val PTP_OP_OPEN_SESSION = 0x1002
    private const val PTP_OP_CLOSE_SESSION = 0x1003

    /** PTP standard response codes (subset). */
    private const val PTP_RC_OK = 0x2001

    private const val ACTION_USB_PERMISSION = "com.ehrocha.pulsar.USB_PERMISSION"

    /** Result of a probe attempt — either a working PTP device with its
     *  GetDeviceInfo payload, or a structured failure telling us where in
     *  the pipeline it broke. */
    sealed class Result {
        data class Ok(val report: DeviceReport) : Result()
        object NoUsbDevices : Result()
        object NoCameraFound : Result()
        object NoPtpInterface : Result()
        object PermissionDenied : Result()
        data class OpenFailed(val reason: String) : Result()
        data class IoError(val stage: String, val cause: Throwable) : Result()
        data class ProtocolError(val stage: String, val detail: String) : Result()
    }

    /** Human-readable digest of GetDeviceInfo for the result screen. */
    data class DeviceReport(
        val vendorId: Int,
        val productId: Int,
        val vendorName: String,                  // e.g. "Canon"
        val manufacturer: String,                // PTP-reported, e.g. "Canon"
        val model: String,                       // e.g. "Canon EOS R"
        val deviceVersion: String,
        val serialNumber: String,
        val vendorExtensionId: Long,             // 11 = Canon EOS, 6 = Microsoft MTP, etc.
        val supportedOperationsCount: Int,
        val supportsCapture: Boolean,            // op 0x100E
        val supportsTriggerCapture: Boolean,     // op 0x9008 (Canon-specific)
        val supportsBulb: Boolean,               // op 0x9125 / 0x9128 (Canon-specific)
    )

    /** Find any USB device that looks like a camera (known vendor) AND
     *  exposes a PTP interface. Returns null if nothing matches. */
    fun findCameraDevice(usb: UsbManager): UsbDevice? {
        val devices = usb.deviceList
        if (devices.isEmpty()) {
            Log.d(TAG, "No USB devices attached at all")
            return null
        }
        Log.d(TAG, "Enumerating ${devices.size} USB device(s)")
        for ((name, device) in devices) {
            val vendorName = KNOWN_CAMERA_VENDORS[device.vendorId]
            Log.d(TAG, "  $name: vid=${"0x%04X".format(device.vendorId)} " +
                       "pid=${"0x%04X".format(device.productId)} " +
                       "vendor=${vendorName ?: "?"} interfaces=${device.interfaceCount}")
            for (i in 0 until device.interfaceCount) {
                val iface = device.getInterface(i)
                if (iface.interfaceClass == USB_CLASS_PTP &&
                    iface.interfaceSubclass == USB_SUBCLASS_PTP &&
                    iface.interfaceProtocol == USB_PROTOCOL_PTP) {
                    Log.i(TAG, "  -> PTP interface on ${device.deviceName}")
                    return device
                }
            }
        }
        return null
    }

    /** Ask Android for permission to access [device]. Suspends until the user
     *  grants or denies via the system dialog. */
    suspend fun requestPermission(ctx: Context, usb: UsbManager, device: UsbDevice): Boolean {
        if (usb.hasPermission(device)) return true
        return suspendCancellableCoroutine { cont ->
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(c: Context, intent: Intent) {
                    if (intent.action != ACTION_USB_PERMISSION) return
                    val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    try { ctx.unregisterReceiver(this) } catch (_: Throwable) {}
                    if (cont.isActive) cont.resume(granted)
                }
            }
            val filter = IntentFilter(ACTION_USB_PERMISSION)
            // RECEIVER_NOT_EXPORTED added in API 33 — older API just registers normally.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                ctx.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                ctx.registerReceiver(receiver, filter)
            }
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            else
                PendingIntent.FLAG_UPDATE_CURRENT
            val pi = PendingIntent.getBroadcast(
                ctx, 0, Intent(ACTION_USB_PERMISSION).setPackage(ctx.packageName), flags,
            )
            usb.requestPermission(device, pi)
            cont.invokeOnCancellation {
                try { ctx.unregisterReceiver(receiver) } catch (_: Throwable) {}
            }
        }
    }

    /** Open the PTP interface, send `GetDeviceInfo`, return the parsed result.
     *  Closes the connection on the way out. Runs on IO dispatcher. */
    suspend fun probe(ctx: Context, device: UsbDevice): Result = withContext(Dispatchers.IO) {
        val usb = ctx.getSystemService(Context.USB_SERVICE) as UsbManager
        if (!usb.hasPermission(device)) return@withContext Result.PermissionDenied
        // Find the PTP interface + its bulk-in / bulk-out endpoints.
        val iface = (0 until device.interfaceCount).map(device::getInterface).firstOrNull {
            it.interfaceClass == USB_CLASS_PTP &&
            it.interfaceSubclass == USB_SUBCLASS_PTP &&
            it.interfaceProtocol == USB_PROTOCOL_PTP
        } ?: return@withContext Result.NoPtpInterface
        var bulkIn: UsbEndpoint? = null
        var bulkOut: UsbEndpoint? = null
        for (i in 0 until iface.endpointCount) {
            val ep = iface.getEndpoint(i)
            if (ep.type != UsbConstants.USB_ENDPOINT_XFER_BULK) continue
            if (ep.direction == UsbConstants.USB_DIR_IN) bulkIn = ep
            else bulkOut = ep
        }
        if (bulkIn == null || bulkOut == null) {
            return@withContext Result.OpenFailed("PTP interface missing bulk endpoints")
        }
        val connection = usb.openDevice(device)
            ?: return@withContext Result.OpenFailed("usb.openDevice returned null")
        try {
            if (!connection.claimInterface(iface, true)) {
                return@withContext Result.OpenFailed("claimInterface failed")
            }
            val info = doGetDeviceInfo(connection, bulkIn, bulkOut)
            val vendorName = KNOWN_CAMERA_VENDORS[device.vendorId] ?: "Unknown"
            Result.Ok(buildReport(device, vendorName, info))
        } catch (e: ProtocolException) {
            Result.ProtocolError(e.stage, e.message ?: "unknown")
        } catch (e: Throwable) {
            Result.IoError("getDeviceInfo", e)
        } finally {
            try { connection.releaseInterface(iface) } catch (_: Throwable) {}
            try { connection.close() } catch (_: Throwable) {}
        }
    }

    // ── PTP-over-USB framing ─────────────────────────────────────────────

    private class ProtocolException(val stage: String, msg: String) : Exception(msg)

    private fun doGetDeviceInfo(
        conn: UsbDeviceConnection,
        bulkIn: UsbEndpoint,
        bulkOut: UsbEndpoint,
    ): DeviceInfoRaw {
        val transactionId = 0
        // Command block: 12-byte header (no params for GetDeviceInfo).
        val cmd = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(12)                              // length
            .putShort(PTP_CONTAINER_COMMAND.toShort())
            .putShort(PTP_OP_GET_DEVICE_INFO.toShort())
            .putInt(transactionId)
            .array()
        val sent = conn.bulkTransfer(bulkOut, cmd, cmd.size, 2000)
        if (sent != cmd.size) {
            throw ProtocolException("send-command", "bulkTransfer sent $sent / ${cmd.size}")
        }
        // Data block: header + DeviceInfo dataset. The dataset can be up to a
        // few KB on modern bodies (lots of supported ops). 4 KB buffer is plenty.
        val dataBuf = ByteArray(4096)
        val dataLen = conn.bulkTransfer(bulkIn, dataBuf, dataBuf.size, 5000)
        if (dataLen < 12) {
            throw ProtocolException("recv-data", "short read: $dataLen")
        }
        val dataHeader = ByteBuffer.wrap(dataBuf, 0, 12).order(ByteOrder.LITTLE_ENDIAN)
        val declaredLen = dataHeader.int                  // total bytes including header
        val containerType = dataHeader.short.toInt() and 0xFFFF
        if (containerType != PTP_CONTAINER_DATA) {
            throw ProtocolException("recv-data", "expected DATA container, got $containerType")
        }
        // Some bodies send the data block in multiple bulk reads. Loop until we
        // have `declaredLen` bytes or hit a short packet.
        val collected = mutableListOf<Byte>()
        collected.addAll(dataBuf.slice(12 until dataLen))
        var totalSeen = dataLen
        while (totalSeen < declaredLen) {
            val more = conn.bulkTransfer(bulkIn, dataBuf, dataBuf.size, 2000)
            if (more <= 0) break
            collected.addAll(dataBuf.slice(0 until more))
            totalSeen += more
        }
        // Response block: 12 bytes (no params expected for GetDeviceInfo).
        val respBuf = ByteArray(64)
        val respLen = conn.bulkTransfer(bulkIn, respBuf, respBuf.size, 2000)
        if (respLen < 12) {
            throw ProtocolException("recv-response", "short read: $respLen")
        }
        val respHeader = ByteBuffer.wrap(respBuf, 0, 12).order(ByteOrder.LITTLE_ENDIAN)
        respHeader.int                                     // length
        val respType = respHeader.short.toInt() and 0xFFFF
        val respCode = respHeader.short.toInt() and 0xFFFF
        if (respType != PTP_CONTAINER_RESPONSE) {
            throw ProtocolException("recv-response", "expected RESPONSE container, got $respType")
        }
        if (respCode != PTP_RC_OK) {
            throw ProtocolException("recv-response", "response code 0x${"%04X".format(respCode)}")
        }
        return parseDeviceInfoDataset(collected.toByteArray())
    }

    // ── DeviceInfo dataset parser (PIMA 15740 §5.5.1) ────────────────────

    /** Raw dataset values we care about for the probe. */
    private data class DeviceInfoRaw(
        val vendorExtensionId: Long,
        val supportedOps: List<Int>,
        val manufacturer: String,
        val model: String,
        val deviceVersion: String,
        val serialNumber: String,
    )

    private fun parseDeviceInfoDataset(data: ByteArray): DeviceInfoRaw {
        val buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        buf.short                                                          // StandardVersion
        val vendorExtensionId = buf.int.toLong() and 0xFFFFFFFFL
        buf.short                                                          // VendorExtensionVersion
        readPtpString(buf)                                                 // VendorExtensionDesc
        buf.short                                                          // FunctionalMode
        val supportedOps = readU16Array(buf)
        readU16Array(buf)                                                  // Events
        readU16Array(buf)                                                  // DeviceProperties
        readU16Array(buf)                                                  // CaptureFormats
        readU16Array(buf)                                                  // ImageFormats
        val manufacturer = readPtpString(buf)
        val model = readPtpString(buf)
        val deviceVersion = readPtpString(buf)
        val serialNumber = readPtpString(buf)
        return DeviceInfoRaw(vendorExtensionId, supportedOps, manufacturer,
                             model, deviceVersion, serialNumber)
    }

    /** PTP string: 1-byte length (count of UTF-16 code units, including
     *  trailing NUL), then UTF-16LE code units. Length=0 means absent. */
    private fun readPtpString(buf: ByteBuffer): String {
        val units = buf.get().toInt() and 0xFF
        if (units == 0) return ""
        val bytes = ByteArray(units * 2)
        buf.get(bytes)
        // Trim trailing NUL char.
        val str = String(bytes, Charsets.UTF_16LE)
        return if (str.isNotEmpty() && str.last() == ' ') str.dropLast(1) else str
    }

    private fun readU16Array(buf: ByteBuffer): List<Int> {
        val n = buf.int
        val out = ArrayList<Int>(n.coerceAtMost(1024))  // sanity cap
        for (i in 0 until n) out.add(buf.short.toInt() and 0xFFFF)
        return out
    }

    private fun buildReport(device: UsbDevice, vendor: String, info: DeviceInfoRaw) = DeviceReport(
        vendorId = device.vendorId,
        productId = device.productId,
        vendorName = vendor,
        manufacturer = info.manufacturer,
        model = info.model,
        deviceVersion = info.deviceVersion,
        serialNumber = info.serialNumber,
        vendorExtensionId = info.vendorExtensionId,
        supportedOperationsCount = info.supportedOps.size,
        supportsCapture = 0x100E in info.supportedOps,
        // Canon-specific operations (vendor extension 11):
        supportsTriggerCapture = 0x9008 in info.supportedOps,
        supportsBulb = 0x9125 in info.supportedOps || 0x9128 in info.supportedOps,
    )
}
