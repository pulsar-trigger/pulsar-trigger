/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Copyright (C) 2026 Pulsar Trigger contributors
 */

package com.ehrocha.pulsar

import android.app.Application
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.ehrocha.pulsar.canonble.CrashPersister
import com.ehrocha.pulsar.update.UpdateCheckWorker
import com.ehrocha.pulsar.widget.DashboardWidgetWorker
import java.util.concurrent.TimeUnit

class PulsarApp : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashPersister.install(this)
        scheduleUpdateCheck()
        scheduleDashboardWidgetRefresh()
    }

    private fun scheduleUpdateCheck() {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val request = PeriodicWorkRequestBuilder<UpdateCheckWorker>(
            AppConfig.UPDATE_CHECK_INTERVAL_HOURS, TimeUnit.HOURS,
        ).setConstraints(constraints)
            .build()

        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            UpdateCheckWorker.WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            request,
        )
    }

    /** Refresh the home-screen widget's cached dashboard snapshot every 3 h.
     *  The dashboard data (weather + astronomy) changes slowly, so 3 h is
     *  plenty to keep the widget useful without burning battery. Also kicks
     *  off a one-shot run on first launch so a freshly-placed widget paints
     *  real data without requiring the user to open the Dashboard tab. */
    private fun scheduleDashboardWidgetRefresh() {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val periodic = PeriodicWorkRequestBuilder<DashboardWidgetWorker>(
            3, TimeUnit.HOURS,
        ).setConstraints(constraints)
            .build()

        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            DashboardWidgetWorker.WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            periodic,
        )

        // One-shot on first launch — the periodic schedule's initial delay
        // could be hours, leaving a fresh widget stuck on the loading layout.
        val oneShot = OneTimeWorkRequestBuilder<DashboardWidgetWorker>()
            .setConstraints(constraints)
            .build()
        WorkManager.getInstance(this).enqueueUniqueWork(
            "${DashboardWidgetWorker.WORK_NAME}_oneshot_bootstrap",
            ExistingWorkPolicy.KEEP,
            oneShot,
        )
    }
}
