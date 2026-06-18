/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Copyright (C) 2026 Pulsar Trigger contributors
 */

package com.ehrocha.pulsar.ptp

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
 */

/** Enumerate every still image on the card as [CameraImage]s, newest-first.
 *  One `GetObjectInfo` round-trip per handle, filtered to image extensions
 *  (folders / movies / sidecars dropped). Returns empty on any wire error
 *  rather than throwing — the gallery shows an empty state. */
internal suspend fun PtpClient.listImageContents(): List<CameraImage> =
    withContext(Dispatchers.IO) {
        runCatching {
            getObjectHandles().mapNotNull { handle ->
                val info = getObjectInfo(handle) ?: return@mapNotNull null
                if (!CameraImage.isImageName(info.fileName)) return@mapNotNull null
                CameraImage(
                    id = handle.toString(),
                    fileName = info.fileName,
                    byteSize = info.compressedSize,
                    isRaw = CameraImage.isRawName(info.fileName),
                    capturedEpochMs = info.captureEpochMs,
                )
            }.sortedByDescending { it.capturedEpochMs ?: 0L }
        }.getOrDefault(emptyList())
    }

/** Embedded thumbnail JPEG for [image], or null on failure. */
internal suspend fun PtpClient.thumbnailFor(image: CameraImage): ByteArray? =
    withContext(Dispatchers.IO) {
        val handle = image.id.toIntOrNull() ?: return@withContext null
        runCatching { getThumb(handle) }.getOrNull()
    }

/** Stream the full file for [image] into [sink]. Returns true on success. */
internal suspend fun PtpClient.downloadObject(
    image: CameraImage,
    sink: OutputStream,
    onProgress: (Long, Long) -> Unit,
): Boolean = withContext(Dispatchers.IO) {
    val handle = image.id.toIntOrNull() ?: return@withContext false
    runCatching { getObject(handle, sink, onProgress) }.getOrDefault(false)
}
