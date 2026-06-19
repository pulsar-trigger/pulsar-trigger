/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Copyright (C) 2026 Pulsar Trigger contributors
 */

package com.ehrocha.pulsar.ptp

import com.ehrocha.pulsar.canonble.CanonBleLog
import com.ehrocha.pulsar.transport.CameraImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
