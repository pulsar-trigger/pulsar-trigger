/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Copyright (C) 2026 Pulsar Trigger contributors
 */

package com.ehrocha.pulsar.stacking

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Rect
import android.media.Image
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Encode a sequence of JPEG frames into an H.264 MP4 via MediaCodec + MediaMuxer.
 *
 * Pure Kotlin, no FFmpeg / no extra deps. Works with Android's built-in encoder,
 * downscales to a max dimension (default 1920 px) so timelapse renders aren't
 * gigabyte-sized, and runs cooperatively with the existing stacking pipeline
 * (call from Dispatchers.Default like the Stacker entry points).
 */
object VideoBuilder {

    fun interface ProgressCallback {
        fun onProgress(current: Int, total: Int)
    }

    /**
     * @param sequenceRelativePath e.g. `"DCIM/Pulsar/Sequence_20260501_120000"` —
     *   the MP4 lands in the same folder so it travels with the source frames.
     * @param fps Output frame rate. 30 is the safe default for playback compatibility.
     * @param maxDim Longest output edge in pixels. Aspect ratio preserved.
     * @param bitrate Encoder target bitrate. 10 Mbps gives good quality for 1080p.
     */
    fun build(
        context: Context,
        frames: List<Uri>,
        sequenceRelativePath: String,
        fps: Int = 30,
        maxDim: Int = 1920,
        bitrate: Int = 10_000_000,
        progress: ProgressCallback,
    ): Uri? {
        if (frames.isEmpty()) return null

        val (srcW, srcH) = readDimensions(context, frames[0]) ?: return null
        val (targetW, targetH) = scaleToFit(srcW, srcH, maxDim)
        if (targetW <= 0 || targetH <= 0) return null

        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, targetW, targetH).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }
        val encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        encoder.start()

        val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(System.currentTimeMillis())
        val cv = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "Timelapse_$ts.mp4")
            put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.MediaColumns.RELATIVE_PATH, sequenceRelativePath)
            }
        }
        val outUri = context.contentResolver.insert(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI, cv,
        ) ?: run {
            try { encoder.stop() } catch (_: Exception) {}
            encoder.release()
            return null
        }
        val pfd = context.contentResolver.openFileDescriptor(outUri, "rw") ?: run {
            try { encoder.stop() } catch (_: Exception) {}
            encoder.release()
            try { context.contentResolver.delete(outUri, null, null) } catch (_: Exception) {}
            return null
        }
        val muxer = MediaMuxer(pfd.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

        var trackIndex = -1
        var muxerStarted = false
        val bufferInfo = MediaCodec.BufferInfo()

        fun drain(endOfStream: Boolean) {
            while (true) {
                val outIdx = encoder.dequeueOutputBuffer(bufferInfo, if (endOfStream) 10_000 else 0)
                when {
                    outIdx == MediaCodec.INFO_TRY_AGAIN_LATER -> if (!endOfStream) return
                    outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        if (!muxerStarted) {
                            trackIndex = muxer.addTrack(encoder.outputFormat)
                            muxer.start()
                            muxerStarted = true
                        }
                    }
                    outIdx >= 0 -> {
                        val outBuf = encoder.getOutputBuffer(outIdx)
                        if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                            bufferInfo.size = 0
                        }
                        if (bufferInfo.size > 0 && outBuf != null && muxerStarted) {
                            outBuf.position(bufferInfo.offset)
                            outBuf.limit(bufferInfo.offset + bufferInfo.size)
                            muxer.writeSampleData(trackIndex, outBuf, bufferInfo)
                        }
                        encoder.releaseOutputBuffer(outIdx, false)
                        if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) return
                    }
                }
            }
        }

        var success = false
        var encodedFrames = 0
        try {
            for (i in frames.indices) {
                drain(endOfStream = false)
                val bitmap = decodeAndScale(context, frames[i], targetW, targetH)
                if (bitmap == null) {
                    progress.onProgress(i + 1, frames.size)
                    continue
                }
                while (true) {
                    val inIdx = encoder.dequeueInputBuffer(10_000)
                    if (inIdx >= 0) {
                        val image = encoder.getInputImage(inIdx)
                        if (image != null) {
                            fillImageWithBitmap(image, bitmap)
                            val ptUs = (encodedFrames.toLong() * 1_000_000L) / fps
                            encoder.queueInputBuffer(inIdx, 0, targetW * targetH * 3 / 2, ptUs, 0)
                            encodedFrames++
                        } else {
                            encoder.queueInputBuffer(inIdx, 0, 0, 0L, 0)
                        }
                        break
                    }
                    drain(endOfStream = false)
                }
                bitmap.recycle()
                progress.onProgress(i + 1, frames.size)
            }

            // Push end-of-stream sentinel so the encoder flushes.
            while (true) {
                val inIdx = encoder.dequeueInputBuffer(10_000)
                if (inIdx >= 0) {
                    encoder.queueInputBuffer(inIdx, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                    break
                }
                drain(endOfStream = false)
            }
            drain(endOfStream = true)
            success = encodedFrames > 0
        } catch (_: Exception) {
            success = false
        } finally {
            try { encoder.stop() } catch (_: Exception) {}
            encoder.release()
            try {
                if (muxerStarted) muxer.stop()
                muxer.release()
            } catch (_: Exception) {}
            try { pfd.close() } catch (_: Exception) {}
        }

        return if (success) {
            outUri
        } else {
            try { context.contentResolver.delete(outUri, null, null) } catch (_: Exception) {}
            null
        }
    }

    private fun readDimensions(context: Context, uri: Uri): Pair<Int, Int>? = try {
        context.contentResolver.openInputStream(uri)?.use {
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeStream(it, null, opts)
            if (opts.outWidth > 0 && opts.outHeight > 0) opts.outWidth to opts.outHeight else null
        }
    } catch (_: Exception) {
        null
    }

    private fun scaleToFit(w: Int, h: Int, maxDim: Int): Pair<Int, Int> {
        val maxSrc = maxOf(w, h)
        val scale = if (maxSrc > maxDim) maxDim.toDouble() / maxSrc else 1.0
        // H.264 wants even dimensions.
        var tw = (w * scale).toInt()
        var th = (h * scale).toInt()
        if (tw and 1 == 1) tw--
        if (th and 1 == 1) th--
        return tw to th
    }

    /**
     * Decode + downscale in one pass. Uses inSampleSize to keep peak memory
     * down: a 12 MP source going to 1080 p drops to ~3 MP at decode time
     * before the final exact-fit Canvas resize.
     */
    private fun decodeAndScale(context: Context, uri: Uri, targetW: Int, targetH: Int): Bitmap? = try {
        val dim = readDimensions(context, uri)
        var sample = 1
        if (dim != null) {
            val (sw, sh) = dim
            while ((sw / (sample * 2)) >= targetW && (sh / (sample * 2)) >= targetH) {
                sample *= 2
            }
        }
        context.contentResolver.openInputStream(uri)?.use {
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            val src = BitmapFactory.decodeStream(it, null, opts) ?: return@use null
            if (src.width == targetW && src.height == targetH && src.config == Bitmap.Config.ARGB_8888) {
                src
            } else {
                val scaled = Bitmap.createBitmap(targetW, targetH, Bitmap.Config.ARGB_8888)
                Canvas(scaled).drawBitmap(src, null, Rect(0, 0, targetW, targetH), null)
                src.recycle()
                scaled
            }
        }
    } catch (_: Exception) {
        null
    }

    /**
     * RGB→YUV420 (BT.601 limited range). Image API hides device-specific layout
     * via per-plane row/pixel strides, so this works on planar and semi-planar
     * (NV12-ish) encoders without branching.
     */
    private fun fillImageWithBitmap(image: Image, bitmap: Bitmap) {
        val w = image.width
        val h = image.height
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)

        val yPlane = image.planes[0]
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]
        val yBuf = yPlane.buffer
        val uBuf = uPlane.buffer
        val vBuf = vPlane.buffer
        val yRowStride = yPlane.rowStride
        val yPixStride = yPlane.pixelStride
        val uRowStride = uPlane.rowStride
        val uPixStride = uPlane.pixelStride
        val vRowStride = vPlane.rowStride
        val vPixStride = vPlane.pixelStride

        // Y at full resolution.
        for (y in 0 until h) {
            val rowBase = y * w
            val yRowBase = y * yRowStride
            for (x in 0 until w) {
                val p = pixels[rowBase + x]
                val r = (p shr 16) and 0xff
                val g = (p shr 8) and 0xff
                val b = p and 0xff
                val yVal = (((66 * r + 129 * g + 25 * b + 128) shr 8) + 16).coerceIn(0, 255)
                yBuf.put(yRowBase + x * yPixStride, yVal.toByte())
            }
        }

        // U/V at quarter resolution (2×2 box average).
        val cw = w / 2
        val ch = h / 2
        for (j in 0 until ch) {
            val srcBase0 = (j * 2) * w
            val srcBase1 = srcBase0 + w
            val uRowBase = j * uRowStride
            val vRowBase = j * vRowStride
            for (i in 0 until cw) {
                val xs = i * 2
                val p00 = pixels[srcBase0 + xs]
                val p01 = pixels[srcBase0 + xs + 1]
                val p10 = pixels[srcBase1 + xs]
                val p11 = pixels[srcBase1 + xs + 1]
                val r = (((p00 shr 16) and 0xff) + ((p01 shr 16) and 0xff) +
                        ((p10 shr 16) and 0xff) + ((p11 shr 16) and 0xff)) shr 2
                val g = (((p00 shr 8) and 0xff) + ((p01 shr 8) and 0xff) +
                        ((p10 shr 8) and 0xff) + ((p11 shr 8) and 0xff)) shr 2
                val b = ((p00 and 0xff) + (p01 and 0xff) +
                        (p10 and 0xff) + (p11 and 0xff)) shr 2
                val u = (((-38 * r - 74 * g + 112 * b + 128) shr 8) + 128).coerceIn(0, 255)
                val v = (((112 * r - 94 * g - 18 * b + 128) shr 8) + 128).coerceIn(0, 255)
                uBuf.put(uRowBase + i * uPixStride, u.toByte())
                vBuf.put(vRowBase + i * vPixStride, v.toByte())
            }
        }
    }
}
