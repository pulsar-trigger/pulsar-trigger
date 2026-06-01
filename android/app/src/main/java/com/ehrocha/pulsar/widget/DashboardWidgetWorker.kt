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
            manager.refresh(LocalDate.now())
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
