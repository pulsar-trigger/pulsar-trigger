/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Copyright (C) 2026 Pulsar Trigger contributors
 */

package com.ehrocha.pulsar.ptp

import com.ehrocha.pulsar.canonble.CanonBleLog
import com.ehrocha.pulsar.transport.CameraImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.OutputStream

/**
 * PTP image enumeration / transfer, shared by [PtpTransport] (USB) and
 * [PtpIpTransport] (Wi-Fi) — they wrap the same [PtpClient] and differ only at
 * the wire, so the photo-transfer logic lives here once.
 *
 * Requires an open session (both transports `OpenSession` at connect).
 *
 * Logs each enumeration step through [CanonBleLog] so the in-app diagnostics
 * dump shows where transfer breaks on a given body (these standard PTP object
 * ops aren't guaranteed on a Canon EOS in PC-remote mode — see the per-storage
 * fallback below).
 */
private const val TAG = "PtpContent"

/** PTP object-format code for an Association (folder). */
private const val FORMAT_ASSOCIATION = 0x3001
/** Safety cap on objects walked, so a pathological card can't hang the UI. */
private const val MAX_OBJECTS = 20_000

/** Enumerate every still image on the card as [CameraImage]s, newest-first.
 *  `GetObjectHandles(parent=0xFFFFFFFF)` returns only the store **root** (the
 *  DCIM / MISC folders), not a flat list — so we walk the association tree:
 *  start at the root, recurse into every folder (format 0x3001), and collect
 *  the image leaves. One `GetObjectInfo` per object; returns empty on a wire
 *  error. */
internal suspend fun PtpClient.listImageContents(): List<CameraImage> =
    withContext(Dispatchers.IO) {
        runCatching {
            val roots = getObjectHandles()  // STORAGE_ALL, fmt 0, root (0xFFFFFFFF)
            CanonBleLog.i(TAG, "roots -> ${roots.size}")
            val images = mutableListOf<CameraImage>()
            val visited = HashSet<Int>()
            val queue = ArrayDeque(roots)
            var folders = 0
            while (queue.isNotEmpty() && images.size < MAX_OBJECTS) {
                val handle = queue.removeFirst()
                if (!visited.add(handle)) continue
                val info = getObjectInfo(handle) ?: continue
                when {
                    info.objectFormat == FORMAT_ASSOCIATION -> {
                        folders++
                        queue.addAll(getObjectHandles(parent = handle))  // children of this folder
                    }
                    CameraImage.isImageName(info.fileName) -> images += CameraImage(
                        id = handle.toString(),
                        fileName = info.fileName,
                        byteSize = info.compressedSize,
                        isRaw = CameraImage.isRawName(info.fileName),
                        capturedEpochMs = info.captureEpochMs,
                    )
                }
            }
            CanonBleLog.i(TAG, "listContents -> ${images.size} images ($folders folders walked)")
            images.sortedByDescending { it.capturedEpochMs ?: 0L }
        }.getOrElse {
            CanonBleLog.w(TAG, "listContents failed: ${it.javaClass.simpleName}: ${it.message}")
            emptyList()
        }
    }

/** Embedded thumbnail JPEG for [image], or null on failure. */
internal suspend fun PtpClient.thumbnailFor(image: CameraImage): ByteArray? =
    withContext(Dispatchers.IO) {
        val handle = image.id.toIntOrNull() ?: return@withContext null
        val bytes = runCatching { getThumb(handle) }.getOrNull()
        if (bytes == null) CanonBleLog.d(TAG, "getThumb(${image.fileName}) -> null")
        bytes
    }

/** Stream the full file for [image] into [sink]. Returns true on success. */
internal suspend fun PtpClient.downloadObject(
    image: CameraImage,
    sink: OutputStream,
    onProgress: (Long, Long) -> Unit,
): Boolean = withContext(Dispatchers.IO) {
    val handle = image.id.toIntOrNull() ?: return@withContext false
    val ok = runCatching { getObject(handle, sink, onProgress) }.getOrDefault(false)
    if (!ok) CanonBleLog.w(TAG, "getObject(${image.fileName}) failed")
    ok
}

/** Largest RAW we'll buffer in memory to pull a preview from — guards the heap
 *  against a pathological file (real CR3s are ~20-60 MB). */
private const val MAX_RAW_BUFFER = 120 * 1024 * 1024

/** Stream a JPEG rendition of a RAW [image] by downloading the file and writing
 *  its largest embedded JPEG preview into [sink]. Returns false (writing
 *  nothing) when the file is too large to buffer or carries no embedded JPEG —
 *  the caller then fails the item rather than leaving RAW bytes in a `.JPG`.
 *  PTP has no display-rendition op (unlike CCAPI's `?kind=display`), so this is
 *  the embedded preview the camera already wrote into the CR3. */
internal suspend fun PtpClient.downloadObjectAsJpeg(
    image: CameraImage,
    sink: OutputStream,
    onProgress: (Long, Long) -> Unit,
): Boolean = withContext(Dispatchers.IO) {
    val handle = image.id.toIntOrNull() ?: return@withContext false
    val hint = image.byteSize.takeIf { it in 1..MAX_RAW_BUFFER.toLong() }?.toInt() ?: (8 * 1024 * 1024)
    val buf = ByteArrayOutputStream(hint)
    val ok = runCatching { getObject(handle, buf, onProgress) }.getOrDefault(false)
    if (!ok) { CanonBleLog.w(TAG, "getObject(${image.fileName}) for JPEG failed"); return@withContext false }
    val jpeg = extractLargestJpeg(buf.toByteArray())
    if (jpeg == null) {
        CanonBleLog.w(TAG, "no embedded JPEG found in ${image.fileName}")
        return@withContext false
    }
    sink.write(jpeg)
    sink.flush()
    CanonBleLog.d(TAG, "extracted ${jpeg.size}B JPEG from ${image.fileName}")
    true
}

/** Scan a CR3/RAW byte array for embedded JPEG images (SOI…EOI) and return the
 *  largest complete one — the full-resolution preview — or null if none parses.
 *  Walks JPEG marker segments by their declared length so an EXIF thumbnail
 *  nested in an APP1 segment can't end the outer image early. */
internal fun extractLargestJpeg(data: ByteArray): ByteArray? {
    val n = data.size
    var bestStart = -1
    var bestLen = 0
    var i = 0
    while (i + 2 < n) {
        if (data[i] == 0xFF.toByte() && data[i + 1] == 0xD8.toByte() && data[i + 2] == 0xFF.toByte()) {
            val len = jpegLengthAt(data, i)
            if (len > bestLen) { bestLen = len; bestStart = i }
            if (len > 0) { i += len; continue }  // skip past this image (incl. its APP thumbnails)
        }
        i++
    }
    return if (bestStart >= 0) data.copyOfRange(bestStart, bestStart + bestLen) else null
}

/** Byte length of the complete JPEG starting at [start] (an SOI), or -1 if it
 *  doesn't terminate within [data]. Skips marker segments by their declared
 *  length and steps over SOS entropy-coded data (honouring 0xFF byte-stuffing
 *  and restart markers), so the matching EOI is found even when APPn segments
 *  embed their own JPEG thumbnails. */
private fun jpegLengthAt(data: ByteArray, start: Int): Int {
    val n = data.size
    if (start + 1 >= n || data[start] != 0xFF.toByte() || data[start + 1] != 0xD8.toByte()) return -1
    var p = start + 2
    while (p + 1 < n) {
        if (data[p] != 0xFF.toByte()) { p++; continue }          // resync to next 0xFF
        var mPos = p + 1
        while (mPos < n && data[mPos] == 0xFF.toByte()) mPos++    // skip fill bytes
        if (mPos >= n) return -1
        when (data[mPos].toInt() and 0xFF) {
            0xD9 -> return mPos - start + 1                       // EOI
            0x01 -> p = mPos + 1                                  // TEM, no payload
            in 0xD0..0xD7 -> p = mPos + 1                         // RSTn, no payload
            0xDA -> {                                             // SOS: skip header, scan entropy
                if (mPos + 2 >= n) return -1
                val len = ((data[mPos + 1].toInt() and 0xFF) shl 8) or (data[mPos + 2].toInt() and 0xFF)
                p = mPos + 1 + len
                while (p + 1 < n) {
                    if (data[p] == 0xFF.toByte()) {
                        val b = data[p + 1].toInt() and 0xFF
                        if (b == 0x00 || b in 0xD0..0xD7) { p += 2; continue }  // stuffed / RSTn
                        break                                     // real marker (the EOI)
                    }
                    p++
                }
            }
            else -> {                                            // marker with a 2-byte length
                if (mPos + 2 >= n) return -1
                val len = ((data[mPos + 1].toInt() and 0xFF) shl 8) or (data[mPos + 2].toInt() and 0xFF)
                if (len < 2) return -1
                p = mPos + 1 + len
            }
        }
    }
    return -1
}
