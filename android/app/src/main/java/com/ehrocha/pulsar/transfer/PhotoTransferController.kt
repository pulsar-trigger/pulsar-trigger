/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Copyright (C) 2026 Pulsar Trigger contributors
 */

package com.ehrocha.pulsar.transfer

import android.content.Context
import android.graphics.BitmapFactory
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.ehrocha.pulsar.transport.CameraImage
import com.ehrocha.pulsar.transport.CameraTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Screen-scoped state + actions for the photo-transfer gallery. Wraps the
 * active content-capable [CameraTransport] (CCAPI / USB-PTP / PTP-IP) and the
 * [MediaStoreSaver]. State is Compose-observable so the screen recomposes as
 * the listing loads, thumbnails arrive, and a transfer progresses.
 *
 * [scope] is the screen's coroutine scope, so leaving the gallery cancels an
 * in-flight listing / transfer (a partially-written file is discarded by
 * [saveOne]'s failure path; a hard cancel leaves the MediaStore entry pending,
 * which the OS cleans up).
 */
class PhotoTransferController(
    private val transport: CameraTransport,
    private val appContext: Context,
    private val scope: CoroutineScope,
) {
    var loading by mutableStateOf(true)
        private set
    var loadFailed by mutableStateOf(false)
        private set

    /** Images on the card, newest-first. */
    val images = mutableStateListOf<CameraImage>()

    /** Format filter for the grid. */
    enum class Filter { ALL, JPEG, RAW }
    var filter by mutableStateOf(Filter.ALL)

    /** Images matching the active [filter] (same newest-first order). */
    val visibleImages: List<CameraImage>
        get() = when (filter) {
            Filter.ALL -> images
            Filter.RAW -> images.filter { it.isRaw }
            Filter.JPEG -> images.filter { !it.isRaw }
        }

    val rawCount: Int get() = images.count { it.isRaw }
    val jpegCount: Int get() = images.count { !it.isRaw }

    /** Selected image ids. */
    val selected = mutableStateListOf<String>()

    /** id → decoded thumbnail (null while in-flight or on failure). */
    private val thumbs = mutableStateMapOf<String, ImageBitmap?>()

    /** Non-null while a transfer is running. */
    var transfer by mutableStateOf<Progress?>(null)
        private set

    /** Set once when a transfer finishes; the screen shows it then clears it. */
    var result by mutableStateOf<Result?>(null)
        private set

    data class Progress(val current: Int, val total: Int, val fileName: String, val fraction: Float)
    data class Result(val saved: Int, val rawSaved: Int, val failed: Int)

    val busy: Boolean get() = transfer != null

    /** Load the card listing. Safe to call again (e.g. a refresh). */
    fun load() {
        scope.launch {
            loading = true
            loadFailed = false
            images.clear(); selected.clear(); thumbs.clear(); filter = Filter.ALL
            val list = runCatching { transport.listContents() }.getOrNull()
            if (list == null) loadFailed = true else images.addAll(list)
            loading = false
        }
    }

    /** Thumbnail for [image], fetching + decoding lazily on first request.
     *  Returns null until it arrives (or if it failed). */
    fun thumbnail(image: CameraImage): ImageBitmap? {
        if (image.id !in thumbs) {
            thumbs[image.id] = null  // mark in-flight so the grid doesn't refetch
            scope.launch {
                val bmp = withContext(Dispatchers.IO) {
                    val bytes = runCatching { transport.getThumbnail(image) }.getOrNull()
                    bytes?.let {
                        runCatching { BitmapFactory.decodeByteArray(it, 0, it.size) }.getOrNull()
                    }
                }
                thumbs[image.id] = bmp?.asImageBitmap()
            }
        }
        return thumbs[image.id]
    }

    fun toggle(id: String) { if (id in selected) selected.remove(id) else selected.add(id) }
    /** Select every image in the *current filter* (so "Select all" under the
     *  RAW chip selects only RAW). */
    fun selectAll() { selected.clear(); selected.addAll(visibleImages.map { it.id }) }
    fun clearSelection() { selected.clear() }
    fun consumeResult() { result = null }

    fun transferSelected() = transferThese(images.filter { it.id in selected })
    /** Transfer every image in the current filter. */
    fun transferAll() = transferThese(visibleImages.toList())

    private fun transferThese(list: List<CameraImage>) {
        if (list.isEmpty() || busy) return
        scope.launch {
            var saved = 0
            var rawSaved = 0
            var failed = 0
            list.forEachIndexed { i, img ->
                transfer = Progress(i + 1, list.size, img.fileName, 0f)
                val ok = saveOne(img) { written, total ->
                    val frac = if (total > 0) (written.toFloat() / total).coerceIn(0f, 1f) else 0f
                    transfer = Progress(i + 1, list.size, img.fileName, frac)
                }
                if (ok) {
                    saved++
                    if (img.isRaw) rawSaved++  // RAW lands in Download/Pulsar, not the gallery
                } else failed++
            }
            transfer = null
            result = Result(saved, rawSaved, failed)
            clearSelection()
        }
    }

    /** Stream one image straight into a MediaStore entry; publish on success,
     *  discard the partial entry on failure. */
    private suspend fun saveOne(image: CameraImage, onProgress: (Long, Long) -> Unit): Boolean =
        withContext(Dispatchers.IO) {
            val mime = CameraImage.mimeFor(image.fileName)
            val (uri, stream) = MediaStoreSaver.open(appContext, image.fileName, mime)
                ?: run {
                    com.ehrocha.pulsar.canonble.CanonBleLog.w(
                        "PhotoTransfer", "saveOne(${image.fileName}): MediaStore.open returned null")
                    return@withContext false
                }
            val ok = runCatching {
                stream.use { transport.downloadImage(image, it, onProgress) }
            }.onFailure {
                com.ehrocha.pulsar.canonble.CanonBleLog.w(
                    "PhotoTransfer", "saveOne(${image.fileName}) download threw: ${it.message}")
            }.getOrDefault(false)
            if (ok) MediaStoreSaver.publish(appContext, uri) else MediaStoreSaver.discard(appContext, uri)
            ok
        }
}
