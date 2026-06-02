/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Copyright (C) 2026 Pulsar Trigger contributors
 */

package com.ehrocha.pulsar.wearable

import android.content.Context
import android.util.Log
import com.ehrocha.pulsar.viewmodel.PulsarViewModel
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

/**
 * Bridge from the phone's [PulsarViewModel] state flows to the paired
 * Wear OS companion in [:wear]. Whenever the run state changes, we
 * package the relevant fields into a DataMap and push it on
 * [RunStateWire.PATH_RUN_STATE]. The watch's WearStateListenerService
 * picks it up and re-renders.
 *
 * Set up once in MainActivity (or PulsarApp) — the collection runs
 * inside the supplied [CoroutineScope], which the caller owns.
 */
class WearableSync(
    private val context: Context,
    private val vm: PulsarViewModel,
    private val scope: CoroutineScope,
) {
    private val dataClient = Wearable.getDataClient(context)

    fun start() {
        scope.launch {
            combine(
                vm.connected,
                vm.flowRunning,
                vm.flowSteps,         // for the mode label
                vm.status,
                vm.flowCurrentStep,
            ) { connected, running, steps, status, currentStep ->
                Snapshot(
                    connected = connected,
                    running = running,
                    modeLabel = pickModeLabel(steps, currentStep),
                    shotsTaken = status?.shotsTaken ?: 0,
                    plannedShots = if (running) steps.sumOf { plannedShotsFor(it) } else 0,
                    timeRemainingMs = status?.timeRemainingMs ?: 0L,
                    deviceState = status?.state?.name ?: "",
                )
            }
                .distinctUntilChanged()
                .collect { snap -> push(snap) }
        }
    }

    private suspend fun push(snap: Snapshot) {
        val request = PutDataMapRequest.create(RunStateWire.PATH_RUN_STATE).apply {
            dataMap.putBoolean(RunStateWire.KEY_CONNECTED, snap.connected)
            dataMap.putBoolean(RunStateWire.KEY_RUNNING, snap.running)
            dataMap.putString(RunStateWire.KEY_MODE_LABEL, snap.modeLabel)
            dataMap.putInt(RunStateWire.KEY_SHOTS_TAKEN, snap.shotsTaken)
            dataMap.putInt(RunStateWire.KEY_PLANNED_SHOTS, snap.plannedShots)
            dataMap.putLong(RunStateWire.KEY_TIME_REMAINING_MS, snap.timeRemainingMs)
            dataMap.putString(RunStateWire.KEY_DEVICE_STATE, snap.deviceState)
            dataMap.putLong(RunStateWire.KEY_TS, System.currentTimeMillis())
        }.asPutDataRequest().setUrgent()
        runCatching { dataClient.putDataItem(request).await() }
            .onFailure { Log.w(TAG, "putDataItem failed: ${it.message}") }
    }

    private data class Snapshot(
        val connected: Boolean,
        val running: Boolean,
        val modeLabel: String,
        val shotsTaken: Int,
        val plannedShots: Int,
        val timeRemainingMs: Long,
        val deviceState: String,
    )

    private fun pickModeLabel(
        steps: List<com.ehrocha.pulsar.model.FlowStep>,
        currentIdx: Int,
    ): String {
        val step = steps.getOrNull(currentIdx) ?: steps.firstOrNull() ?: return ""
        return when (step) {
            is com.ehrocha.pulsar.model.FlowStep.Intervalometer ->
                if (step.exposureMs == com.ehrocha.pulsar.AppConfig.TIMELAPSE_PULSE_MS) "TIMELAPSE"
                else "INTERVALOMETER"
            is com.ehrocha.pulsar.model.FlowStep.Astro -> "ASTRO"
            is com.ehrocha.pulsar.model.FlowStep.DarkFrame -> "DARK_FRAME"
            is com.ehrocha.pulsar.model.FlowStep.Ramp -> "RAMP"
            is com.ehrocha.pulsar.model.FlowStep.Pause -> "PAUSE"
        }
    }

    private fun plannedShotsFor(step: com.ehrocha.pulsar.model.FlowStep): Int = when (step) {
        is com.ehrocha.pulsar.model.FlowStep.Intervalometer -> step.shotCount
        is com.ehrocha.pulsar.model.FlowStep.Astro -> step.shotCount
        is com.ehrocha.pulsar.model.FlowStep.DarkFrame -> step.shotCount
        is com.ehrocha.pulsar.model.FlowStep.Ramp -> step.steps
        is com.ehrocha.pulsar.model.FlowStep.Pause -> 0
    }

    companion object { private const val TAG = "WearableSync" }
}
