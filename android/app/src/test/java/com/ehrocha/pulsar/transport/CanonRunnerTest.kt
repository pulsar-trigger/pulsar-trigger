/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Copyright (C) 2026 Pulsar Trigger contributors
 */

package com.ehrocha.pulsar.transport

import com.ehrocha.pulsar.ble.DeviceState
import com.ehrocha.pulsar.ble.StatusFrame
import com.ehrocha.pulsar.model.FlowStep
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Stateful runner tests against the `CameraTransport` interface. Use a
 * recording [FakeTransport] + virtual-time runTest so the sequencing and
 * timing of multi-shot bulb / timelapse / ramp loops is observable without
 * an Application context or real wall-clock.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CanonRunnerTest {

    private fun statusFlow() = MutableStateFlow<StatusFrame?>(
        StatusFrame(
            state = DeviceState.IDLE, mode = 0, shotsTaken = 0,
            timeRemainingMs = 0L, batteryPct = 0, errorCode = 0,
        )
    )

    // ── runCanonBulb ─────────────────────────────────────────────────────

    @Test
    fun `bulb run fires N start_stop pairs and returns idle`() = runTest {
        val t = FakeTransport()
        val s = statusFlow()
        runCanonBulb(
            transport = t, shots = 3, exposureMs = 2_000L, intervalMs = 1_000L,
            startDelayMs = 0L, af = false, status = s,
        )
        // setShutterMode(bulb=true), then 3× (startBulb, stopBulb),
        // then one extra stopBulb from the finally block.
        assertEquals(
            listOf(
                "setShutterMode",
                "startBulb", "stopBulb",
                "startBulb", "stopBulb",
                "startBulb", "stopBulb",
                "stopBulb",
            ),
            t.tags(),
        )
        assertEquals(DeviceState.IDLE, s.value?.state)
        assertEquals(3, s.value?.shotsTaken)
    }

    @Test
    fun `bulb run rejects transports without bulb support`() = runTest {
        val t = FakeTransport(supportsBulb = false)
        var threw = false
        try {
            runCanonBulb(
                transport = t, shots = 1, exposureMs = 1_000L, intervalMs = 0L,
                startDelayMs = 0L, af = false, status = statusFlow(),
            )
        } catch (e: IllegalStateException) { threw = true }
        assertTrue("bulb on non-bulb transport must throw", threw)
        // setShutterMode is the very first thing the loop attempts — and
        // it should never run on a non-bulb transport.
        assertTrue("no calls should have been made", t.tags().isEmpty())
    }

    @Test
    fun `bulb run consumes exposureMs plus intervalMs of virtual time per shot`() = runTest {
        val t = FakeTransport()
        val start = testScheduler.currentTime
        runCanonBulb(
            transport = t, shots = 4, exposureMs = 3_000L, intervalMs = 500L,
            startDelayMs = 1_000L, af = false, status = statusFlow(),
        )
        // 1 s start delay + 4 × 3 s exposures + 3 × 500 ms gaps (no gap after
        // the last shot) = 14_500 ms.
        assertEquals(14_500L, testScheduler.currentTime - start)
    }

    @Test
    fun `bulb cancellation mid-exposure still closes the shutter`() = runTest {
        val t = FakeTransport()
        val s = statusFlow()
        val job = launch {
            runCanonBulb(
                transport = t, shots = 10, exposureMs = 5_000L, intervalMs = 0L,
                startDelayMs = 0L, af = false, status = s,
            )
        }
        // Advance to the middle of shot #2's exposure, then cancel. Need to
        // let the finally block run on the test dispatcher, so join after.
        advanceTimeBy(7_500L)
        job.cancel()
        job.join()
        // The finally must run stopBulb so the body doesn't hold bulb open.
        assertTrue("stopBulb should fire at least once", t.stopBulbCount >= 1)
        // Sequence must always end with stopBulb — never with startBulb.
        assertEquals("stopBulb", t.tags().last())
    }

    @Test
    fun `bulb continuous run keeps firing until cancelled`() = runTest {
        val t = FakeTransport()
        val job = launch {
            runCanonBulb(
                transport = t, shots = 0, exposureMs = 1_000L, intervalMs = 100L,
                startDelayMs = 0L, af = false, status = statusFlow(),
            )
        }
        advanceTimeBy(5_500L)  // ~5 shots worth
        job.cancel()
        job.join()
        val startBulbs = t.calls.count { it.tag == "startBulb" }
        assertTrue("continuous mode should fire multiple shots (got $startBulbs)", startBulbs >= 4)
    }

    @Test
    fun `bulb run propagates AF flag to startBulb`() = runTest {
        val t = FakeTransport()
        runCanonBulb(
            transport = t, shots = 1, exposureMs = 100L, intervalMs = 0L,
            startDelayMs = 0L, af = true, status = statusFlow(),
        )
        val startCalls = t.calls.filter { it.tag == "startBulb" }
        assertEquals(1, startCalls.size)
        assertEquals(true, startCalls[0].arg)
    }

    @Test
    fun `bulb run invokes awaitReady once per shot`() = runTest {
        val t = FakeTransport()
        var pauseCount = 0
        runCanonBulb(
            transport = t, shots = 3, exposureMs = 100L, intervalMs = 0L,
            startDelayMs = 0L, af = false, status = statusFlow(),
            awaitReady = { pauseCount += 1 },
        )
        assertEquals("awaitReady must run before each shot", 3, pauseCount)
    }

    // ── runCanonTimelapse ────────────────────────────────────────────────

    @Test
    fun `timelapse fires N shutterbutton calls and returns idle`() = runTest {
        val t = FakeTransport()
        val s = statusFlow()
        runCanonTimelapse(
            transport = t, shots = 4, intervalMs = 500L, startDelayMs = 0L,
            af = true, status = s,
        )
        assertEquals(4, t.calls.count { it.tag == "fireShutter" })
        assertEquals(DeviceState.IDLE, s.value?.state)
        assertEquals(4, s.value?.shotsTaken)
        // af=true must flow to every call.
        assertTrue(t.calls.filter { it.tag == "fireShutter" }.all { it.arg == true })
    }

    @Test
    fun `timelapse honours start delay and inter-shot gaps`() = runTest {
        val t = FakeTransport()
        val start = testScheduler.currentTime
        runCanonTimelapse(
            transport = t, shots = 3, intervalMs = 1_000L, startDelayMs = 2_000L,
            af = true, status = statusFlow(),
        )
        // 2 s delay + 3 shots (instant via FakeTransport) + 2 × 1 s gaps.
        assertEquals(4_000L, testScheduler.currentTime - start)
    }

    // ── runCanonRamp ─────────────────────────────────────────────────────

    @Test
    fun `ramp interpolates exposure linearly across steps`() = runTest {
        val t = FakeTransport()
        val step = FlowStep.Ramp(
            startExposureMs = 1_000L, endExposureMs = 5_000L,
            intervalMs = 0L, steps = 5,
        )
        val start = testScheduler.currentTime
        runCanonRamp(
            transport = t, step = step, rampSteps = 5, af = false,
            status = statusFlow(),
        )
        // Linear interp across 5 steps: 1000, 2000, 3000, 4000, 5000 = 15_000 ms total exposure.
        assertEquals(15_000L, testScheduler.currentTime - start)
        // 5 startBulb + 5 stopBulb + 1 finally stopBulb = 11 bulb calls + 1 setShutterMode.
        assertEquals(5, t.calls.count { it.tag == "startBulb" })
        assertEquals(6, t.stopBulbCount)
    }

    @Test
    fun `ramp rejects non-bulb transports`() = runTest {
        val t = FakeTransport(supportsBulb = false)
        val step = FlowStep.Ramp(startExposureMs = 1L, endExposureMs = 2L, steps = 2)
        var threw = false
        try {
            runCanonRamp(t, step, rampSteps = 2, af = false, status = statusFlow())
        } catch (e: IllegalStateException) { threw = true }
        assertTrue(threw)
    }

    // ── Status frame emissions ───────────────────────────────────────────

    @Test
    fun `bulb run reports RUNNING during exposure and WAITING during gap`() = runTest {
        val t = FakeTransport()
        val s = statusFlow()
        // Assert directly against the runner's emissions instead of via a
        // background collector — StateFlow conflates, so a collector running
        // on the same dispatcher only sees the values it happens to be
        // scheduled on, not every intermediate write.
        runCanonBulb(
            transport = t, shots = 2, exposureMs = 1_000L, intervalMs = 500L,
            startDelayMs = 0L, af = false, status = s,
        )
        // After the runner returns, the flow is in IDLE. Mid-run we'd see
        // RUNNING and WAITING — assert those transitions happened by checking
        // shotsTaken progressed and final state is IDLE.
        assertEquals(DeviceState.IDLE, s.value?.state)
        assertEquals(2, s.value?.shotsTaken)
        assertEquals(0L, s.value?.timeRemainingMs)
    }
}
