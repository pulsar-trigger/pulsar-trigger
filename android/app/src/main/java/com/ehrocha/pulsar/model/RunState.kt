/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Copyright (C) 2026 Pulsar Trigger contributors
 */

package com.ehrocha.pulsar.model

import com.ehrocha.pulsar.ble.DeviceState
import com.ehrocha.pulsar.ble.StatusFrame

/**
 * Canonical run state — the single value UI consumers should read to decide
 * what to render. Combines the firmware/simulator [StatusFrame] with the
 * flow runner's bookkeeping into one shape so views don't have to combine
 * four parallel flags by hand. Derived from the viewmodel's other flows.
 */
sealed class RunState {
    /** Nothing happening. */
    data object Idle : RunState()

    /** Canon BLE only: the run has started but is still waking the body +
     *  settling before the first frame (the wake-nudge window). Non-Idle so
     *  the mode screens switch to the run view and show a "Preparing…" state
     *  instead of leaving the config screen up looking stalled. */
    data object Preparing : RunState()

    /** Firmware reported an error. */
    data class Error(val code: Int) : RunState()

    /** Flow runner halted at a PAUSE step, waiting for user Continue. */
    data class Paused(val stepLabel: String) : RunState()

    /** Shutter is open — exposing a frame. */
    data class Running(
        val currentStep: Int,
        val totalSteps: Int,
        val shotsTaken: Int,
        val timeRemainingMs: Long,
    ) : RunState()

    /** Inter-shot gap or pre-flow start delay. */
    data class Waiting(
        val currentStep: Int,
        val totalSteps: Int,
        val shotsTaken: Int,
        val timeRemainingMs: Long,
    ) : RunState()

    companion object {
        /** Pure projection — given the underlying state holders, compute the
         *  current RunState. Easy to test; called from the viewmodel's
         *  combine() block and (eventually) from anywhere else that has the
         *  inputs in hand. */
        fun from(
            status: StatusFrame?,
            flowRunning: Boolean,
            flowPaused: Boolean,
            currentStep: Int,
            steps: List<FlowStep>,
        ): RunState {
            if (flowPaused) {
                val label = (steps.getOrNull(currentStep) as? FlowStep.Pause)?.label
                    ?: "Paused"
                return Paused(label)
            }
            val totalSteps = steps.size.coerceAtLeast(1)
            val stepIdx = if (flowRunning) currentStep.coerceAtLeast(0) else -1
            return when (status?.state) {
                DeviceState.ERROR -> Error(status.errorCode)
                DeviceState.RUNNING -> Running(
                    currentStep = stepIdx,
                    totalSteps = totalSteps,
                    shotsTaken = status.shotsTaken,
                    timeRemainingMs = status.timeRemainingMs,
                )
                DeviceState.WAITING -> Waiting(
                    currentStep = stepIdx,
                    totalSteps = totalSteps,
                    shotsTaken = status.shotsTaken,
                    timeRemainingMs = status.timeRemainingMs,
                )
                else -> Idle
            }
        }
    }
}
