/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Copyright (C) 2026 Pulsar Trigger contributors
 */

package com.ehrocha.pulsar.ptp

import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * USB-bulk implementation of [PtpWire] — PIMA-15740 PTP-over-USB container
 * framing on top of Android's [UsbDeviceConnection.bulkTransfer]. This is the
 * wire `PtpTransport` (USB) has used since its first release; extracted from
 * `PtpClient` so the same client can sit on top of `PtpIpWire` for the
 * Wi-Fi PTP/IP transport.
 *
 * Construction expects the caller to have already opened the USB device,
 * claimed the PTP interface, and located the bulk-in / bulk-out endpoints.
 * [close] is a no-op — the owner of the connection is responsible for
 * `releaseInterface` + `connection.close()`.
 *
 * Not thread-safe. Pulsar serialises every transport call through the
 * camera run loop, so concurrent transactions aren't a concern.
 */
class BulkPtpWire(
    private val connection: UsbDeviceConnection,
    private val bulkIn: UsbEndpoint,
    private val bulkOut: UsbEndpoint,
) : PtpWire {

    private var transactionId: Int = 0

    override suspend fun transact(
        opCode: Int,
        params: IntArray,
        dataOut: ByteArray?,
        expectDataIn: Boolean,
    ): PtpClient.Response {
        val txId = ++transactionId
        sendCommand(opCode, params, txId)
        if (dataOut != null) sendData(opCode, dataOut, txId)
        val data = if (expectDataIn) readData(txId) else null
        return readResponse(txId).copy(data = data)
    }

    override suspend fun transactStream(
        opCode: Int,
        params: IntArray,
        sink: java.io.OutputStream,
        onProgress: (Long, Long) -> Unit,
    ): PtpClient.Response {
        val txId = ++transactionId
        sendCommand(opCode, params, txId)
        streamData(txId, sink, onProgress)
        return readResponse(txId)
    }

    /** Read a DATA container, streaming its payload to [sink] in chunks
     *  instead of buffering it (GetObject — multi-MB). Mirrors [readData]'s
     *  framing; the only difference is the payload destination. */
    private fun streamData(
        expectedTxId: Int,
        sink: java.io.OutputStream,
        onProgress: (Long, Long) -> Unit,
    ) {
        val buf = ByteArray(STREAM_CHUNK)
        val firstLen = connection.bulkTransfer(bulkIn, buf, buf.size, TIMEOUT_LONG)
        if (firstLen < 12) throw PtpProtocolException("recv-object", "short read: $firstLen")
        val header = ByteBuffer.wrap(buf, 0, 12).order(ByteOrder.LITTLE_ENDIAN)
        val declared = header.int.toLong() and 0xFFFFFFFFL
        val type = header.short.toInt() and 0xFFFF
        header.short // opCode echo
        val txId = header.int
        if (type != PtpClient.CONTAINER_DATA) {
            throw PtpProtocolException("recv-object", "expected DATA container, got $type")
        }
        if (txId != expectedTxId) {
            throw PtpProtocolException(
                "recv-object",
                "transaction id mismatch: expected $expectedTxId, got $txId",
            )
        }
        val total = (declared - 12).coerceAtLeast(0)  // payload bytes (no header)
        var written = 0L
        val firstPayload = firstLen - 12
        if (firstPayload > 0) {
            val take = minOf(firstPayload.toLong(), total).toInt()
            sink.write(buf, 12, take)
            written += take
            onProgress(written, total)
        }
        while (written < total) {
            val n = connection.bulkTransfer(bulkIn, buf, buf.size, TIMEOUT_DEFAULT)
            if (n <= 0) break
            val take = minOf(n.toLong(), total - written).toInt()
            sink.write(buf, 0, take)
            written += take
            onProgress(written, total)
        }
        if (written < total) {
            throw PtpProtocolException("recv-object", "truncated: $written / $total bytes")
        }
    }

    override fun close() { /* connection ownership is the transport's */ }

    private fun sendCommand(opCode: Int, params: IntArray, txId: Int) {
        require(params.size <= 5) { "PTP commands take at most 5 parameters" }
        val len = 12 + params.size * 4
        val buf = ByteBuffer.allocate(len).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(len)
            .putShort(PtpClient.CONTAINER_COMMAND.toShort())
            .putShort(opCode.toShort())
            .putInt(txId)
        for (p in params) buf.putInt(p)
        val sent = connection.bulkTransfer(bulkOut, buf.array(), len, TIMEOUT_DEFAULT)
        if (sent != len) {
            throw PtpProtocolException(
                "send-command",
                "bulkTransfer sent $sent / $len for op 0x${"%04X".format(opCode)}",
            )
        }
    }

    private fun sendData(opCode: Int, payload: ByteArray, txId: Int) {
        val len = 12 + payload.size
        val buf = ByteBuffer.allocate(len).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(len)
            .putShort(PtpClient.CONTAINER_DATA.toShort())
            .putShort(opCode.toShort())
            .putInt(txId)
            .put(payload)
        val sent = connection.bulkTransfer(bulkOut, buf.array(), len, TIMEOUT_DEFAULT)
        if (sent != len) {
            throw PtpProtocolException(
                "send-data",
                "bulkTransfer sent $sent / $len for op 0x${"%04X".format(opCode)}",
            )
        }
    }

    /** Read a DATA container. Loops until the declared length has arrived. */
    private fun readData(expectedTxId: Int): ByteArray {
        val first = ByteArray(BULK_READ_CHUNK)
        val firstLen = connection.bulkTransfer(bulkIn, first, first.size, TIMEOUT_LONG)
        if (firstLen < 12) throw PtpProtocolException("recv-data", "short read: $firstLen")
        val header = ByteBuffer.wrap(first, 0, 12).order(ByteOrder.LITTLE_ENDIAN)
        val declared = header.int
        val type = header.short.toInt() and 0xFFFF
        header.short // opCode echo
        val txId = header.int
        if (type != PtpClient.CONTAINER_DATA) {
            throw PtpProtocolException("recv-data", "expected DATA container, got $type")
        }
        if (txId != expectedTxId) {
            throw PtpProtocolException(
                "recv-data",
                "transaction id mismatch: expected $expectedTxId, got $txId",
            )
        }
        val out = ByteArray(ptpReceiveBodyLength(declared.toLong() and 0xFFFFFFFFL, 12, "recv-data"))
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

    private fun readResponse(expectedTxId: Int): PtpClient.Response {
        val buf = ByteArray(64)
        val n = connection.bulkTransfer(bulkIn, buf, buf.size, TIMEOUT_DEFAULT)
        if (n < 12) throw PtpProtocolException("recv-response", "short read: $n")
        val header = ByteBuffer.wrap(buf, 0, n).order(ByteOrder.LITTLE_ENDIAN)
        val declared = header.int
        val type = header.short.toInt() and 0xFFFF
        val code = header.short.toInt() and 0xFFFF
        val txId = header.int
        if (type != PtpClient.CONTAINER_RESPONSE) {
            throw PtpProtocolException("recv-response", "expected RESPONSE container, got $type")
        }
        if (txId != expectedTxId) {
            throw PtpProtocolException(
                "recv-response",
                "transaction id mismatch: expected $expectedTxId, got $txId",
            )
        }
        val paramCount = ((declared - 12).coerceAtLeast(0) / 4).coerceAtMost(5)
        val params = IntArray(paramCount) { header.int }
        return PtpClient.Response(code, params)
    }

    companion object {
        private const val TIMEOUT_DEFAULT = 3_000
        private const val TIMEOUT_LONG = 10_000
        /** First-chunk buffer for a data-in read. 4 KB is enough to swallow
         *  most DeviceInfo + standard property reads in one bulk transfer. */
        private const val BULK_READ_CHUNK = 4096
        /** Chunk buffer for streaming a full image off the card. Bigger than
         *  [BULK_READ_CHUNK] for throughput on multi-MB JPEG/RAW transfers;
         *  bounds the in-memory footprint regardless of file size. */
        private const val STREAM_CHUNK = 64 * 1024
    }
}
