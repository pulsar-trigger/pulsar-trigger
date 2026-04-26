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

    enum class Type(val label: String) {
        LIGHTEN("lighten"),
        MEAN("mean"),
        LIGHTNING("lightning"),
    }

    /** Result of a lightning auto-cull pass. */
    data class LightningResult(
        val totalFrames: Int,
        val winnerIndices: List<Int>,
        val composite: Bitmap?,
    )

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

    private fun decodeDownsampled(context: Context, uri: Uri, sampleSize: Int): Bitmap? = try {
        context.contentResolver.openInputStream(uri)?.use {
            val opts = BitmapFactory.Options().apply { inSampleSize = sampleSize.coerceAtLeast(1) }
            BitmapFactory.decodeStream(it, null, opts)
        }
    } catch (_: Exception) {
        null
    }

    /**
     * Lightning auto-cull then composite: walk the sequence, score each frame by
     * how many near-saturated pixels it has (lightning bolts spike this hard),
     * flag frames whose ratio is well above the per-sequence median, and
     * lighten-blend just those winners into a single composite image.
     *
     * If nothing stands out (no strike captured), `winnerIndices` is empty and
     * `composite` is null — caller can surface a friendly "no strikes" message.
     */
    fun lightningCompose(
        context: Context,
        frames: List<Uri>,
        progress: ProgressCallback,
    ): LightningResult? {
        if (frames.isEmpty()) return null

        // ── Pass 1: bright-pixel ratio per frame, on downsampled frames for speed ──
        val analysisSample = 8
        val ratios = DoubleArray(frames.size)
        val twoPassTotal = frames.size * 2
        for (i in frames.indices) {
            val bm = decodeDownsampled(context, frames[i], analysisSample)
            if (bm != null) {
                val w = bm.width
                val h = bm.height
                val px = IntArray(w * h)
                bm.getPixels(px, 0, w, 0, 0, w, h)
                bm.recycle()
                var bright = 0
                // Threshold: per-channel sum > 660 ≈ each channel ≥ 220/255.
                for (p in px) {
                    val s = ((p shr 16) and 0xff) + ((p shr 8) and 0xff) + (p and 0xff)
                    if (s > 660) bright++
                }
                ratios[i] = bright.toDouble() / (w * h).coerceAtLeast(1)
            }
            progress.onProgress(i + 1, twoPassTotal)
        }

        // ── Threshold = max(3× median, 0.5%). Median is the quiet-sky baseline; ──
        // ── 0.5% floor catches sequences where every frame is mostly dark.         ──
        val sortedRatios = ratios.sortedArray()
        val median = sortedRatios[sortedRatios.size / 2]
        val threshold = maxOf(median * 3.0, 0.005)
        val winnerIndices = ratios.indices.filter { ratios[it] > threshold }

        if (winnerIndices.isEmpty()) {
            return LightningResult(frames.size, emptyList(), null)
        }

        // ── Pass 2: lighten-blend just the winners at full resolution ──
        val winnerUris = winnerIndices.map { frames[it] }
        val composite = lightenBlend(context, winnerUris) { current, _ ->
            progress.onProgress(frames.size + current, twoPassTotal)
        }
        return LightningResult(frames.size, winnerIndices, composite)
    }
}
