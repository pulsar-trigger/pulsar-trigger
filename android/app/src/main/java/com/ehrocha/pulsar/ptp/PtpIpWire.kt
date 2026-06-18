/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Copyright (C) 2026 Pulsar Trigger contributors
 */

package com.ehrocha.pulsar.ptp

import android.util.Log
import com.ehrocha.pulsar.canonble.CanonBleLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.DataInputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

/**
 * PTP/IP implementation of [PtpWire] — ISO-15740 PTP-over-TCP-IP (the protocol
 * Canon EOS bodies expose on their "Remote Control (EOS Utility)" Wi-Fi mode,
 * port 15740). Sister of [BulkPtpWire]; both feed the same [PtpClient].
 *
 * **Handshake** ([connect]): opens two TCP sockets, sends `Init Command
 * Request` on the first (with a 16-byte GUID + UTF-16 friendly name) and waits
 * for `Init Command Ack` — at this point the camera shows a per-device
 * "allow this connection?" prompt that the user must confirm on the body.
 * Then opens the event channel and sends `Init Event Request` carrying the
 * connection number the camera assigned. Both sockets stay open for the life
 * of the session.
 *
 * **Operations** ([transact]): commands ride the command socket only.
 *  - Command: `OP_REQUEST` with `dataPhase + opcode + txid + params`.
 *  - Optional data-out: `START_DATA` (declared total length) + `END_DATA`
 *    (the payload).
 *  - Data-in: arrives as `START_DATA` (declared length) + zero or more
 *    `DATA` packets + a final `END_DATA`.
 *  - Response: `OP_RESPONSE` with `rc + txid + 0–5 params`.
 *  - Events on the event socket are read by a separate consumer (TBD in
 *    Phase 4 for live view); here they're tolerated and ignored.
 *
 * Not thread-safe; Pulsar serialises transport calls through the camera run
 * loop. Camera-side TCP read timeouts are generous because user confirmation
 * on the body can take seconds.
 */
class PtpIpWire private constructor(
    private val cmdSocket: Socket,
    private val evtSocket: Socket,
    /** Connection number the camera assigned in `Init Command Ack`.
     *  Forwarded back in `Init Event Request`; useful for diagnostics. */
    val connectionNumber: Int,
    /** Identity the camera reported in `Init Command Ack` — friendly name
     *  (e.g. "MonsteR") + body GUID. Pulsar surfaces the name as the
     *  transport label. */
    val responderName: String,
    val responderGuidHex: String,
) : PtpWire {

    private val cmdIn = DataInputStream(cmdSocket.getInputStream())
    private val cmdOut = cmdSocket.getOutputStream()
    private var transactionId: Int = 0

    override suspend fun transact(
        opCode: Int,
        params: IntArray,
        dataOut: ByteArray?,
        expectDataIn: Boolean,
    ): PtpClient.Response = withContext(Dispatchers.IO) {
        require(params.size <= 5) { "PTP commands take at most 5 parameters" }
        val txId = ++transactionId
        val dph = if (dataOut != null) DPH_DATA_OUT else DPH_NO_DATA_OR_IN

        // Op-request body: dataPhase(4) + opcode(2) + txid(4) + params(N*4)
        val reqBody = ByteBuffer
            .allocate(10 + params.size * 4)
            .order(ByteOrder.LITTLE_ENDIAN)
            .putInt(dph)
            .putShort(opCode.toShort())
            .putInt(txId)
            .also { for (p in params) it.putInt(p) }
            .array()
        sendPacket(PKT_OP_REQUEST, reqBody)

        if (dataOut != null) {
            // Two-packet data-out: START_DATA carries the declared total
            // length, END_DATA carries the actual bytes. Pulsar's writes
            // are all small (single property set) so we don't chunk.
            sendPacket(
                PKT_START_DATA,
                ByteBuffer.allocate(12)
                    .order(ByteOrder.LITTLE_ENDIAN)
                    .putInt(txId)
                    .putLong(dataOut.size.toLong())
                    .array(),
            )
            sendPacket(
                PKT_END_DATA,
                ByteBuffer.allocate(4 + dataOut.size)
                    .order(ByteOrder.LITTLE_ENDIAN)
                    .putInt(txId)
                    .put(dataOut)
                    .array(),
            )
        }

        // Read until we land on OP_RESPONSE. Tolerate (and accumulate)
        // interleaved data packets and ignore stray events on the cmd socket.
        var data: ByteArray? = if (expectDataIn) ByteArray(0) else null
        while (true) {
            val (ptype, payload) = recvPacket()
            when (ptype) {
                PKT_START_DATA -> Unit // txid(4) + totalLen(8); accumulate via DATA/END_DATA
                PKT_DATA -> if (data != null) data += payload.copyOfRange(4, payload.size)
                PKT_END_DATA -> if (data != null) data += payload.copyOfRange(4, payload.size)
                PKT_OP_RESPONSE -> {
                    if (payload.size < 6) {
                        throw PtpProtocolException(
                            "recv-response",
                            "short response payload: ${payload.size}",
                        )
                    }
                    val rcBuf = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)
                    val rc = rcBuf.short.toInt() and 0xFFFF
                    val gotTxId = rcBuf.int
                    if (gotTxId != txId) {
                        throw PtpProtocolException(
                            "recv-response",
                            "transaction id mismatch: expected $txId, got $gotTxId",
                        )
                    }
                    val paramCount = ((payload.size - 6) / 4).coerceIn(0, 5)
                    val rparams = IntArray(paramCount) { rcBuf.int }
                    CanonBleLog.d(TAG, "op 0x${"%04X".format(opCode)} " +
                        "rc=0x${"%04X".format(rc)} params=${rparams.toList()} " +
                        "data=${data?.size ?: 0}B")
                    return@withContext PtpClient.Response(rc, rparams, data)
                }
                PKT_EVENT -> Unit // tolerate strays on the cmd channel
                else -> Log.w(TAG, "transact: unexpected packet type $ptype, ignoring")
            }
        }
        @Suppress("UNREACHABLE_CODE")
        throw PtpProtocolException("recv-response", "exited transact loop without OP_RESPONSE")
    }

    override fun close() {
        runCatching { cmdSocket.close() }
        runCatching { evtSocket.close() }
    }

    // ── Packet framing ──────────────────────────────────────────────────

    private fun sendPacket(ptype: Int, payload: ByteArray) {
        val total = 8 + payload.size
        val hdr = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(total).putInt(ptype).array()
        cmdOut.write(hdr)
        if (payload.isNotEmpty()) cmdOut.write(payload)
        cmdOut.flush()
    }

    private fun recvPacket(): Pair<Int, ByteArray> {
        val length = readU32LE(cmdIn).toLong() and 0xFFFFFFFFL
        val ptype = readU32LE(cmdIn)
        val bodyLen = ptpReceiveBodyLength(length, 8, "recv-packet")
        val body = if (bodyLen > 0) {
            val out = ByteArray(bodyLen)
            cmdIn.readFully(out)
            out
        } else ByteArray(0)
        return ptype to body
    }

    companion object {
        private const val TAG = "PtpIpWire"

        /** Standard PTP/IP TCP port (PIMA 15740). */
        const val PTPIP_PORT = 15740

        /** PTP/IP packet types — ISO 15740. */
        private const val PKT_INIT_CMD_REQ = 1
        private const val PKT_INIT_CMD_ACK = 2
        private const val PKT_INIT_EVENT_REQ = 3
        private const val PKT_INIT_EVENT_ACK = 4
        private const val PKT_INIT_FAIL = 5
        private const val PKT_OP_REQUEST = 6
        private const val PKT_OP_RESPONSE = 7
        private const val PKT_EVENT = 8
        private const val PKT_START_DATA = 9
        private const val PKT_DATA = 10
        private const val PKT_END_DATA = 12

        /** Data-phase info for [PKT_OP_REQUEST]. */
        private const val DPH_NO_DATA_OR_IN = 1
        private const val DPH_DATA_OUT = 2

        /** Protocol version negotiated in Init Command Request. 1.0. */
        private const val PROTOCOL_VERSION = 0x00010000

        /** Open both PTP/IP sockets and run the four-message init
         *  handshake. The camera shows a "Connect this device?" prompt on
         *  the first ever [Init Command Request] from a new GUID — the
         *  caller's [onAwaitConfirm] callback fires so the UI can tell the
         *  user to look at the body. Persist [clientGuid] across runs so
         *  reconnects don't re-prompt. */
        suspend fun connect(
            host: String,
            port: Int = PTPIP_PORT,
            clientName: String = "Pulsar",
            clientGuid: UUID = UUID.randomUUID(),
            onAwaitConfirm: () -> Unit = {},
            connectTimeoutMs: Int = 10_000,
            confirmTimeoutMs: Int = 60_000,
        ): PtpIpConnectResult = withContext(Dispatchers.IO) {
            val cmd = Socket()
            val evt = Socket()
            try {
                cmd.connect(InetSocketAddress(host, port), connectTimeoutMs)
                cmd.soTimeout = confirmTimeoutMs   // user confirmation can take a while
                CanonBleLog.i(TAG, "command channel up to $host:$port")

                // Init Command Request: guid(16) + utf16(name) + version(4)
                val nameBytes = utf16(clientName)
                val req = ByteBuffer
                    .allocate(16 + nameBytes.size + 4)
                    .order(ByteOrder.LITTLE_ENDIAN)
                    .put(guidBytes(clientGuid))
                    .put(nameBytes)
                    .putInt(PROTOCOL_VERSION)
                    .array()
                sendPacketRaw(cmd, PKT_INIT_CMD_REQ, req)
                onAwaitConfirm()

                val (ackType, ackBody) = recvPacketRaw(cmd)
                when (ackType) {
                    PKT_INIT_FAIL -> {
                        val reason = if (ackBody.size >= 4)
                            ByteBuffer.wrap(ackBody).order(ByteOrder.LITTLE_ENDIAN).int
                        else -1
                        return@withContext PtpIpConnectResult.Rejected(
                            "INIT_FAIL reason=0x${"%08X".format(reason)}",
                        )
                    }
                    PKT_INIT_CMD_ACK -> Unit
                    else -> return@withContext PtpIpConnectResult.Failed(
                        "unexpected packet type $ackType (expected INIT_CMD_ACK)",
                    )
                }
                if (ackBody.size < 20) {
                    return@withContext PtpIpConnectResult.Failed(
                        "INIT_CMD_ACK short body: ${ackBody.size}",
                    )
                }
                val connNo = ByteBuffer.wrap(ackBody).order(ByteOrder.LITTLE_ENDIAN).int
                val respGuid = ackBody.copyOfRange(4, 20).joinToString("") { "%02x".format(it) }
                val respName = readUtf16Z(ackBody, 20).first
                CanonBleLog.i(TAG, "Init Cmd Ack: conn=$connNo guid=$respGuid name='$respName'")

                // Event channel — same host:port, second socket.
                evt.connect(InetSocketAddress(host, port), connectTimeoutMs)
                evt.soTimeout = confirmTimeoutMs
                val evtReq = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
                    .putInt(connNo).array()
                sendPacketRaw(evt, PKT_INIT_EVENT_REQ, evtReq)
                val (evtAck, _) = recvPacketRaw(evt)
                if (evtAck != PKT_INIT_EVENT_ACK) {
                    return@withContext PtpIpConnectResult.Failed(
                        "event-channel init: type $evtAck (expected INIT_EVENT_ACK)",
                    )
                }
                CanonBleLog.i(TAG, "Init Event Ack — PTP/IP link up")

                // Drop the camera-confirmation timeout to a working session timeout.
                cmd.soTimeout = SESSION_READ_TIMEOUT_MS
                evt.soTimeout = SESSION_READ_TIMEOUT_MS

                PtpIpConnectResult.Ok(PtpIpWire(cmd, evt, connNo, respName, respGuid))
            } catch (t: Throwable) {
                runCatching { cmd.close() }
                runCatching { evt.close() }
                Log.w(TAG, "connect failed: ${t.message}", t)
                PtpIpConnectResult.Failed(t.message ?: t.javaClass.simpleName)
            }
        }

        /** Working-session read timeout. Generous: live view reads can take
         *  hundreds of ms, GetEvent polling can take seconds. */
        private const val SESSION_READ_TIMEOUT_MS = 15_000

        // ── Wire helpers (used by [connect] only — [transact] uses the
        //    instance-bound cmdIn/cmdOut for performance + thread safety). ──

        private fun sendPacketRaw(sock: Socket, ptype: Int, payload: ByteArray) {
            val out = sock.getOutputStream()
            val total = 8 + payload.size
            val hdr = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
                .putInt(total).putInt(ptype).array()
            out.write(hdr)
            if (payload.isNotEmpty()) out.write(payload)
            out.flush()
        }

        private fun recvPacketRaw(sock: Socket): Pair<Int, ByteArray> {
            val input = DataInputStream(sock.getInputStream())
            val length = readU32LE(input).toLong() and 0xFFFFFFFFL
            val ptype = readU32LE(input)
            val bodyLen = ptpReceiveBodyLength(length, 8, "recv-packet")
            val body = if (bodyLen > 0) {
                val b = ByteArray(bodyLen)
                input.readFully(b)
                b
            } else ByteArray(0)
            return ptype to body
        }

        private fun readU32LE(input: DataInputStream): Int {
            val a = input.readUnsignedByte()
            val b = input.readUnsignedByte()
            val c = input.readUnsignedByte()
            val d = input.readUnsignedByte()
            return (a) or (b shl 8) or (c shl 16) or (d shl 24)
        }

        /** UUID → 16 bytes (big-endian per RFC 4122; PTP/IP doesn't care about
         *  byte order in the GUID — it's an opaque identifier for the camera
         *  to remember our device). */
        private fun guidBytes(uuid: UUID): ByteArray {
            val buf = ByteBuffer.allocate(16).order(ByteOrder.BIG_ENDIAN)
            buf.putLong(uuid.mostSignificantBits)
            buf.putLong(uuid.leastSignificantBits)
            return buf.array()
        }

        /** PTP/IP friendly-name: UTF-16LE, NUL-terminated. */
        private fun utf16(s: String): ByteArray = s.toByteArray(Charsets.UTF_16LE) + byteArrayOf(0, 0)

        /** Read a NUL-terminated UTF-16LE string from [buf] starting at [off];
         *  returns (decoded, nextOff). */
        private fun readUtf16Z(buf: ByteArray, off: Int): Pair<String, Int> {
            var end = off
            while (end + 1 < buf.size && !(buf[end] == 0.toByte() && buf[end + 1] == 0.toByte())) {
                end += 2
            }
            val s = String(buf, off, end - off, Charsets.UTF_16LE)
            return s to (end + 2)
        }
    }
}

/** Outcome of [PtpIpWire.connect]. Distinguishes user-rejection (camera said
 *  "deny" on its confirm prompt) from generic failure so the UI can guide the
 *  user differently. */
sealed interface PtpIpConnectResult {
    data class Ok(val wire: PtpIpWire) : PtpIpConnectResult
    data class Failed(val reason: String) : PtpIpConnectResult
    data class Rejected(val reason: String) : PtpIpConnectResult
}
