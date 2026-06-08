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
 * Camera-driven run loops for transports that own shot timing on the phone
 * side (CCAPI today; any future phone-driven transport in the same shape).
 * Extracted from the ViewModel so the bulb / timelapse / ramp sequencing can
 * be exercised in unit tests without an Application context —
 * see `CanonRunnerTest`.
 *
 * The functions take their state mutators as parameters:
 *  - [status]: the run-screen `StatusFrame` flow. Writes here are the same the
 *    BLE simulator path makes, so the run UI is transport-agnostic.
 *  - [awaitReady]: pause hook invoked at the top of each shot iteration.
 *    The CCAPI viewmodel passes a closure that waits out the reconnect loop.
 *    Tests pass a no-op. May throw to signal "give up — transport is gone
 *    for good"; callers propagate that as a flow failure.
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
        // fireShutter is a press-delay-release pair on transports that expose
        // a positional shutter toggle (Canon BLE smartphone mode). A cancel
        // landing between press and release would leave the toggle DOWN, so
        // run the tap on a non-cancellable context — the loop still stops on
        // the next iteration via ensureActive().
        withContext(NonCancellable) { transport.fireShutter(af = af) }
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
    // Defensive release before we start pressing. Belt and braces against a
    // prior session leaving the body stuck DOWN — every transport's
    // stopBulb() is idempotent (the wire-level [00,02] / SHUTTER_RELEASE is
    // a no-op on an already-released camera), so this costs one write at
    // session start and removes a whole class of "every-other-shot"
    // surprise. Canon BLE additionally settles per [canonBleSettleIfNeeded].
    runCatching { transport.stopBulb() }
    canonBleSettleIfNeeded(transport)
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
            canonBleSettleIfNeeded(transport)
        }
    }
    status.value = status.value?.copy(state = DeviceState.IDLE, timeRemainingMs = 0L)
}

/** Canon BLE specifically needs ~300 ms to process a shutter-release
 *  write on its internal state machine before another write to the same
 *  characteristic hits — otherwise the body misses the release and stays
 *  in bulb-open state. Verified on EOS RP 2026-06-03 (the camera test
 *  wizard fired 5 bulbs back-to-back across 4 FlowSteps with <13 ms
 *  inter-step gaps; the RP stayed exposed at the end despite every UP
 *  byte landing GATT_SUCCESS). CCAPI / PTP / PTP-IP don't have this
 *  issue — their bulb release is a request-response op the wire layer
 *  ACKs synchronously. */
internal suspend fun canonBleSettleIfNeeded(transport: CameraTransport) {
    if (transport.kind == com.ehrocha.pulsar.transport.TransportKind.CANON_BLE) {
        // v0.361 used 300 ms which was enough between same-step shots but
        // not enough at the END of a step (R6 stayed exposed after the
        // final UP write). 500 ms is a budget compromise: harmless on
        // typical multi-shot bulb runs (~1–2 % overhead on a 30 s bulb),
        // robust enough for end-of-step state isolation.
        delay(500)
    }
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
    // Defensive release — see runCanonBulb for the same pattern.
    runCatching { transport.stopBulb() }
    canonBleSettleIfNeeded(transport)
    try {
        // Start delay before the first shot — used by the Camera Test
        // wizard to enforce a multi-second gap between diagnostic steps
        // on Canon BLE so the camera registers the previous release.
        if (step.delayMs > 0) {
            status.value = status.value?.copy(state = DeviceState.WAITING, shotsTaken = 0)
            delay(step.delayMs)
        }
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
            canonBleSettleIfNeeded(transport)
        }
    }
    status.value = status.value?.copy(state = DeviceState.IDLE, timeRemainingMs = 0L)
}
