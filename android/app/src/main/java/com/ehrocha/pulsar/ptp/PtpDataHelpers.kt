/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Copyright (C) 2026 Pulsar Trigger contributors
 */

package com.ehrocha.pulsar.ptp

import java.nio.ByteBuffer
import java.nio.ByteOrder

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

// ── PTP object enumeration (photo-transfer) ─────────────────────────────────
// Pure parsers for the GetStorageIDs / GetObjectHandles / GetObjectInfo
// datasets (PIMA 15740 §5.5.x). Kept here, transport-agnostic and side-effect
// free, so they're unit-tested without a camera — see PtpObjectInfoTest.

/** The subset of a PTP `ObjectInfo` dataset the transfer gallery needs:
 *  format code, full-file (compressed) size, filename, and best-effort
 *  capture time. */
data class PtpObjectInfo(
    val objectFormat: Int,
    val compressedSize: Long,
    val fileName: String,
    val captureEpochMs: Long?,
)

/** Parse a PTP `SimpleArray` of UINT32 — a `uint32 count` followed by that many
 *  little-endian uint32s. Used for GetStorageIDs + GetObjectHandles. Bounded by
 *  the actual buffer, so a bogus count can't over-read or over-allocate. */
internal fun parsePtpU32Array(data: ByteArray): List<Int> {
    val buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
    if (buf.remaining() < 4) return emptyList()
    val declared = buf.int.coerceAtLeast(0)
    val count = minOf(declared, buf.remaining() / 4)
    return List(count) { buf.int }
}

/** Parse the fixed header of a PTP `ObjectInfo` dataset plus the Filename and
 *  CaptureDate strings. Fixed fields run to offset 52 (PIMA 15740 §5.5.3);
 *  the variable strings follow. Tolerant: returns blanks rather than throwing
 *  on a short/odd buffer. */
internal fun parsePtpObjectInfo(data: ByteArray): PtpObjectInfo {
    val buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
    val format = if (buf.limit() >= 6) buf.getShort(4).toInt() and 0xFFFF else 0
    val size = if (buf.limit() >= 12) buf.getInt(8).toLong() and 0xFFFFFFFFL else 0L
    var fileName = ""
    var captureDate = ""
    if (buf.limit() > 52) {
        buf.position(52)
        fileName = readPtpStringAt(buf)
        captureDate = readPtpStringAt(buf)
    }
    return PtpObjectInfo(format, size, fileName, ptpDateToEpochMs(captureDate))
}

/** Read a PTP string at the buffer's current position: 1-byte length (UTF-16
 *  code units incl. trailing NUL), then UTF-16LE. Advances the buffer. */
private fun readPtpStringAt(buf: ByteBuffer): String {
    if (!buf.hasRemaining()) return ""
    val units = buf.get().toInt() and 0xFF
    if (units == 0) return ""
    val byteCount = units * 2
    if (buf.remaining() < byteCount) return ""
    val bytes = ByteArray(byteCount)
    buf.get(bytes)
    return String(bytes, Charsets.UTF_16LE).trimEnd('\u0000', ' ')
}

/** Parse a PTP date-time string ("YYYYMMDDThhmmss", optional ".s"/"Z" suffix)
 *  to epoch millis in the device's local time, or null if unparseable. */
internal fun ptpDateToEpochMs(s: String): Long? {
    val m = Regex("""^(\d{4})(\d{2})(\d{2})T(\d{2})(\d{2})(\d{2})""").find(s) ?: return null
    return runCatching {
        val (y, mo, d, h, mi, se) = m.destructured
        java.util.Calendar.getInstance().apply {
            clear()
            set(y.toInt(), mo.toInt() - 1, d.toInt(), h.toInt(), mi.toInt(), se.toInt())
        }.timeInMillis
    }.getOrNull()
}
