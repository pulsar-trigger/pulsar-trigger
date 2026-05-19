/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Copyright (C) 2026 Pulsar Trigger contributors
 */

package com.ehrocha.pulsar.transport

import com.ehrocha.pulsar.ble.DeviceState
import com.ehrocha.pulsar.ble.StatusFrame
import com.ehrocha.pulsar.model.FlowStep
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

/**
 * Camera-driven run loops shared by every transport that owns shot timing on
 * the phone side (CCAPI today; direct-Canon-BLE later). Extracted from the
 * ViewModel so the bulb / timelapse / ramp sequencing can be exercised in unit
 * tests without an Application context — see `CanonRunnerTest`.
 *
 * The functions take their state mutators as parameters:
 *  - [status]: the run-screen `StatusFrame` flow. Writes here are the same the
 *    BLE simulator path makes, so the run UI is transport-agnostic.
 *  - [awaitReady]: pause hook invoked at the top of each shot iteration.
 *    The CCAPI viewmodel passes a closure that waits out the reconnect loop;
 *    direct-BLE will eventually pass a bond-loss hook. Tests pass a no-op.
 *    May throw to signal "give up — transport is gone for good"; callers
 *    propagate that as a flow failure.
 */

/** Timelapse-style sequence over CCAPI single-shot shutterbutton: camera owns
 *  exposure (the body's shutter-speed setting), we just fire-and-delay.
 *  Continuous mode (shots ≤ 0) runs until cancelled. */
internal suspend fun runCanonTimelapse(
    transport: CameraTransport,
    shots: Int,
    intervalMs: Long,
    startDelayMs: Long,
    af: Boolean,
    status: MutableStateFlow<StatusFrame?>,
    awaitReady: suspend () -> Unit = {},
) {
    // Approximate per-shot exposure for "time remaining" math. The actual
    // exposure happens inside the camera; ~250 ms covers shutter actuation
    // round-trip + typical 1/30 s pulse.
    val perShotEstimate = 250L
    val continuous = shots <= 0
    val plannedTotal = if (continuous) 0L
                       else startDelayMs + shots * (perShotEstimate + intervalMs) - intervalMs

    if (startDelayMs > 0) {
        status.value = status.value?.copy(
            state = DeviceState.WAITING, shotsTaken = 0,
            timeRemainingMs = plannedTotal,
        )
        delay(startDelayMs)
    }

    var shot = 0
    while (true) {
        coroutineContext.ensureActive()
        awaitReady()
        shot += 1
        val remaining = if (continuous) 0L
                        else ((shots - shot + 1).coerceAtLeast(0)) *
                            (perShotEstimate + intervalMs) - intervalMs
        status.value = status.value?.copy(
            state = DeviceState.RUNNING, shotsTaken = shot - 1,
            timeRemainingMs = remaining.coerceAtLeast(0),
        )
        transport.fireShutter(af = af)
        status.value = status.value?.copy(
            state = DeviceState.WAITING, shotsTaken = shot,
            timeRemainingMs = (remaining - perShotEstimate).coerceAtLeast(0),
        )
        if (!continuous && shot >= shots) break
        if (intervalMs > 0) delay(intervalMs)
    }
    status.value = status.value?.copy(state = DeviceState.IDLE, timeRemainingMs = 0L)
}

/** Bulb-style intervalometer: switch to bulb, full-press, wait `exposureMs`,
 *  release, wait `intervalMs`, repeat. The transport owns the wire-level open
 *  and close; the loop here owns timing. Releases the shutter in a finally
 *  block — guarantees the body doesn't sit with bulb open if the flow is
 *  cancelled mid-exposure. */
internal suspend fun runCanonBulb(
    transport: CameraTransport,
    shots: Int,
    exposureMs: Long,
    intervalMs: Long,
    startDelayMs: Long,
    af: Boolean,
    status: MutableStateFlow<StatusFrame?>,
    awaitReady: suspend () -> Unit = {},
) {
    if (!transport.supportsBulb) {
        throw IllegalStateException("Transport ${transport.kind} lacks bulb support")
    }
    val continuous = shots <= 0
    val plannedTotal = if (continuous) 0L
                       else startDelayMs + shots * (exposureMs + intervalMs) - intervalMs

    transport.setShutterMode(bulb = true)
    try {
        if (startDelayMs > 0) {
            status.value = status.value?.copy(
                state = DeviceState.WAITING, shotsTaken = 0,
                timeRemainingMs = plannedTotal,
            )
            delay(startDelayMs)
        }

        var shot = 0
        while (true) {
            coroutineContext.ensureActive()
            awaitReady()
            shot += 1
            val remaining = if (continuous) 0L
                            else ((shots - shot + 1).coerceAtLeast(0)) *
                                (exposureMs + intervalMs) - intervalMs
            status.value = status.value?.copy(
                state = DeviceState.RUNNING, shotsTaken = shot - 1,
                timeRemainingMs = remaining.coerceAtLeast(0),
            )
            transport.startBulb(af = af)
            delay(exposureMs)
            transport.stopBulb()
            status.value = status.value?.copy(
                state = DeviceState.WAITING, shotsTaken = shot,
                timeRemainingMs = (remaining - exposureMs).coerceAtLeast(0),
            )
            if (!continuous && shot >= shots) break
            if (intervalMs > 0) delay(intervalMs)
        }
    } finally {
        // Cancellation may have arrived mid-exposure with the shutter open.
        // Release on a non-cancellable context so the close-bulb actually
        // makes it through the transport.
        withContext(NonCancellable) {
            runCatching { transport.stopBulb() }
        }
    }
    status.value = status.value?.copy(state = DeviceState.IDLE, timeRemainingMs = 0L)
}

/** Exposure ramp: a sequence of bulb shots whose exposure interpolates
 *  linearly from [FlowStep.Ramp.startExposureMs] to [FlowStep.Ramp.endExposureMs]
 *  across `rampSteps` steps. */
internal suspend fun runCanonRamp(
    transport: CameraTransport,
    step: FlowStep.Ramp,
    rampSteps: Int,
    af: Boolean,
    status: MutableStateFlow<StatusFrame?>,
    awaitReady: suspend () -> Unit = {},
) {
    if (!transport.supportsBulb) {
        throw IllegalStateException("Transport ${transport.kind} lacks bulb support")
    }
    transport.setShutterMode(bulb = true)
    try {
        for (i in 0 until rampSteps) {
            coroutineContext.ensureActive()
            awaitReady()
            val fraction = if (rampSteps <= 1) 0.0 else i.toDouble() / (rampSteps - 1)
            val expMs = (step.startExposureMs +
                fraction * (step.endExposureMs - step.startExposureMs)).toLong()
            status.value = status.value?.copy(
                state = DeviceState.RUNNING, shotsTaken = i,
                // Best-effort remaining estimate: assume average exposure.
                timeRemainingMs = ((rampSteps - i) *
                    ((step.startExposureMs + step.endExposureMs) / 2 + step.intervalMs))
                    - step.intervalMs,
            )
            transport.startBulb(af = af)
            delay(expMs)
            transport.stopBulb()
            status.value = status.value?.copy(
                state = DeviceState.WAITING, shotsTaken = i + 1,
            )
            if (i < rampSteps - 1 && step.intervalMs > 0) delay(step.intervalMs)
        }
    } finally {
        withContext(NonCancellable) {
            runCatching { transport.stopBulb() }
        }
    }
    status.value = status.value?.copy(state = DeviceState.IDLE, timeRemainingMs = 0L)
}
