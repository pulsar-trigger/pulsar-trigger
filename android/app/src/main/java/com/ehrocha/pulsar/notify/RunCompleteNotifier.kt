/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Copyright (C) 2026 Pulsar Trigger contributors
 */

package com.ehrocha.pulsar.notify

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.ehrocha.pulsar.MainActivity
import com.ehrocha.pulsar.R
import com.ehrocha.pulsar.model.ShotLogStatus

/**
 * Fires a one-shot notification when a phone-driven flow finishes
 * (completed, stopped, or aborted). The user has the screen off /
 * is in a tent / is asleep — the notification is how they learn it's
 * done. Tap opens the app on the last screen.
 *
 * Channel-only — not a foreground service. The flow runner already owns
 * its run-screen UI; this is a discrete after-the-fact alert.
 */
object RunCompleteNotifier {
    private const val CHANNEL_ID = "pulsar_run_complete"
    private const val NOTIFICATION_ID = 3

    fun ensureChannel(context: Context) {
        val mgr = context.getSystemService(NotificationManager::class.java) ?: return
        if (mgr.getNotificationChannel(CHANNEL_ID) != null) return
        val ch = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.notif_channel_run_complete),
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = context.getString(R.string.notif_channel_run_complete_desc)
            setShowBadge(true)
        }
        mgr.createNotificationChannel(ch)
    }

    fun post(
        context: Context,
        modeLabel: String,
        completedShots: Int,
        plannedShots: Int,
        durationMs: Long,
        status: ShotLogStatus,
    ) {
        ensureChannel(context)
        val statusStr = when (status) {
            ShotLogStatus.COMPLETED -> context.getString(R.string.notif_run_complete_completed)
            ShotLogStatus.STOPPED -> context.getString(R.string.notif_run_complete_stopped)
            ShotLogStatus.ERROR -> context.getString(R.string.notif_run_complete_error)
        }
        val title = context.getString(R.string.notif_run_complete_title, statusStr, modeLabel)
        val text = context.getString(
            R.string.notif_run_complete_body,
            completedShots,
            plannedShots.coerceAtLeast(completedShots),
            formatDuration(durationMs),
        )
        val openIntent = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(openIntent)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
        } catch (_: SecurityException) {
            // POST_NOTIFICATIONS permission not granted on API 33+ — silently drop.
        }
    }

    private fun formatDuration(ms: Long): String {
        if (ms <= 0) return "—"
        val totalSec = ms / 1000
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val s = totalSec % 60
        return when {
            h > 0 -> "%dh %02dm".format(h, m)
            m > 0 -> "%dm %02ds".format(m, s)
            else -> "%ds".format(s)
        }
    }
}
