package com.ehrocha.pulsar.model

import com.ehrocha.pulsar.ble.DeviceState
import com.ehrocha.pulsar.ble.StatusFrame
import com.ehrocha.pulsar.ble.TriggerMode
import org.junit.Assert.*
import org.junit.Test

class RunStateTest {

    private fun status(state: DeviceState, shotsTaken: Int = 0, remaining: Long = 0L,
                       errorCode: Int = 0): StatusFrame = StatusFrame(
        state = state,
        mode = TriggerMode.INTERVALOMETER.id,
        shotsTaken = shotsTaken,
        timeRemainingMs = remaining,
        batteryPct = 100,
        errorCode = errorCode,
    )

    @Test
    fun `idle when no status and no flow`() {
        val s = RunState.from(null, flowRunning = false, flowPaused = false,
            currentStep = -1, steps = emptyList())
        assertEquals(RunState.Idle, s)
    }

    @Test
    fun `idle when status is IDLE`() {
        val s = RunState.from(status(DeviceState.IDLE), flowRunning = false,
            flowPaused = false, currentStep = -1, steps = emptyList())
        assertEquals(RunState.Idle, s)
    }

    @Test
    fun `running when status is RUNNING`() {
        val s = RunState.from(
            status(DeviceState.RUNNING, shotsTaken = 3, remaining = 5_000L),
            flowRunning = true, flowPaused = false, currentStep = 0,
            steps = listOf(FlowStep.Intervalometer()),
        )
        assertTrue(s is RunState.Running)
        val r = s as RunState.Running
        assertEquals(3, r.shotsTaken)
        assertEquals(5_000L, r.timeRemainingMs)
        assertEquals(0, r.currentStep)
        assertEquals(1, r.totalSteps)
    }

    @Test
    fun `waiting when status is WAITING`() {
        val s = RunState.from(
            status(DeviceState.WAITING, shotsTaken = 1, remaining = 2_000L),
            flowRunning = true, flowPaused = false, currentStep = 0,
            steps = listOf(FlowStep.Intervalometer()),
        )
        assertTrue(s is RunState.Waiting)
        val w = s as RunState.Waiting
        assertEquals(2_000L, w.timeRemainingMs)
    }

    @Test
    fun `paused overrides status`() {
        // Even with a RUNNING status frame on the wire, an in-flight pause step
        // should surface as Paused — the flow runner has paused the orchestration.
        val s = RunState.from(
            status(DeviceState.RUNNING),
            flowRunning = true, flowPaused = true, currentStep = 1,
            steps = listOf(
                FlowStep.Intervalometer(),
                FlowStep.Pause(label = "Reframe"),
            ),
        )
        assertEquals(RunState.Paused("Reframe"), s)
    }

    @Test
    fun `paused uses default label when step isn't a Pause`() {
        val s = RunState.from(
            null, flowRunning = true, flowPaused = true, currentStep = 0,
            steps = listOf(FlowStep.Intervalometer()),
        )
        assertEquals(RunState.Paused("Paused"), s)
    }

    @Test
    fun `error when status is ERROR`() {
        val s = RunState.from(
            status(DeviceState.ERROR, errorCode = 7),
            flowRunning = false, flowPaused = false, currentStep = -1,
            steps = emptyList(),
        )
        assertEquals(RunState.Error(7), s)
    }

    @Test
    fun `currentStep is -1 when flow isn't running`() {
        // Running shots reported via BLE without an active flow (e.g. user
        // hit Start on a single mode directly) still produce Running but
        // currentStep stays at -1.
        val s = RunState.from(
            status(DeviceState.RUNNING),
            flowRunning = false, flowPaused = false, currentStep = -1,
            steps = emptyList(),
        )
        assertTrue(s is RunState.Running)
        assertEquals(-1, (s as RunState.Running).currentStep)
    }
}
