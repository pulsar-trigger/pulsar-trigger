/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Copyright (C) 2026 Pulsar Trigger contributors
 */

package com.ehrocha.pulsar.planner

import android.content.Context
import com.ehrocha.pulsar.AppConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.util.UUID

class PlannerManager(context: Context) {

    companion object {
        private const val PREFS = "planner_data"
        private const val KEY_LOCATIONS = "locations"
        private const val KEY_ENTRIES = "entries"
    }

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val _state = MutableStateFlow(PlannerState())
    val state: StateFlow<PlannerState> = _state

    init { load() }

    // ── Locations ────────────────────────────────────────────────────

    fun addLocation(name: String, lat: Double, lon: Double): SavedLocation {
        val loc = SavedLocation(UUID.randomUUID().toString(), name, lat, lon)
        val current = _state.value
        if (current.locations.size >= AppConfig.MAX_SAVED_LOCATIONS) return loc
        _state.value = current.copy(locations = current.locations + loc)
        save()
        return loc
    }

    fun removeLocation(id: String) {
        val current = _state.value
        _state.value = current.copy(
            locations = current.locations.filter { it.id != id },
            entries = current.entries.filter { it.location.id != id },
        )
        save()
    }

    // ── Planner entries ──────────────────────────────────────────────

    fun addEntry(location: SavedLocation, date: LocalDate): PlannerEntry {
        val entry = PlannerEntry(UUID.randomUUID().toString(), location, date)
        val current = _state.value
        if (current.entries.size >= AppConfig.MAX_PLANNER_ENTRIES) return entry
        _state.value = current.copy(entries = current.entries + entry)
        save()
        return entry
    }

    fun addEntries(location: SavedLocation, startDate: LocalDate, endDate: LocalDate) {
        var current = _state.value
        var date = startDate
        while (!date.isAfter(endDate) && current.entries.size < AppConfig.MAX_PLANNER_ENTRIES) {
            val entry = PlannerEntry(UUID.randomUUID().toString(), location, date)
            current = current.copy(entries = current.entries + entry)
            date = date.plusDays(1)
        }
        _state.value = current
        save()
    }

    fun removeEntry(id: String) {
        val current = _state.value
        _state.value = current.copy(entries = current.entries.filter { it.id != id })
        save()
    }

    fun updateEntry(entry: PlannerEntry) {
        val current = _state.value
        _state.value = current.copy(
            entries = current.entries.map { if (it.id == entry.id) entry else it }
        )
        save()
    }

    // ── Persistence ──────────────────────────────────────────────────

    private fun save() {
        val state = _state.value
        val locsJson = JSONArray().apply {
            state.locations.forEach { loc ->
                put(JSONObject().apply {
                    put("id", loc.id)
                    put("name", loc.name)
                    put("lat", loc.latitude)
                    put("lon", loc.longitude)
                })
            }
        }
        val entriesJson = JSONArray().apply {
            state.entries.forEach { e ->
                put(JSONObject().apply {
                    put("id", e.id)
                    put("locId", e.location.id)
                    put("date", e.date.toString())
                    put("lastChecked", e.lastChecked)
                    put("verdict", e.verdict.name)
                    put("summary", e.summary)
                })
            }
        }
        prefs.edit()
            .putString(KEY_LOCATIONS, locsJson.toString())
            .putString(KEY_ENTRIES, entriesJson.toString())
            .apply()
    }

    private fun load() {
        val locsStr = prefs.getString(KEY_LOCATIONS, null) ?: return
        val entriesStr = prefs.getString(KEY_ENTRIES, null) ?: "[]"
        try {
            val locsArr = JSONArray(locsStr)
            val locations = (0 until locsArr.length()).map { i ->
                val j = locsArr.getJSONObject(i)
                SavedLocation(j.getString("id"), j.getString("name"),
                    j.getDouble("lat"), j.getDouble("lon"))
            }
            val entriesArr = JSONArray(entriesStr)
            val entries = (0 until entriesArr.length()).mapNotNull { i ->
                val j = entriesArr.getJSONObject(i)
                val locId = j.getString("locId")
                val loc = locations.find { it.id == locId } ?: return@mapNotNull null
                PlannerEntry(
                    id = j.getString("id"),
                    location = loc,
                    date = LocalDate.parse(j.getString("date")),
                    lastChecked = j.optLong("lastChecked", 0),
                    verdict = PlannerVerdict.entries.firstOrNull { it.name == j.optString("verdict") }
                        ?: PlannerVerdict.UNKNOWN,
                    summary = j.optString("summary", ""),
                )
            }
            _state.value = PlannerState(locations, entries)
        } catch (_: Exception) { /* corrupt prefs — start fresh */ }
    }
}
