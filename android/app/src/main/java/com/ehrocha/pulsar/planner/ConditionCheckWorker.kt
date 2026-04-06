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
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDate
import java.time.format.DateTimeFormatter
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
        val entries = manager.state.value.entries.filter {
            !it.date.isBefore(LocalDate.now())
        }
        if (entries.isEmpty()) return Result.success()

        ensureChannel()

        for (entry in entries) {
            try {
                val verdict = checkConditions(entry)
                manager.updateEntry(entry.copy(
                    lastChecked = System.currentTimeMillis(),
                    verdict = verdict.first,
                    summary = verdict.second,
                ))
                if (verdict.first == PlannerVerdict.EXCELLENT || verdict.first == PlannerVerdict.GOOD) {
                    sendNotification(entry, verdict.second)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to check ${entry.location.name}", e)
            }
        }
        return Result.success()
    }

    private fun checkConditions(entry: PlannerEntry): Pair<PlannerVerdict, String> {
        val loc = entry.location
        val dateStr = entry.date.format(DateTimeFormatter.ISO_LOCAL_DATE)
        val url = URL(
            "https://api.open-meteo.com/v1/forecast" +
                "?latitude=${loc.latitude}&longitude=${loc.longitude}" +
                "&hourly=cloud_cover,precipitation" +
                "&start_date=$dateStr&end_date=$dateStr" +
                "&timezone=auto"
        )
        val conn = url.openConnection() as HttpURLConnection
        conn.connectTimeout = AppConfig.API_CONNECT_TIMEOUT_MS
        conn.readTimeout = AppConfig.API_READ_TIMEOUT_MS
        try {
            val json = JSONObject(conn.inputStream.bufferedReader().readText())
            val hourly = json.getJSONObject("hourly")
            val clouds = hourly.getJSONArray("cloud_cover")
            val precip = hourly.getJSONArray("precipitation")

            // Check night hours (18:00 → 06:00 = indices 18..23, 0..5)
            val nightIndices = (18..23) + (0..5)
            var clearHours = 0
            var totalRain = 0.0
            for (i in nightIndices) {
                if (i < clouds.length()) {
                    if (clouds.getInt(i) <= AppConfig.CLOUD_COVER_CLEAR_THRESHOLD) clearHours++
                    totalRain += precip.getDouble(i)
                }
            }

            val verdict = when {
                totalRain > 1.0 -> PlannerVerdict.POOR
                clearHours >= 8 -> PlannerVerdict.EXCELLENT
                clearHours >= 5 -> PlannerVerdict.GOOD
                clearHours >= 3 -> PlannerVerdict.FAIR
                else -> PlannerVerdict.POOR
            }
            val summary = "$clearHours clear hours, ${String.format("%.1f", totalRain)} mm rain"
            return verdict to summary
        } finally {
            conn.disconnect()
        }
    }

    private fun sendNotification(entry: PlannerEntry, summary: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(applicationContext, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) return

        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("Clear skies at ${entry.location.name}!")
            .setContentText("${entry.date}: $summary")
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .build()

        NotificationManagerCompat.from(applicationContext)
            .notify(entry.id.hashCode(), notification)
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
