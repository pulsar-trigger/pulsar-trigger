/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Copyright (C) 2026 Pulsar Trigger contributors
 */

package com.ehrocha.pulsar.model

import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray

/**
 * Ring-buffered shoot history backed by SharedPreferences. Keeps the most
 * recent [MAX_ENTRIES] runs. Entries are immutable once written; callers
 * append via [record] and observe via [entries].
 */
class ShotLog(private val prefs: SharedPreferences) {

    companion object {
        private const val KEY = "shot_log_v1"
        const val MAX_ENTRIES = 50
    }

    private val _entries = MutableStateFlow<List<ShotLogEntry>>(load())
    val entries: StateFlow<List<ShotLogEntry>> = _entries

    private fun load(): List<ShotLogEntry> {
        val raw = prefs.getString(KEY, null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { ShotLogEntry.fromJson(arr.getJSONObject(it)) }
        }.getOrDefault(emptyList())
    }

    private fun save(list: List<ShotLogEntry>) {
        val arr = JSONArray()
        list.forEach { arr.put(it.toJson()) }
        prefs.edit().putString(KEY, arr.toString()).apply()
    }

    fun record(entry: ShotLogEntry) {
        val next = (listOf(entry) + _entries.value).take(MAX_ENTRIES)
        _entries.value = next
        save(next)
    }

    fun clear() {
        _entries.value = emptyList()
        prefs.edit().remove(KEY).apply()
    }

    /** Find the most-recent completed bulb-style session whose exposure +
     *  shot count make sense to match darks against. Used by the Dark Frame
     *  wizard's "Pair with last lights" affordance.
     *
     *  Eligible: INTERVALOMETER (with a real bulb exposure, not the
     *  timelapse pulse-length sentinel), ASTRO, RAMP, CUSTOM.
     *  Excluded: TIMELAPSE (camera owns exposure), DARK_FRAME (don't pair
     *  darks with darks), anything STOPPED / ERROR (incomplete data).
     */
    fun findLastLightSession(): ShotLogEntry? = _entries.value.firstOrNull { e ->
        e.status == ShotLogStatus.COMPLETED &&
            e.completedShots > 0 &&
            e.exposureMs > 0 &&
            e.modeLabel != "DARK_FRAME" &&
            e.modeLabel != "TIMELAPSE"
    }
}
