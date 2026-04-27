/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Copyright (C) 2026 Pulsar Trigger contributors
 */

package com.ehrocha.pulsar.stacking

import android.content.Context
import androidx.core.content.edit

/**
 * Which capture mode produced a given sequence. Drives the post-processing UI:
 * Auto Astro gets Mean + Nightscape, Storm gets Lightning + Lighten, etc.
 *
 * Manual is the fallback when the user took the sequence themselves through
 * the manual intervalometer panel — we don't know their intent, so all
 * composites are offered.
 */
enum class CaptureMode { AUTO_ASTRO, STORM, TRAILS, FIREWORKS, MANUAL }

/** Prefs-backed `path → CaptureMode` mapping. Set when the sequence starts; read
 *  when the user opens its detail screen. */
object SequenceTags {
    private const val PREFS = "sequence_tags"

    fun get(context: Context, path: String): CaptureMode? {
        val name = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(path, null) ?: return null
        return runCatching { CaptureMode.valueOf(name) }.getOrNull()
    }

    fun set(context: Context, path: String, mode: CaptureMode?) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit {
            if (mode == null) remove(path) else putString(path, mode.name)
        }
    }

    /** Composites that make sense for this capture. Manual / null = all four. */
    fun availableComposites(mode: CaptureMode?): Set<Stacker.Type> = when (mode) {
        CaptureMode.AUTO_ASTRO -> setOf(Stacker.Type.MEAN, Stacker.Type.NIGHTSCAPE)
        CaptureMode.STORM -> setOf(Stacker.Type.LIGHTNING, Stacker.Type.LIGHTEN)
        CaptureMode.TRAILS -> setOf(Stacker.Type.LIGHTEN)
        CaptureMode.FIREWORKS -> setOf(Stacker.Type.LIGHTEN)
        CaptureMode.MANUAL, null -> setOf(
            Stacker.Type.LIGHTEN, Stacker.Type.MEAN,
            Stacker.Type.LIGHTNING, Stacker.Type.NIGHTSCAPE,
        )
    }
}
