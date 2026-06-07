/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Copyright (C) 2026 Pulsar Trigger contributors
 */

package com.ehrocha.pulsar.widget

import android.content.Context
import android.util.Log
import androidx.glance.appwidget.updateAll
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.ehrocha.pulsar.astro.AstroDashboardManager
import java.time.LocalDate

/** Periodic widget refresh. Builds a fresh [com.ehrocha.pulsar.astro.DashboardState]
 *  via [AstroDashboardManager.refresh] (which itself respects the cache), writes
 *  the serialized snapshot to [DashboardSnapshotStore], then asks the Glance
 *  runtime to redraw any active widgets. */
class DashboardWidgetWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    companion object {
        const val WORK_NAME = "dashboard_widget_refresh"
        private const val TAG = "DashWidgetWorker"
    }

    override suspend fun doWork(): Result {
        return try {
            val manager = AstroDashboardManager(applicationContext)
            val today = LocalDate.now()

            // Prefer the location baked into the previous snapshot. Background
            // workers on Android 10+ can't reach `getLastKnownLocation` without
            // ACCESS_BACKGROUND_LOCATION, so the GPS-based refresh path bails
            // silently and the widget's own refresh button never lands a new
            // snapshot. The user has been in the app once already (or no
            // snapshot would exist), so the cached lat/lon is fine.
            val cached = DashboardSnapshotStore.load(applicationContext)
            val cachedLoc = cached?.json
                ?.let { if (manager.restoreState(it)) manager.state.value.location else null }
            if (cachedLoc != null) {
                manager.refreshForLocation(
                    lat = cachedLoc.latitude,
                    lon = cachedLoc.longitude,
                    cityName = cachedLoc.cityName,
                    date = today,
                )
            } else {
                manager.refresh(today)
            }
            val s = manager.state.value
            if (s.location != null && (s.moon != null || s.sun != null)) {
                DashboardSnapshotStore.save(applicationContext, manager.serializeState())
                DashboardWidget().updateAll(applicationContext)
                Log.i(TAG, "snapshot refreshed (lastUpdated=${s.lastUpdated})")
            } else {
                Log.i(TAG, "skip save — incomplete state (loc=${s.location != null})")
            }
            Result.success()
        } catch (t: Throwable) {
            Log.w(TAG, "refresh failed", t)
            Result.retry()
        }
    }
}
