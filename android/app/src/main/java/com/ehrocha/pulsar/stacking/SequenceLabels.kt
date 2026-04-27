/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Copyright (C) 2026 Pulsar Trigger contributors
 */

package com.ehrocha.pulsar.stacking

import android.content.Context
import androidx.core.content.edit

/**
 * Per-sequence custom labels. Disk folder names stay immutable
 * (`DCIM/Pulsar/Sequence_<ts>/`); the user-facing label is overlaid from prefs
 * so we never have to rewrite RELATIVE_PATH on every frame in the sequence.
 */
object SequenceLabels {
    private const val PREFS = "sequence_labels"

    fun get(context: Context, path: String): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(path, null)

    fun set(context: Context, path: String, label: String?) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit {
            if (label.isNullOrBlank()) remove(path)
            else putString(path, label.trim())
        }
    }
}
