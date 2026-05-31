/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Copyright (C) 2026 Pulsar Trigger contributors
 */

package com.ehrocha.pulsar.ptp

/**
 * The wire underneath [PtpClient]. A PTP transaction has the same shape on
 * every transport (request → optional data phase → response with code + 0-5
 * uint32 params); only the framing differs:
 *
 *  - **USB-bulk** ([BulkPtpWire]): PIMA-15740 container — one bulk transfer
 *    each for COMMAND, optional DATA, RESPONSE; each container is
 *    `length(4) + type(2) + opcode(2) + txid(4) + params…`.
 *  - **PTP/IP** ([PtpIpWire]): ISO-15740 over TCP — each packet wrapped in
 *    `length(4) + ptype(4)` outer envelope, with separate packet types for
 *    `OP_REQUEST`, `START_DATA` / `DATA` / `END_DATA`, and `OP_RESPONSE`.
 *
 *  `PtpClient` doesn't care which one it's talking to. Canon EOS bodies
 *  speak the same vendor op set over USB cable and Wi-Fi PTP/IP — that's
 *  the whole point of the abstraction.
 */
interface PtpWire {

    /**
     * Run one PTP transaction end-to-end. Returns the response code, any
     * uint32 params the responder echoes back, and (for `expectDataIn`
     * transactions) the dataset payload.
     *
     * Implementations own their own transaction-ID counter — every call
     * gets a fresh monotonic txid. Thread-safety is the caller's job;
     * Pulsar serialises transport calls through the run loop today.
     */
    suspend fun transact(
        opCode: Int,
        params: IntArray = IntArray(0),
        dataOut: ByteArray? = null,
        expectDataIn: Boolean = false,
    ): PtpClient.Response

    /** Release any underlying resources. Idempotent. After [close] the wire
     *  is not reusable — discard it. The owner of the underlying transport
     *  handle (USB connection, TCP sockets) is responsible for the actual
     *  resource release; this is for any wire-level state. */
    fun close()
}

/** Wire-level failure — short read, container-type mismatch, transaction-ID
 *  mismatch, etc. Thrown by [PtpWire] implementations. The caller (PtpClient)
 *  surfaces these as transport errors. */
class PtpProtocolException(
    val stage: String,
    msg: String,
) : Exception("[$stage] $msg")
