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
        const val CHANNEL_ID = "pulsar_job"
        const val NOTIFICATION_ID = 1
        const val ACTION_CANCEL = "com.ehrocha.pulsar.CANCEL_JOB"
        const val EXTRA_MODE = "mode"
        const val EXTRA_SHOTS = "shots"
        const val EXTRA_TOTAL = "total"
        const val EXTRA_STATE = "state"
    }

    private lateinit var notificationManager: NotificationManager

    override fun onCreate() {
        super.onCreate()
        notificationManager = getSystemService(NotificationManager::class.java)
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) {
            // Broadcast cancel so the ViewModel can pick it up
            sendBroadcast(Intent(ACTION_CANCEL).setPackage(packageName))
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        val mode = intent?.getStringExtra(EXTRA_MODE) ?: "Running"
        val shots = intent?.getIntExtra(EXTRA_SHOTS, 0) ?: 0
        val total = intent?.getIntExtra(EXTRA_TOTAL, 0) ?: 0
        val state = intent?.getStringExtra(EXTRA_STATE) ?: "RUNNING"

        val notification = buildNotification(mode, shots, total, state)
        startForeground(NOTIFICATION_ID, notification)

        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Pulsar Job Progress",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Shows progress while a trigger job is running"
            setShowBadge(false)
        }
        notificationManager.createNotificationChannel(channel)
    }

    private fun buildNotification(mode: String, shots: Int, total: Int, state: String): Notification {
        // Tap → open app
        val openIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        // Cancel action
        val cancelIntent = PendingIntent.getService(
            this, 1,
            Intent(this, PulsarNotificationService::class.java).apply {
                action = ACTION_CANCEL
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val progressText = if (total > 0) "$shots / $total shots" else "$shots shots"
        val subtitle = "$mode  •  $progressText"

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Pulsar — $state")
            .setContentText(subtitle)
            .setContentIntent(openIntent)
            .setOngoing(true)
            .setSilent(true)
            .addAction(0, "Cancel", cancelIntent)

        if (total > 0) {
            builder.setProgress(total, shots.coerceAtMost(total), false)
        } else {
            builder.setProgress(0, 0, true)  // indeterminate
        }

        return builder.build()
    }
}
