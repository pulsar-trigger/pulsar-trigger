/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Copyright (C) 2026 Pulsar Trigger contributors
 */

package com.ehrocha.pulsar.service

import com.ehrocha.pulsar.model.FlowStep
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * What the ongoing run notification shows. Built by [RunKeepAlive] from the
 * view model's run state (already localised); rendered by
 * [PulsarNotificationService]. [progressMax] is 0 when the run has no planned
 * end (a continuous run) or the plan is unknown — the bar is then hidden.
 */
data class RunNotice(
    val title: String,
    val text: String,
    val progress: Int = 0,
    val progressMax: Int = 0,
)

/**
 * Process-wide hand-off between the view model and the foreground service.
 * The service is started once per run (from the foreground, when the user
 * taps Start) and then observes [notice] — updates never need another
 * `startForegroundService` call, which Android 12+ refuses from the
 * background. `null` = no run: the service drops its run notification and,
 * if nothing else (an OTA) needs it, stops.
 *
 * The notification's Stop action lands in the service, which forwards it as a
 * [stopRequests] event the view model turns into `stop()`.
 */
object RunSession {
    private val _notice = MutableStateFlow<RunNotice?>(null)
    val notice: StateFlow<RunNotice?> = _notice

    private val _stopRequests = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val stopRequests: SharedFlow<Unit> = _stopRequests

    fun publish(notice: RunNotice?) { _notice.value = notice }
    fun requestStop() { _stopRequests.tryEmit(Unit) }
}

/**
 * One frame of run progress, independent of Android — what [RunKeepAlive]
 * turns into a [RunNotice] and a wake-lock decision.
 *
 * @param stepIndex 0-based index of the executing flow step, -1 if unknown.
 * @param plannedShots total planned shots, 0 = continuous (∞), null = unknown
 *        (a firmware-owned run the app didn't start).
 * @param timeRemainingMs time left in the current step, 0 = unknown.
 */
data class RunSnapshot(
    val step: FlowStep?,
    val stepIndex: Int,
    val stepCount: Int,
    val shotsTaken: Int,
    val plannedShots: Int?,
    val timeRemainingMs: Long,
    val paused: Boolean,
    val preparing: Boolean,
    val phoneTimed: Boolean,
)

/** Pure helpers behind the run notification — unit-tested in
 *  `RunNoticeFormatTest`. */
object RunNoticeFormat {
    /** "12 / 300", "12 / ∞" (continuous) or "12" (plan unknown). */
    fun shots(taken: Int, planned: Int?): String = when {
        planned == null -> "$taken"
        planned <= 0 -> "$taken / ∞"
        else -> "$taken / $planned"
    }

    /** Compact remaining time: "1h 02m", "4m 05s", "12s"; null when unknown. */
    fun remaining(ms: Long): String? {
        if (ms <= 0) return null
        val totalSec = (ms + 999) / 1000
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val s = totalSec % 60
        return when {
            h > 0 -> "%dh %02dm".format(java.util.Locale.ROOT, h, m)
            m > 0 -> "%dm %02ds".format(java.util.Locale.ROOT, m, s)
            else -> "%ds".format(java.util.Locale.ROOT, s)
        }
    }

    /** Planned shots of a whole flow: 0 (∞) if any shooting step is
     *  continuous, the sum otherwise. Pause steps plan nothing. */
    fun plannedShots(steps: List<FlowStep>): Int {
        var total = 0
        for (s in steps) {
            val n = when (s) {
                is FlowStep.Intervalometer -> s.shotCount
                is FlowStep.Astro -> s.shotCount
                is FlowStep.DarkFrame -> s.shotCount
                is FlowStep.Ramp -> s.steps
                is FlowStep.Pause -> continue
            }
            if (n <= 0) return 0
            total += n
        }
        return total
    }

    /**
     * True when the PHONE times the shots, so a sleeping CPU would stretch an
     * exposure or stall the run: every camera transport (CCAPI, USB PTP,
     * Canon BLE, PTP/IP) and the simulator, plus the ESP32 paths the app
     * sequences step by step (multi-step flows and Ramp). A single-step ESP32
     * run is timed by the firmware — the phone only listens.
     */
    fun isPhoneTimed(flowRunning: Boolean, firmwareTimesShots: Boolean, steps: List<FlowStep>): Boolean =
        flowRunning && (!firmwareTimesShots || steps.size > 1 || steps.any { it is FlowStep.Ramp })
}
