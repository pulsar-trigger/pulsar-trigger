/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Copyright (C) 2026 Pulsar Trigger contributors
 */

package com.ehrocha.pulsar.planner

import android.content.Context
import android.content.SharedPreferences
import com.ehrocha.pulsar.AppConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID

class PlannerManager(context: Context) {

    companion object {
        private const val PREFS = "planner_data"
        private const val CACHE_PREFS = "planner_dashboard_cache"
        private const val KEY_EVENTS = "events"
        private const val KEY_SESSIONS = "sessions"
        private const val KEY_CACHE_INTERVAL = "cache_interval_hours"
    }

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val cachePrefs: SharedPreferences =
        context.getSharedPreferences(CACHE_PREFS, Context.MODE_PRIVATE)

    private val _state = MutableStateFlow(PlannerState())
    val state: StateFlow<PlannerState> = _state

    /** User-configurable cache refresh interval (hours). */
    var cacheIntervalHours: Long
        get() = cachePrefs.getLong(KEY_CACHE_INTERVAL, AppConfig.PLANNER_DASHBOARD_CACHE_HOURS_DEFAULT)
        set(value) { cachePrefs.edit().putLong(KEY_CACHE_INTERVAL, value).apply() }

    init { load() }

    // ── Events ───────────────────────────────────────────────────────

    fun addEvent(
        name: String,
        startDate: LocalDate,
        endDate: LocalDate,
    ): PlannerEvent {
        val event = PlannerEvent(
            id = UUID.randomUUID().toString(),
            name = name,
            startDate = startDate,
            endDate = endDate,
        )
        val current = _state.value
        if (current.events.size >= AppConfig.MAX_SAVED_LOCATIONS) return event

        _state.value = current.copy(events = current.events + event)
        save()
        return event
    }

    fun removeEvent(id: String) {
        val current = _state.value
        _state.value = current.copy(
            events = current.events.filter { it.id != id },
            sessions = current.sessions.filter { it.eventId != id },
        )
        save()
    }

    // ── Sessions ─────────────────────────────────────────────────────

    fun addSession(
        eventId: String,
        name: String,
        lat: Double,
        lon: Double,
        date: LocalDate,
        startTime: LocalTime? = null,
        endTime: LocalTime? = null,
    ): PlannerSession? {
        val current = _state.value
        if (current.sessions.size >= AppConfig.MAX_PLANNER_ENTRIES) return null
        val session = PlannerSession(
            id = UUID.randomUUID().toString(),
            eventId = eventId,
            name = name,
            latitude = lat,
            longitude = lon,
            date = date,
            startTime = startTime,
            endTime = endTime,
        )
        _state.value = current.copy(sessions = current.sessions + session)
        save()
        return session
    }

    fun removeSession(id: String) {
        val current = _state.value
        _state.value = current.copy(sessions = current.sessions.filter { it.id != id })
        save()
    }

    fun updateSession(session: PlannerSession) {
        val current = _state.value
        _state.value = current.copy(
            sessions = current.sessions.map { if (it.id == session.id) session else it }
        )
        save()
    }

    fun sessionsForEvent(eventId: String): List<PlannerSession> =
        _state.value.sessions.filter { it.eventId == eventId }.sortedBy { it.date }

    fun eventById(eventId: String): PlannerEvent? =
        _state.value.events.find { it.id == eventId }

    // ── Sharing (export / import) ────────────────────────────────────

    fun exportEvent(eventId: String): String? {
        val event = eventById(eventId) ?: return null
        val sessions = sessionsForEvent(eventId)
        return JSONObject().apply {
            put("v", 2)
            put("event", JSONObject().apply {
                put("name", event.name)
                put("startDate", event.startDate.toString())
                put("endDate", event.endDate.toString())
            })
            put("sessions", JSONArray().apply {
                sessions.forEach { s ->
                    put(JSONObject().apply {
                        put("name", s.name)
                        put("lat", s.latitude)
                        put("lon", s.longitude)
                        put("date", s.date.toString())
                        s.startTime?.let { put("startTime", it.toString()) }
                        s.endTime?.let { put("endTime", it.toString()) }
                    })
                }
            })
        }.toString(2)
    }

    fun importEvent(json: String): PlannerEvent? {
        return try {
            val root = JSONObject(json)
            val e = root.getJSONObject("event")
            val event = addEvent(
                name = e.getString("name"),
                startDate = LocalDate.parse(e.getString("startDate")),
                endDate = LocalDate.parse(e.getString("endDate")),
            )
            val sessionsArr = root.optJSONArray("sessions")
            if (sessionsArr != null) {
                for (i in 0 until sessionsArr.length()) {
                    val s = sessionsArr.getJSONObject(i)
                    addSession(
                        eventId = event.id,
                        name = s.getString("name"),
                        lat = s.getDouble("lat"),
                        lon = s.getDouble("lon"),
                        date = LocalDate.parse(s.getString("date")),
                        startTime = s.optString("startTime", "").takeIf { it.isNotEmpty() }?.let { LocalTime.parse(it) },
                        endTime = s.optString("endTime", "").takeIf { it.isNotEmpty() }?.let { LocalTime.parse(it) },
                    )
                }
            }
            event
        } catch (_: Exception) {
            null
        }
    }

    // ── Persistence ──────────────────────────────────────────────────

    private fun save() {
        val state = _state.value
        val eventsJson = JSONArray().apply {
            state.events.forEach { ev ->
                put(JSONObject().apply {
                    put("id", ev.id)
                    put("name", ev.name)
                    put("startDate", ev.startDate.toString())
                    put("endDate", ev.endDate.toString())
                })
            }
        }
        val sessionsJson = JSONArray().apply {
            state.sessions.forEach { s ->
                put(JSONObject().apply {
                    put("id", s.id)
                    put("eventId", s.eventId)
                    put("name", s.name)
                    put("lat", s.latitude)
                    put("lon", s.longitude)
                    put("date", s.date.toString())
                    s.startTime?.let { put("startTime", it.toString()) }
                    s.endTime?.let { put("endTime", it.toString()) }
                    put("lastChecked", s.lastChecked)
                    put("verdict", s.verdict.name)
                    put("summary", s.summary)
                })
            }
        }
        prefs.edit()
            .putString(KEY_EVENTS, eventsJson.toString())
            .putString(KEY_SESSIONS, sessionsJson.toString())
            .apply()
    }

    private fun load() {
        val eventsStr = prefs.getString(KEY_EVENTS, null) ?: return
        val sessionsStr = prefs.getString(KEY_SESSIONS, null) ?: "[]"
        try {
            val eventsArr = JSONArray(eventsStr)
            val events = (0 until eventsArr.length()).map { i ->
                val j = eventsArr.getJSONObject(i)
                PlannerEvent(
                    id = j.getString("id"),
                    name = j.getString("name"),
                    startDate = LocalDate.parse(j.getString("startDate")),
                    endDate = LocalDate.parse(j.getString("endDate")),
                )
            }
            val sessionsArr = JSONArray(sessionsStr)
            val eventIds = events.map { it.id }.toSet()
            val sessions = (0 until sessionsArr.length()).mapNotNull { i ->
                val j = sessionsArr.getJSONObject(i)
                val eventId = j.getString("eventId")
                if (eventId !in eventIds) return@mapNotNull null
                PlannerSession(
                    id = j.getString("id"),
                    eventId = eventId,
                    name = j.optString("name", ""),
                    latitude = j.optDouble("lat", 0.0),
                    longitude = j.optDouble("lon", 0.0),
                    date = LocalDate.parse(j.getString("date")),
                    startTime = j.optString("startTime", "").takeIf { it.isNotEmpty() }?.let { LocalTime.parse(it) },
                    endTime = j.optString("endTime", "").takeIf { it.isNotEmpty() }?.let { LocalTime.parse(it) },
                    lastChecked = j.optLong("lastChecked", 0),
                    verdict = PlannerVerdict.entries.firstOrNull { it.name == j.optString("verdict") }
                        ?: PlannerVerdict.UNKNOWN,
                    summary = j.optString("summary", ""),
                )
            }
            _state.value = PlannerState(events, sessions)
        } catch (_: Exception) { /* corrupt prefs — start fresh */ }
    }

    // ── Session dashboard cache ──────────────────────────────────────

    fun getCachedDashboard(sessionId: String): String? {
        val ts = cachePrefs.getLong("ts_$sessionId", 0L)
        if (ts == 0L) return null
        val age = System.currentTimeMillis() - ts
        if (age > cacheIntervalHours * 3_600_000L) return null
        return cachePrefs.getString("data_$sessionId", null)
    }

    fun putCachedDashboard(sessionId: String, json: String) {
        cachePrefs.edit()
            .putString("data_$sessionId", json)
            .putLong("ts_$sessionId", System.currentTimeMillis())
            .apply()
    }
}
