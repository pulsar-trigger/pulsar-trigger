/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Copyright (C) 2026 Pulsar Trigger contributors
 */

package com.ehrocha.pulsar.ptp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Bounds checks for [ptpReceiveBodyLength] — the guard the USB and PTP/IP
 * wires run a device-declared length through before allocating a receive
 * buffer. The declared value is attacker-controllable on PTP/IP, so the guard
 * is what stops a crafted length from driving a NegativeArraySize / OOM crash
 * (audit 2026-06-18 H1).
 */
class PtpReceiveBodyLengthTest {

    @Test
    fun `normal length yields declared minus header`() {
        assertEquals(192, ptpReceiveBodyLength(200L, 8, "t"))
        assertEquals(188, ptpReceiveBodyLength(200L, 12, "t"))
    }

    @Test
    fun `exactly header bytes yields empty body`() {
        assertEquals(0, ptpReceiveBodyLength(8L, 8, "t"))
        assertEquals(0, ptpReceiveBodyLength(12L, 12, "t"))
    }

    @Test
    fun `length below header is rejected`() {
        assertThrows(PtpProtocolException::class.java) {
            ptpReceiveBodyLength(4L, 8, "t")
        }
        assertThrows(PtpProtocolException::class.java) {
            ptpReceiveBodyLength(0L, 12, "t")
        }
    }

    @Test
    fun `payload exactly at the cap is allowed`() {
        val raw = MAX_PTP_DATA_BYTES + 8
        assertEquals(MAX_PTP_DATA_BYTES.toInt(), ptpReceiveBodyLength(raw, 8, "t"))
    }

    @Test
    fun `payload over the cap is rejected`() {
        assertThrows(PtpProtocolException::class.java) {
            ptpReceiveBodyLength(MAX_PTP_DATA_BYTES + 9, 8, "t")
        }
    }

    @Test
    fun `hostile high-bit u32 lengths are rejected, not allocated`() {
        // The exact values that, pre-fix, sized a multi-hundred-MB / ~2 GB
        // ByteArray straight off the wire.
        val oneGig = 0x40000000L          // positive Int → ~1 GB alloc pre-fix
        val maxU32 = 0xFFFFFFFFL           // full unsigned 32-bit
        val highBit = 0x7FFFFFFFL
        for (raw in listOf(oneGig, maxU32, highBit)) {
            assertThrows(PtpProtocolException::class.java) {
                ptpReceiveBodyLength(raw, 8, "t")
            }
        }
    }
}
