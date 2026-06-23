/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Copyright (C) 2026 Pulsar Trigger contributors
 */

package com.ehrocha.pulsar.planner

import android.content.Context
import android.content.SharedPreferences
import com.ehrocha.pulsar.AppConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.UUID

class PlannerManager(context: Context) {

    companion object {
        private const val PREFS = "planner_data"
        private const val CACHE_PREFS = "planner_dashboard_cache"
        private const val KEY_EVENTS = "events"
        private const val KEY_SESSIONS = "sessions"
        private const val KEY_CACHE_INTERVAL = "cache_interval_hours"
        private const val KEY_CLOUD_THRESHOLD = "cloud_clear_threshold"
        private const val TAG = "PlannerManager"
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

    /** User-configurable cloud cover threshold (%) for "clear" verdict. */
    var cloudClearThreshold: Int
        get() = prefs.getInt(KEY_CLOUD_THRESHOLD, AppConfig.CLOUD_COVER_CLEAR_THRESHOLD)
        set(value) { prefs.edit().putInt(KEY_CLOUD_THRESHOLD, value.coerceIn(5, 80)).apply() }

    /** Full planner snapshot for the settings backup — events, sessions, and
     *  the two tunables. Mirrors the on-disk keys so it round-trips exactly. */
    fun exportAll(): JSONObject = JSONObject().apply {
        put("events", prefs.getString(KEY_EVENTS, "[]"))
        put("sessions", prefs.getString(KEY_SESSIONS, "[]"))
        put("cloud_clear_threshold", cloudClearThreshold)
        put("cache_interval_hours", cacheIntervalHours)
    }

    /** Restore a snapshot from [exportAll]. Replaces the current planner data. */
    fun importAll(o: JSONObject) {
        prefs.edit()
            .putString(KEY_EVENTS, o.optString("events", "[]"))
            .putString(KEY_SESSIONS, o.optString("sessions", "[]"))
            .apply()
        if (o.has("cloud_clear_threshold")) cloudClearThreshold = o.getInt("cloud_clear_threshold")
        if (o.has("cache_interval_hours")) cacheIntervalHours = o.getLong("cache_interval_hours")
        load()
    }

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

    // ── On-demand condition check ────────────────────────────────────

    /** Fetch weather forecast and update the session verdict. */
    suspend fun checkSessionConditions(session: PlannerSession) {
        val (verdict, summary) = withContext(Dispatchers.IO) { fetchConditions(session) }
        updateSession(session.copy(
            lastChecked = System.currentTimeMillis(),
            verdict = verdict,
            summary = summary,
        ))
    }

    internal fun fetchConditions(session: PlannerSession): Pair<PlannerVerdict, String> {
        val dateStr = session.date.format(DateTimeFormatter.ISO_LOCAL_DATE)
        // Fetch next day too so we can cover the full night (sunset today → sunrise tomorrow)
        val nextDateStr = session.date.plusDays(1).format(DateTimeFormatter.ISO_LOCAL_DATE)
        val url = URL(
            "https://api.open-meteo.com/v1/forecast" +
                "?latitude=${session.latitude}&longitude=${session.longitude}" +
                "&hourly=cloud_cover,precipitation" +
                "&daily=sunset,sunrise" +
                "&start_date=$dateStr&end_date=$nextDateStr" +
                "&timezone=auto"
        )
        val conn = url.openConnection() as HttpURLConnection
        conn.connectTimeout = AppConfig.API_CONNECT_TIMEOUT_MS
        conn.readTimeout = AppConfig.API_READ_TIMEOUT_MS
        try {
            val responseCode = conn.responseCode
            if (responseCode != 200) {
                android.util.Log.w(TAG, "Weather API returned $responseCode for ${session.name}")
                return PlannerVerdict.UNKNOWN to "Weather data unavailable (HTTP $responseCode)"
            }
            val json = JSONObject(conn.inputStream.bufferedReader().readText())
            val hourly = json.getJSONObject("hourly")
            val clouds = hourly.getJSONArray("cloud_cover")
            val precip = hourly.getJSONArray("precipitation")
            val times = hourly.getJSONArray("time")

            // Determine night window from actual sunset/sunrise
            val daily = json.getJSONObject("daily")
            val sunsetStr = daily.getJSONArray("sunset").getString(0)    // today's sunset
            val sunriseStr = daily.getJSONArray("sunrise").getString(1)  // tomorrow's sunrise
            val sunsetHour = LocalTime.parse(sunsetStr.substring(11)).hour
            val sunriseHour = LocalTime.parse(sunriseStr.substring(11)).hour + 24 // next day offset

            // Build night indices: from sunset hour today to sunrise hour tomorrow
            // Hours are indexed 0-23 for day 1 and 24-47 for day 2
            val nightStart = sunsetHour
            val nightEnd = sunriseHour.coerceAtMost(times.length())
            val threshold = cloudClearThreshold

            var clearHours = 0
            var totalRain = 0.0
            var firstClearHour = -1
            var lastClearHour = -1
            val nightHourCount = (nightEnd - nightStart).coerceAtLeast(0)

            for (h in nightStart until nightEnd) {
                if (h < clouds.length()) {
                    if (clouds.getInt(h) <= threshold) {
                        clearHours++
                        if (firstClearHour == -1) firstClearHour = h
                        lastClearHour = h
                    }
                    totalRain += precip.getDouble(h)
                }
            }

            val verdict = when {
                totalRain > 1.0 -> PlannerVerdict.POOR
                nightHourCount > 0 && clearHours >= nightHourCount * 3 / 4 -> PlannerVerdict.EXCELLENT
                nightHourCount > 0 && clearHours >= nightHourCount / 2 -> PlannerVerdict.GOOD
                clearHours >= 3 -> PlannerVerdict.FAIR
                else -> PlannerVerdict.POOR
            }

            // Build descriptive summary with time ranges
            val summary = buildString {
                append("$clearHours clear of $nightHourCount night hours")
                if (firstClearHour >= 0 && clearHours > 0) {
                    val startH = firstClearHour % 24
                    val endH = (lastClearHour + 1) % 24
                    append(" (%02d:00–%02d:00)".format(startH, endH))
                }
                append(", %.1f mm rain".format(totalRain))
            }
            return verdict to summary
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Failed to fetch conditions for ${session.name}", e)
            return PlannerVerdict.UNKNOWN to "Error: ${e.message ?: "network failure"}"
        } finally {
            conn.disconnect()
        }
    }

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
