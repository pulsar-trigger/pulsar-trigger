/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Copyright (C) 2026 Pulsar Trigger contributors
 */

package com.ehrocha.pulsar.model

import android.content.Context

/**
 * Persists user-authored modes in SharedPreferences. Capped at
 * [UserMode.MAX_USER_MODES] — a generous cap (across all modes) that keeps the
 * preset list scannable and SharedPreferences storage bounded.
 *
 * Modes are stored as serialised JSON. Order is preserved (camera-strip
 * tile order matches list order). Single source of truth — read-modify-write
 * via [load] / [save].
 */
class UserModeRepository(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun load(): List<UserMode> = UserMode.deserializeList(prefs.getString(KEY_MODES, "") ?: "")

    fun save(modes: List<UserMode>) {
        val capped = modes.take(UserMode.MAX_USER_MODES)
        prefs.edit().putString(KEY_MODES, UserMode.serializeList(capped)).apply()
    }

    /** Insert / update a mode by id. New modes append; existing modes update
     *  in place. Returns the new list, capped at the maximum. */
    fun upsert(mode: UserMode): List<UserMode> {
        val current = load().toMutableList()
        val idx = current.indexOfFirst { it.id == mode.id }
        if (idx >= 0) {
            current[idx] = mode
        } else if (current.size < UserMode.MAX_USER_MODES) {
            current.add(mode)
        }
        save(current)
        return current
    }

    fun remove(id: String): List<UserMode> {
        val updated = load().filterNot { it.id == id }
        save(updated)
        return updated
    }

    fun reorder(ids: List<String>): List<UserMode> {
        val byId = load().associateBy { it.id }
        val ordered = ids.mapNotNull { byId[it] }
        // Append any modes that weren't in the supplied order — keeps the list
        // intact if the caller drops one by accident.
        val tail = byId.values.filterNot { it.id in ids.toSet() }
        val updated = (ordered + tail).take(UserMode.MAX_USER_MODES)
        save(updated)
        return updated
    }

    companion object {
        private const val PREFS_NAME = "pulsar_user_modes"
        private const val KEY_MODES = "modes"
    }
}
