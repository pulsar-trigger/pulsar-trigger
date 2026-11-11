/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Copyright (C) 2026 Pulsar Trigger contributors
 */

package com.ehrocha.pulsar.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.ehrocha.pulsar.MainActivity
import com.ehrocha.pulsar.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * The app's one foreground service. Two independent jobs share it:
 *
 * - **OTA progress** — driven by `ACTION_OTA` intents (one per update).
 * - **Run in progress** — started once per run with `ACTION_RUN_START`, then
 *   follows [RunSession.notice] until it goes null. Shows mode, shots and time
 *   left, with a single-tap Stop action (`ACTION_RUN_STOP` → [RunSession]).
 *
 * The service stays in the foreground while either job is live; whichever
 * notification is current owns the foreground slot. If POST_NOTIFICATIONS is
 * denied the service still runs — Android just hides its notification.
 */
class PulsarNotificationService : Service() {

    companion object {
        const val CHANNEL_OTA = "pulsar_ota"
        const val CHANNEL_RUN = "pulsar_run_active"
        const val NOTIFICATION_OTA_ID = 2
        const val NOTIFICATION_RUN_ID = 4
        const val ACTION_OTA = "com.ehrocha.pulsar.OTA_PROGRESS"
        const val ACTION_RUN_START = "com.ehrocha.pulsar.RUN_START"
        const val ACTION_RUN_STOP = "com.ehrocha.pulsar.RUN_STOP"
        const val EXTRA_OTA_TITLE = "ota_title"
        const val EXTRA_OTA_TEXT = "ota_text"
        const val EXTRA_OTA_PROGRESS = "ota_progress"
        const val EXTRA_OTA_DONE = "ota_done"
    }

    private lateinit var notificationManager: NotificationManager
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var runJob: Job? = null

    private var otaNotification: Notification? = null
    private var runNotification: Notification? = null

    override fun onCreate() {
        super.onCreate()
        notificationManager = getSystemService(NotificationManager::class.java)
        createChannels()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_OTA -> handleOta(intent)
            ACTION_RUN_START -> startRun()
            ACTION_RUN_STOP -> RunSession.requestStop()
        }
        if (otaNotification == null && runNotification == null) stopSelf()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ── OTA ──────────────────────────────────────────────────────────────

    private fun handleOta(intent: Intent) {
        val title = intent.getStringExtra(EXTRA_OTA_TITLE) ?: "Updating…"
        val text = intent.getStringExtra(EXTRA_OTA_TEXT) ?: ""
        val progress = intent.getIntExtra(EXTRA_OTA_PROGRESS, -1)
        val done = intent.getBooleanExtra(EXTRA_OTA_DONE, false)

        if (done) {
            otaNotification = null
            val run = runNotification
            if (run != null) {
                // Hand the foreground slot back to the run before dropping OTA.
                goForeground(NOTIFICATION_RUN_ID, run)
            } else {
                stopForeground(STOP_FOREGROUND_REMOVE)
            }
            notificationManager.cancel(NOTIFICATION_OTA_ID)
        } else {
            val n = buildOtaNotification(title, text, progress)
            otaNotification = n
            goForeground(NOTIFICATION_OTA_ID, n)
        }
    }

    // ── Run ──────────────────────────────────────────────────────────────

    private fun startRun() {
        // startForegroundService() obliges a startForeground() now, even if the
        // run already ended between the request and this call.
        val n = buildRunNotification(RunSession.notice.value)
        runNotification = n
        goForeground(NOTIFICATION_RUN_ID, n)
        if (runJob?.isActive == true) return
        runJob = scope.launch {
            RunSession.notice.collect { notice ->
                if (notice == null) {
                    endRun()
                } else {
                    val built = buildRunNotification(notice)
                    runNotification = built
                    postUpdate(NOTIFICATION_RUN_ID, built)
                }
            }
        }
    }

    private fun endRun() {
        runNotification = null
        runJob?.cancel()
        runJob = null
        val ota = otaNotification
        if (ota != null) {
            goForeground(NOTIFICATION_OTA_ID, ota)
            notificationManager.cancel(NOTIFICATION_RUN_ID)
        } else {
            stopForeground(STOP_FOREGROUND_REMOVE)
            notificationManager.cancel(NOTIFICATION_RUN_ID)
            stopSelf()
        }
    }

    // ── Plumbing ─────────────────────────────────────────────────────────

    private fun goForeground(id: Int, notification: Notification) {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        } else 0
        ServiceCompat.startForeground(this, id, notification, type)
    }

    /** Refresh a notification already shown by [goForeground]. Skipped when
     *  notifications are denied — the service keeps running, unseen. */
    private fun postUpdate(id: Int, notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) return
        notificationManager.notify(id, notification)
    }

    private fun createChannels() {
        val otaChannel = NotificationChannel(
            CHANNEL_OTA,
            "Firmware & App Updates",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Shows progress during firmware or app updates"
            setShowBadge(false)
        }
        val runChannel = NotificationChannel(
            CHANNEL_RUN,
            getString(R.string.notif_channel_run_active),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.notif_channel_run_active_desc)
            setShowBadge(false)
        }
        notificationManager.createNotificationChannel(otaChannel)
        notificationManager.createNotificationChannel(runChannel)
    }

    private fun openAppIntent(): PendingIntent = PendingIntent.getActivity(
        this, 0,
        Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        },
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun buildRunNotification(notice: RunNotice?): Notification {
        val stopIntent = PendingIntent.getService(
            this, 1,
            Intent(this, PulsarNotificationService::class.java).setAction(ACTION_RUN_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val builder = NotificationCompat.Builder(this, CHANNEL_RUN)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(notice?.title ?: getString(R.string.notif_run_title_generic))
            .setContentText(notice?.text ?: getString(R.string.run_phase_starting))
            .setContentIntent(openAppIntent())
            .setOngoing(true)
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(R.drawable.ic_stop, getString(R.string.btn_stop), stopIntent)
        if (notice != null && notice.progressMax > 0) {
            builder.setProgress(notice.progressMax, notice.progress, false)
        }
        return builder.build()
    }

    private fun buildOtaNotification(title: String, text: String, progress: Int): Notification {
        val builder = NotificationCompat.Builder(this, CHANNEL_OTA)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(openAppIntent())
            .setOngoing(true)
            .setSilent(true)

        when {
            progress in 0..100 -> builder.setProgress(100, progress, false)
            else -> builder.setProgress(0, 0, true)  // indeterminate
        }

        return builder.build()
    }
}
