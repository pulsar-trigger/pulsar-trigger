/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Copyright (C) 2026 Pulsar Trigger contributors
 */

package com.ehrocha.pulsar.service

import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import com.ehrocha.pulsar.R
import com.ehrocha.pulsar.model.displayName

/**
 * Keeps a run alive while the screen is off (audit A-H1). Owned by the view
 * model, fed one [RunSnapshot] per (throttled) run-state change:
 *
 * - any active run (every transport, including a firmware-owned ESP32 run)
 *   gets the ongoing foreground notification with progress and Stop;
 * - a PHONE-timed run ([RunSnapshot.phoneTimed]) also holds a
 *   `PARTIAL_WAKE_LOCK`, so the `delay()` loops in the view model keep their
 *   timing when the CPU would otherwise sleep.
 *
 * `update(null)` (run over) and [release] (view model cleared) drop both.
 */
class RunKeepAlive(private val app: Application) {

    companion object {
        private const val TAG = "RunKeepAlive"
        /** Safety cap: a run whose state stops changing for this long (a hung
         *  loop) lets the CPU sleep again. Refreshed while the run moves. */
        private const val WAKE_TIMEOUT_MS = 4 * 60 * 60 * 1000L
        private const val WAKE_REFRESH_MS = 10 * 60 * 1000L
    }

    private var serviceStarted = false
    private var wakeLock: PowerManager.WakeLock? = null
    private var wakeAcquiredAt = 0L

    fun update(snapshot: RunSnapshot?) {
        if (snapshot == null) {
            release()
            return
        }
        setWakeLock(snapshot.phoneTimed)
        RunSession.publish(noticeFor(snapshot))
        if (!serviceStarted) {
            serviceStarted = true
            try {
                ContextCompat.startForegroundService(
                    app,
                    Intent(app, PulsarNotificationService::class.java)
                        .setAction(PulsarNotificationService.ACTION_RUN_START),
                )
            } catch (e: IllegalStateException) {
                // ForegroundServiceStartNotAllowedException (API 31+) when the
                // app is already in the background — e.g. a firmware run picked
                // up on reconnect. The wake lock still holds for phone-timed
                // runs; only the notification is missing.
                Log.w(TAG, "run service not started: ${e.message}")
            }
        }
    }

    fun release() {
        RunSession.publish(null)
        serviceStarted = false
        setWakeLock(false)
    }

    private fun setWakeLock(on: Boolean) {
        if (!on) {
            wakeLock?.let { if (it.isHeld) it.release() }
            return
        }
        val wl = wakeLock ?: run {
            val pm = app.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return
            pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "pulsar:run")
                .apply { setReferenceCounted(false) }
                .also { wakeLock = it }
        }
        val now = SystemClock.elapsedRealtime()
        if (!wl.isHeld || now - wakeAcquiredAt >= WAKE_REFRESH_MS) {
            wl.acquire(WAKE_TIMEOUT_MS)
            wakeAcquiredAt = now
        }
    }

    private fun noticeFor(s: RunSnapshot): RunNotice {
        val mode = s.step?.type?.displayName(app) ?: app.getString(R.string.notif_run_title_generic)
        val title = if (s.stepCount > 1 && s.stepIndex >= 0) {
            app.getString(R.string.notif_run_title_step, mode, s.stepIndex + 1, s.stepCount)
        } else mode
        val shots = RunNoticeFormat.shots(s.shotsTaken, s.plannedShots)
        val remaining = RunNoticeFormat.remaining(s.timeRemainingMs)
        val text = when {
            s.paused -> app.getString(R.string.notif_run_paused)
            s.preparing -> app.getString(R.string.run_phase_preparing)
            remaining != null -> app.getString(R.string.notif_run_shots_remaining, shots, remaining)
            else -> app.getString(R.string.notif_run_shots, shots)
        }
        val planned = s.plannedShots ?: 0
        return RunNotice(
            title = title,
            text = text,
            progress = s.shotsTaken.coerceIn(0, planned.coerceAtLeast(0)),
            progressMax = planned.coerceAtLeast(0),
        )
    }
}
