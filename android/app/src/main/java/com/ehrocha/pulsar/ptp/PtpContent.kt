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

/** Enumerate every still image on the card as [CameraImage]s, newest-first.
 *  Tries the flat "all objects on all stores" query first; if that comes back
 *  empty (some Canon bodies reject the 0xFFFFFFFF association in remote mode),
 *  falls back to enumerating each store's root. One `GetObjectInfo` per handle,
 *  filtered to image extensions. Returns empty on any wire error. */
internal suspend fun PtpClient.listImageContents(): List<CameraImage> =
    withContext(Dispatchers.IO) {
        runCatching {
            var handles = getObjectHandles()  // STORAGE_ALL, fmt 0, HANDLE_ALL
            CanonBleLog.i(TAG, "GetObjectHandles(all) -> ${handles.size}")
            if (handles.isEmpty()) {
                val storages = getStorageIds()
                CanonBleLog.i(TAG, "GetStorageIDs -> ${storages.size} " +
                    storages.joinToString(prefix = "[", postfix = "]") { "0x%08X".format(it) })
                handles = storages.flatMap { sid ->
                    getObjectHandles(storageId = sid, objectFormat = 0, parent = 0)
                }
                CanonBleLog.i(TAG, "GetObjectHandles(per-storage root) -> ${handles.size}")
            }
            val images = handles.mapNotNull { handle ->
                val info = getObjectInfo(handle) ?: return@mapNotNull null
                if (!CameraImage.isImageName(info.fileName)) return@mapNotNull null
                CameraImage(
                    id = handle.toString(),
                    fileName = info.fileName,
                    byteSize = info.compressedSize,
                    isRaw = CameraImage.isRawName(info.fileName),
                    capturedEpochMs = info.captureEpochMs,
                )
            }
            CanonBleLog.i(TAG, "listContents -> ${images.size} images of ${handles.size} objects")
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
