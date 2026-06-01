/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Copyright (C) 2026 Pulsar Trigger contributors
 */

package com.ehrocha.pulsar.ptp

/** Decode a PTP STR-typed property payload: 1-byte length (UTF-16 code
 *  units incl trailing NUL), then UTF-16LE. Returns null on a malformed
 *  buffer. Used by lens-name and other string property reads. */
internal fun decodePtpString(data: ByteArray): String? {
    if (data.isEmpty()) return null
    val units = data[0].toInt() and 0xFF
    if (units == 0) return ""
    val byteCount = units * 2
    if (data.size < 1 + byteCount) return null
    val s = String(data, 1, byteCount, Charsets.UTF_16LE)
    return if (s.isNotEmpty() && s.last() == ' ') s.dropLast(1) else s
}

/** Find the JPEG payload inside Canon's GetViewFinderData wrapper.
 *  Body-specific TLV headers precede the frame; we don't parse them —
 *  we scan for the JPEG SOI (`0xFFD8`) and EOI (`0xFFD9`) markers.
 *  Robust enough for the Star Focus wizard which only needs a
 *  decodable JPEG. */
internal fun extractJpeg(buf: ByteArray): ByteArray? {
    val start = indexOfJpegSoi(buf) ?: return null
    val end = indexOfJpegEoi(buf, start) ?: return null
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
