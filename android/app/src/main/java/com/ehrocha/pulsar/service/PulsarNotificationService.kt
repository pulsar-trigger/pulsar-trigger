/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Copyright (C) 2026 Pulsar Trigger contributors
 */

package com.ehrocha.pulsar.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.ehrocha.pulsar.MainActivity
import com.ehrocha.pulsar.R

class PulsarNotificationService : Service() {

    companion object {
        const val CHANNEL_OTA = "pulsar_ota"
        const val NOTIFICATION_OTA_ID = 2
        const val ACTION_OTA = "com.ehrocha.pulsar.OTA_PROGRESS"
        const val EXTRA_OTA_TITLE = "ota_title"
        const val EXTRA_OTA_TEXT = "ota_text"
        const val EXTRA_OTA_PROGRESS = "ota_progress"
        const val EXTRA_OTA_DONE = "ota_done"
    }

    private lateinit var notificationManager: NotificationManager

    override fun onCreate() {
        super.onCreate()
        notificationManager = getSystemService(NotificationManager::class.java)
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_OTA) {
            val title = intent.getStringExtra(EXTRA_OTA_TITLE) ?: "Updating…"
            val text = intent.getStringExtra(EXTRA_OTA_TEXT) ?: ""
            val progress = intent.getIntExtra(EXTRA_OTA_PROGRESS, -1)
            val done = intent.getBooleanExtra(EXTRA_OTA_DONE, false)

            if (done) {
                notificationManager.cancel(NOTIFICATION_OTA_ID)
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            } else {
                val notification = buildOtaNotification(title, text, progress)
                startForeground(NOTIFICATION_OTA_ID, notification)
            }
        }

        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createChannel() {
        val otaChannel = NotificationChannel(
            CHANNEL_OTA,
            "Firmware & App Updates",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Shows progress during firmware or app updates"
            setShowBadge(false)
        }
        notificationManager.createNotificationChannel(otaChannel)
    }

    private fun buildOtaNotification(title: String, text: String, progress: Int): Notification {
        val openIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val builder = NotificationCompat.Builder(this, CHANNEL_OTA)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(openIntent)
            .setOngoing(true)
            .setSilent(true)

        when {
            progress in 0..100 -> builder.setProgress(100, progress, false)
            else -> builder.setProgress(0, 0, true)  // indeterminate
        }

        return builder.build()
    }
}
