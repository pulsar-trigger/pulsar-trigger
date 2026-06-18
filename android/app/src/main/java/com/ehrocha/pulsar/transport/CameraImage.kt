/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Copyright (C) 2026 Pulsar Trigger contributors
 */

package com.ehrocha.pulsar.transport

/**
 * One transferable image on the camera's storage, transport-agnostic. The
 * photo-transfer gallery lists these, shows their thumbnails, and downloads
 * the selected ones — without caring whether the bytes come over PTP (USB or
 * Wi-Fi) or CCAPI HTTP.
 *
 * [id] is an **opaque per-transport handle** the UI never interprets: a PTP
 * `ObjectHandle` (hex string) for the PTP wires, or the CCAPI content URL path
 * for CCAPI. It's passed straight back to the originating transport's
 * [CameraTransport.getThumbnail] / [CameraTransport.downloadImage].
 */
data class CameraImage(
    /** Opaque handle, meaningful only to the transport that produced it. */
    val id: String,
    /** Display + save filename, e.g. `IMG_0421.JPG` / `IMG_0421.CR3`. */
    val fileName: String,
    /** Full-file size in bytes if the protocol reports it, else 0 (unknown). */
    val byteSize: Long,
    /** RAW (CR3 / CR2 / …) vs a viewable JPEG/HEIF — drives the UI badge and
     *  the MediaStore MIME type. Classified by filename extension. */
    val isRaw: Boolean,
    /** Best-effort capture time (epoch millis), or null if the protocol didn't
     *  give one. Used only for newest-first sorting + display. */
    val capturedEpochMs: Long? = null,
) {
    companion object {
        private val RAW_EXTENSIONS = setOf("cr3", "cr2", "crw", "raw", "dng", "arw", "nef")
        private val VIEWABLE_EXTENSIONS = setOf("jpg", "jpeg", "heic", "heif", "png")

        /** True when [fileName]'s extension is a known RAW format. */
        fun isRawName(fileName: String): Boolean =
            fileName.substringAfterLast('.', "").lowercase() in RAW_EXTENSIONS

        /** True when [fileName] is a transferable still image (viewable or RAW)
         *  — used to filter out folders / movies / sidecars during
         *  enumeration. */
        fun isImageName(fileName: String): Boolean {
            val ext = fileName.substringAfterLast('.', "").lowercase()
            return ext in VIEWABLE_EXTENSIONS || ext in RAW_EXTENSIONS
        }

        /** MIME type for MediaStore, derived from the extension. RAW formats
         *  have no universal MIME, so they fall back to `image/x-canon-cr3`
         *  (CR3) / `image/x-canon-cr2` / a generic octet-stream. */
        fun mimeFor(fileName: String): String =
            when (fileName.substringAfterLast('.', "").lowercase()) {
                "jpg", "jpeg" -> "image/jpeg"
                "heif", "heic" -> "image/heif"
                "png" -> "image/png"
                "cr3" -> "image/x-canon-cr3"
                "cr2" -> "image/x-canon-cr2"
                "dng" -> "image/x-adobe-dng"
                else -> "application/octet-stream"
            }
    }
}
