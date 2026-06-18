/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Copyright (C) 2026 Pulsar Trigger contributors
 */

package com.ehrocha.pulsar.transfer

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream

/**
 * Saves transferred camera images into the phone gallery under
 * **Pictures/Pulsar**, so they appear in Google Photos / the gallery.
 *
 * Two-phase to pair with the streaming download: [open] creates the entry and
 * hands back an [OutputStream] the transport streams into; then [publish]
 * makes it visible (or [discard] removes a partial file if the download
 * failed mid-stream).
 *
 * No storage permission needed on API 29+ (scoped MediaStore). On 26–28 it
 * falls back to the app's external Pictures dir + a media-scan so the image is
 * still indexed — best-effort gallery visibility without `WRITE_EXTERNAL_STORAGE`.
 */
object MediaStoreSaver {

    /** Sub-folder under Pictures/ that all transfers land in. */
    private const val SUBDIR = "Pulsar"

    /** Create a (pending) gallery entry for [fileName] and return its content
     *  Uri + an OutputStream to stream the file into, or null on failure. */
    fun open(context: Context, fileName: String, mimeType: String): Pair<Uri, OutputStream>? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) openScoped(context, fileName, mimeType)
        else openLegacy(context, fileName)

    /** Make a fully-written entry visible in the gallery. */
    fun publish(context: Context, uri: Uri) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }
            runCatching { context.contentResolver.update(uri, values, null, null) }
        } else {
            uri.path?.let { MediaScannerConnection.scanFile(context, arrayOf(it), null, null) }
        }
    }

    /** Remove a partially-written entry (download failed mid-stream). */
    fun discard(context: Context, uri: Uri) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            runCatching { context.contentResolver.delete(uri, null, null) }
        } else {
            uri.path?.let { runCatching { File(it).delete() } }
        }
    }

    private fun openScoped(context: Context, fileName: String, mimeType: String): Pair<Uri, OutputStream>? {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
            put(MediaStore.Images.Media.MIME_TYPE, mimeType)
            put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/$SUBDIR")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val uri = runCatching { resolver.insert(collection, values) }.getOrNull() ?: return null
        val stream = runCatching { resolver.openOutputStream(uri) }.getOrNull()
        if (stream == null) {
            runCatching { resolver.delete(uri, null, null) }
            return null
        }
        return uri to stream
    }

    private fun openLegacy(context: Context, fileName: String): Pair<Uri, OutputStream>? {
        val dir = File(context.getExternalFilesDir(Environment.DIRECTORY_PICTURES), SUBDIR)
        if (!dir.exists() && !dir.mkdirs()) return null
        val file = File(dir, fileName)
        val stream = runCatching { FileOutputStream(file) }.getOrNull() ?: return null
        return Uri.fromFile(file) to stream
    }
}
