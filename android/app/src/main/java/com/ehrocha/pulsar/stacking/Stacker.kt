/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Copyright (C) 2026 Pulsar Trigger contributors
 */

package com.ehrocha.pulsar.stacking

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Frame-stacking algorithms. Pure-Kotlin pixel ops; runs on Dispatchers.Default.
 *
 * - [lightenBlend] — per-pixel max across all frames. For star trails, lightning
 *   composites, fireworks composites: anything where you want to keep every bright
 *   thing across the sequence.
 * - [meanStack] — per-pixel average. Reduces noise on tracked / aligned subjects
 *   (Auto Astro). Assumes frames are already aligned (no movement compensation here yet).
 *
 * Memory note: an IntArray accumulator at 12 MP × 3 channels = ~144 MB. Tight on
 * older phones — for now we keep the implementation simple and trust modern hardware.
 */
object Stacker {

    enum class Type(val label: String) { LIGHTEN("lighten"), MEAN("mean") }

    /** Reports `current` of `total` frames processed (current is 1-based). */
    fun interface ProgressCallback {
        fun onProgress(current: Int, total: Int)
    }

    fun lightenBlend(
        context: Context,
        frames: List<Uri>,
        progress: ProgressCallback,
    ): Bitmap? {
        if (frames.isEmpty()) return null

        val first = decode(context, frames[0]) ?: return null
        val w = first.width
        val h = first.height
        val acc = IntArray(w * h)
        first.getPixels(acc, 0, w, 0, 0, w, h)
        first.recycle()
        progress.onProgress(1, frames.size)

        val px = IntArray(w * h)
        for (i in 1 until frames.size) {
            val frame = decode(context, frames[i]) ?: continue
            if (frame.width != w || frame.height != h) {
                frame.recycle()
                continue
            }
            frame.getPixels(px, 0, w, 0, 0, w, h)
            frame.recycle()
            for (j in acc.indices) {
                val a = acc[j]
                val b = px[j]
                val r = maxOf((a shr 16) and 0xff, (b shr 16) and 0xff)
                val g = maxOf((a shr 8) and 0xff, (b shr 8) and 0xff)
                val bl = maxOf(a and 0xff, b and 0xff)
                acc[j] = (0xff shl 24) or (r shl 16) or (g shl 8) or bl
            }
            progress.onProgress(i + 1, frames.size)
        }

        return Bitmap.createBitmap(acc, w, h, Bitmap.Config.ARGB_8888)
    }

    fun meanStack(
        context: Context,
        frames: List<Uri>,
        progress: ProgressCallback,
    ): Bitmap? {
        if (frames.isEmpty()) return null

        val first = decode(context, frames[0]) ?: return null
        val w = first.width
        val h = first.height
        val n = w * h
        val sumR = IntArray(n)
        val sumG = IntArray(n)
        val sumB = IntArray(n)

        val px = IntArray(n)
        first.getPixels(px, 0, w, 0, 0, w, h)
        first.recycle()
        for (j in 0 until n) {
            val p = px[j]
            sumR[j] = (p shr 16) and 0xff
            sumG[j] = (p shr 8) and 0xff
            sumB[j] = p and 0xff
        }
        var count = 1
        progress.onProgress(1, frames.size)

        for (i in 1 until frames.size) {
            val frame = decode(context, frames[i]) ?: continue
            if (frame.width != w || frame.height != h) {
                frame.recycle()
                continue
            }
            frame.getPixels(px, 0, w, 0, 0, w, h)
            frame.recycle()
            for (j in 0 until n) {
                val p = px[j]
                sumR[j] += (p shr 16) and 0xff
                sumG[j] += (p shr 8) and 0xff
                sumB[j] += p and 0xff
            }
            count++
            progress.onProgress(i + 1, frames.size)
        }

        val out = IntArray(n)
        for (j in 0 until n) {
            out[j] = (0xff shl 24) or
                ((sumR[j] / count) shl 16) or
                ((sumG[j] / count) shl 8) or
                (sumB[j] / count)
        }
        return Bitmap.createBitmap(out, w, h, Bitmap.Config.ARGB_8888)
    }

    /** Saves the stacked bitmap into the same sequence folder. Returns the new URI. */
    fun saveResult(
        context: Context,
        bitmap: Bitmap,
        sequenceRelativePath: String,
        type: Type,
    ): Uri? {
        val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
            .format(System.currentTimeMillis())
        val cv = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "Stack_${type.label}_$ts")
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.MediaColumns.RELATIVE_PATH, sequenceRelativePath)
            }
        }
        val uri = context.contentResolver.insert(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv
        ) ?: return null
        return try {
            context.contentResolver.openOutputStream(uri)?.use { os ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 92, os)
            }
            uri
        } catch (_: Exception) {
            null
        }
    }

    private fun decode(context: Context, uri: Uri): Bitmap? = try {
        context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it)
        }
    } catch (_: Exception) {
        null
    }
}
