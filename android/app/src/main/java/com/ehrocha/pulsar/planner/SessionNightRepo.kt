/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Copyright (C) 2026 Pulsar Trigger contributors
 */

package com.ehrocha.pulsar.planner

import android.content.Context
import com.ehrocha.pulsar.astro.AstroDashboardManager
import com.ehrocha.pulsar.astro.NightModel
import com.ehrocha.pulsar.astro.buildNightModel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.LocalDate
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs

/**
 * Provides a [NightModel] per planner session — the same single-source
 * "tonight" model the Sky Dial and the widget render; the planner's Night
 * Strips are its third renderer.
 *
 * Cache-first: a session's serialized dashboard (written by the session
 * detail screen and by [get] itself) is restored and reduced to a NightModel.
 * On a miss, [get] optionally fetches the full dashboard for the session's
 * location/date and caches it — so opening the detail later is instant too.
 * Fetches are serialized through one mutex: a list of sessions composing at
 * once must not fan N parallel request bursts at the forecast API.
 *
 * Models are memoized per (id, date, lat, lon) so editing a session
 * invalidates naturally. Screen-scoped (create with `remember`), so the memo
 * lives as long as the screen.
 */
class SessionNightRepo(
    private val context: Context,
    private val plannerManager: PlannerManager,
) {
    private val memo = ConcurrentHashMap<String, NightModel>()
    private val fetchMutex = Mutex()
    /** Sessions whose fetch already failed this screen-lifetime — don't retry
     *  on every recomposition (the list would hammer a dead network). */
    private val failed = ConcurrentHashMap.newKeySet<String>()

    private fun key(s: PlannerSession) =
        "${s.id}:${s.date}:${s.latitude}:${s.longitude}"

    /** Whether the forecast API can have data for this date (past nights keep
     *  whatever was cached; far futures have astro-only models). */
    fun canFetch(session: PlannerSession): Boolean {
        val today = LocalDate.now()
        return !session.date.isBefore(today) &&
            !session.date.isAfter(today.plusDays(15))
    }

    /** NightModel for [session]: memo → dashboard cache → (optionally) a full
     *  dashboard fetch. Null when nothing is available (no cache + fetch not
     *  allowed/failed). Safe to call per list row. */
    suspend fun get(session: PlannerSession, allowFetch: Boolean = true): NightModel? {
        val k = key(session)
        memo[k]?.let { return it }

        // A throwaway manager per call: it's a thin state holder, and sharing
        // one across concurrent rows would race its single StateFlow.
        val mgr = AstroDashboardManager(context)
        val cached = plannerManager.getCachedDashboard(session.id)
        var restored = cached != null && mgr.restoreState(cached)
        // A cached dashboard for an OLD date/location (session was edited)
        // must not masquerade as this session's night.
        if (restored) {
            val st = mgr.state.value
            val loc = st.location
            if (st.selectedDate != session.date || loc == null ||
                abs(loc.latitude - session.latitude) > 1e-4 ||
                abs(loc.longitude - session.longitude) > 1e-4
            ) restored = false
        }

        if (!restored) {
            if (!allowFetch || !canFetch(session) || k in failed) return null
            fetchMutex.withLock {
                memo[k]?.let { return it }
                mgr.refreshForLocation(
                    session.latitude, session.longitude, session.name, session.date,
                )
                if (mgr.state.value.location != null) {
                    plannerManager.putCachedDashboard(session.id, mgr.serializeState())
                } else {
                    failed.add(k)
                    return null
                }
            }
        }

        return buildNightModel(mgr.state.value)?.also { memo[k] = it }
    }
}
