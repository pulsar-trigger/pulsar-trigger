/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Copyright (C) 2026 Pulsar Trigger contributors
 */

package com.ehrocha.pulsar.planner

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.*
import com.ehrocha.pulsar.AppConfig
import com.ehrocha.pulsar.R
import java.time.LocalDate
import java.util.concurrent.TimeUnit

class ConditionCheckWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    companion object {
        private const val TAG = "ConditionCheck"
        private const val CHANNEL_ID = "planner_alerts"
        private const val WORK_NAME = "planner_condition_check"

        fun enqueue(context: Context) {
            val request = PeriodicWorkRequestBuilder<ConditionCheckWorker>(
                AppConfig.PLANNER_CHECK_INTERVAL_HOURS, TimeUnit.HOURS,
            ).setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            ).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request,
            )
        }
    }

    override suspend fun doWork(): Result {
        val manager = PlannerManager(applicationContext)
        val state = manager.state.value
        val futureSessions = state.sessions.filter {
            !it.date.isBefore(LocalDate.now())
        }
        if (futureSessions.isEmpty()) return Result.success()

        ensureChannel()

        for (session in futureSessions) {
            val event = manager.eventById(session.eventId) ?: continue
            try {
                val verdict = manager.fetchConditions(session)
                manager.updateSession(session.copy(
                    lastChecked = System.currentTimeMillis(),
                    verdict = verdict.first,
                    summary = verdict.second,
                ))
                if (verdict.first == PlannerVerdict.EXCELLENT || verdict.first == PlannerVerdict.GOOD) {
                    sendNotification(event, session, verdict.second)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to check ${session.name}", e)
            }
        }
        return Result.success()
    }

    private fun sendNotification(event: PlannerEvent, session: PlannerSession, summary: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(applicationContext, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) return

        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("Clear skies for ${session.name}!")
            .setContentText("${event.name} · ${session.date}: $summary")
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .build()

        NotificationManagerCompat.from(applicationContext)
            .notify(session.id.hashCode(), notification)
    }

    private fun ensureChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Planner Alerts",
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply { description = "Condition alerts for planned sessions" }
        (applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .createNotificationChannel(channel)
    }
}
