/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Copyright (C) 2026 Pulsar Trigger contributors
 */

package com.ehrocha.pulsar.ptp

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [extractLargestJpeg] pulls the full-resolution embedded JPEG out of a CR3 so
 * the gallery's JPEG-default download works on the PTP wires (which have no
 * CCAPI-style `?kind=display` rendition). The tricky bit is that a preview JPEG
 * embeds a smaller EXIF thumbnail with its own SOI/EOI — a naive "first EOI"
 * scan would return a truncated image, so the extractor walks marker segments
 * by length instead.
 */
class PtpJpegExtractTest {

    private fun b(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    @Test fun picksLargestAndSkipsNestedThumbnailEoi() {
        // Outer JPEG: SOI + APP1(len=6){ embedded thumb SOI+EOI } + EOI.
        // The nested FF D8 / FF D9 sit inside the APP1 segment and must NOT be
        // mistaken for the outer image's bounds.
        val outer = b(
            0xFF, 0xD8,                   // SOI
            0xFF, 0xE1, 0x00, 0x06,       // APP1, length 6 (covers the 2 len bytes + 4 payload)
            0xFF, 0xD8, 0xFF, 0xD9,       // nested EXIF thumbnail (SOI + EOI)
            0xFF, 0xD9,                   // outer EOI
        )
        // A second, smaller standalone JPEG (empty SOI+EOI).
        val small = b(0xFF, 0xD8, 0xFF, 0xD9)

        val blob = b(0x00, 0x00) + small + b(0x00) + outer + b(0x7F)
        val out = extractLargestJpeg(blob)

        assertArrayEquals("must return the full outer JPEG, not the nested thumb", outer, out)
        assertEquals(outer.size, out!!.size)
    }

    @Test fun handlesSosEntropyWithStuffingAndRestart() {
        // SOS header then entropy data containing FF00 (byte-stuffing) and FFD0
        // (restart) which must be stepped over before the real EOI.
        val jpeg = b(
            0xFF, 0xD8,                   // SOI
            0xFF, 0xDA, 0x00, 0x03, 0x00, // SOS, length 3 (1 payload byte)
            0x12, 0xFF, 0x00, 0xFF, 0xD0, 0x34, // entropy: stuffed FF00, restart FFD0
            0xFF, 0xD9,                   // EOI
        )
        assertArrayEquals(jpeg, extractLargestJpeg(b(0x00) + jpeg + b(0x00)))
    }

    @Test fun nullWhenNoCompleteJpeg() {
        assertNull(extractLargestJpeg(b(0x00, 0x01, 0x02, 0x03, 0x04)))
        assertNull(extractLargestJpeg(b(0xFF, 0xD8, 0xFF, 0xE0))) // SOI but never terminates
    }
}
