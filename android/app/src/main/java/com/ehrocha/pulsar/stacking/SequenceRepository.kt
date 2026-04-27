/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Copyright (C) 2026 Pulsar Trigger contributors
 */

package com.ehrocha.pulsar.stacking

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore

/**
 * One captured sequence — `DCIM/Pulsar/Sequence_<timestamp>/`.
 *
 * Each Auto/Storm/Trails/Fireworks/Astro run drops its frames in a fresh folder
 * so stacking can later target a single sequence without scanning the whole library.
 */
data class SequenceFolder(
    val path: String,           // e.g. "DCIM/Pulsar/Sequence_20260426_103015"
    val name: String,           // e.g. "Sequence_20260426_103015"
    val frames: List<Uri>,
    val mostRecentMs: Long,
)

object SequenceRepository {

    /** All sequence folders currently on disk, newest first. */
    fun list(context: Context): List<SequenceFolder> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return emptyList()

        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.RELATIVE_PATH,
            MediaStore.Images.Media.DATE_ADDED,
        )
        val selection = "${MediaStore.Images.Media.RELATIVE_PATH} LIKE ?"
        val args = arrayOf("DCIM/Pulsar/Sequence_%")
        val folders = mutableMapOf<String, MutableList<Pair<Uri, Long>>>()

        try {
            context.contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                projection, selection, args,
                "${MediaStore.Images.Media.DATE_ADDED} ASC"  // ASC so frames stay in capture order
            )?.use { c ->
                val idCol = c.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                val pathCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.RELATIVE_PATH)
                val nameCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
                val dateCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)
                while (c.moveToNext()) {
                    val id = c.getLong(idCol)
                    val path = c.getString(pathCol)?.trimEnd('/') ?: continue
                    val name = c.getString(nameCol) ?: ""
                    // Hide previously-saved stack outputs from the frame list — they're results, not inputs.
                    if (name.startsWith("Stack_")) continue
                    val date = c.getLong(dateCol) * 1000L
                    val uri = ContentUris.withAppendedId(
                        MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id
                    )
                    folders.getOrPut(path) { mutableListOf() }.add(uri to date)
                }
            }
        } catch (_: Exception) {
            return emptyList()
        }

        return folders.map { (path, items) ->
            val name = path.substringAfterLast('/')
            SequenceFolder(
                path = path,
                name = name,
                frames = items.map { it.first },
                mostRecentMs = items.maxOfOrNull { it.second } ?: 0L,
            )
        }.sortedByDescending { it.mostRecentMs }
    }

    /** Just one folder by path (re-queries to keep current). */
    fun get(context: Context, path: String): SequenceFolder? =
        list(context).firstOrNull { it.path == path }

    /** Delete every frame (and any saved stack output) in the given folder.
     *  Returns the count successfully deleted. */
    fun deleteSequence(context: Context, folder: SequenceFolder): Int {
        val resolver = context.contentResolver
        var deleted = 0
        for (uri in folder.frames) {
            try {
                deleted += resolver.delete(uri, null, null)
            } catch (_: Exception) {
                // Skip frames we don't have permission to delete; user can clean
                // them up via the system file manager.
            }
        }
        // Also drop any custom label we'd stored for this path.
        SequenceLabels.set(context, folder.path, null)
        return deleted
    }
}
