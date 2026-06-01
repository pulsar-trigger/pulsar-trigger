/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Copyright (C) 2026 Pulsar Trigger contributors
 */

package com.ehrocha.pulsar.widget

import android.content.Context
import androidx.glance.GlanceId
import androidx.glance.action.ActionParameters
import androidx.glance.appwidget.action.ActionCallback
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager

/** Tapped on the refresh icon — enqueue a one-shot [DashboardWidgetWorker]
 *  that pulls fresh data, persists the snapshot, and redraws the widget. */
class DashboardRefreshAction : ActionCallback {
    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: ActionParameters,
    ) {
        val request = OneTimeWorkRequestBuilder<DashboardWidgetWorker>().build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            "dashboard_widget_refresh_oneshot",
            ExistingWorkPolicy.REPLACE,
            request,
        )
    }
}
