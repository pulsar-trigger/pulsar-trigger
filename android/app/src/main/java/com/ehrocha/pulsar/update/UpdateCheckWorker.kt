/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Copyright (C) 2026 Pulsar Trigger contributors
 */

package com.ehrocha.pulsar.update

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.ehrocha.pulsar.BuildConfig
import com.ehrocha.pulsar.MainActivity
import com.ehrocha.pulsar.R

/**
 * Periodic WorkManager worker that silently checks GitHub Releases for a
 * newer APK. When one is found it posts a notification — the user can then
 * open the app to download/install.
 */
class UpdateCheckWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    companion object {
        private const val TAG = "UpdateCheckWorker"
        const val WORK_NAME = "app_update_check"
        private const val CHANNEL_ID = "pulsar_ota"
        private const val NOTIFICATION_ID = 9001
    }

    override suspend fun doWork(): Result {
        return try {
            val asset = fetchGitHubRelease(tagPrefix = "app-v", assetSuffix = ".apk")
            if (asset != null && isNewerVersion(asset.version, BuildConfig.VERSION_NAME)) {
                Log.i(TAG, "New app version available: ${asset.version} (current: ${BuildConfig.VERSION_NAME})")
                showUpdateNotification(asset.version)
            } else {
                Log.d(TAG, "App is up to date (${BuildConfig.VERSION_NAME})")
            }
            Result.success()
        } catch (e: Exception) {
            Log.w(TAG, "Update check failed", e)
            Result.retry()
        }
    }

    private fun showUpdateNotification(version: String) {
        val nm = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE)
                as NotificationManager

        // Ensure the channel exists (safe to call repeatedly)
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Firmware & App Updates",
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = "Notifies when a new app or firmware version is available"
        }
        nm.createNotificationChannel(channel)

        val openIntent = PendingIntent.getActivity(
            applicationContext, 0,
            Intent(applicationContext, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(applicationContext.getString(R.string.app_name))
            .setContentText(
                applicationContext.getString(R.string.notif_update_available, version)
            )
            .setAutoCancel(true)
            .setContentIntent(openIntent)
            .build()

        nm.notify(NOTIFICATION_ID, notification)
    }
}
