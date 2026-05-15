package com.ehrocha.pulsar.model

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class FlowStepTest {

    @Test
    fun `default wakeOnPause is true`() {
        val step = FlowStep.Pause()
        assertTrue(step.wakeOnPause)
    }

    @Test
    fun `wakeOnPause false round-trips through JSON`() {
        val step = FlowStep.Pause(wakeOnPause = false, label = "Reframe")
        val json = step.toJson()
        val restored = FlowStep.fromJson(json) as FlowStep.Pause
        assertFalse(restored.wakeOnPause)
        assertEquals("Reframe", restored.label)
    }

    @Test
    fun `wakeOnPause true round-trips through JSON`() {
        val step = FlowStep.Pause(wakeOnPause = true)
        val json = step.toJson()
        val restored = FlowStep.fromJson(json) as FlowStep.Pause
        assertTrue(restored.wakeOnPause)
    }

    @Test
    fun `missing wakeOnPause in legacy JSON defaults to true`() {
        // Pre-refactor save: pauseLabel was the key for label.
        val json = JSONObject().apply {
            put("type", "PAUSE")
            put("pauseLabel", "Old step")
        }
        val step = FlowStep.fromJson(json) as FlowStep.Pause
        assertTrue("Legacy JSON without wakeOnPause should default to true", step.wakeOnPause)
        assertEquals("Old step", step.label)
    }

    @Test
    fun `serializeList preserves variant data`() {
        val steps = listOf<FlowStep>(
            FlowStep.Intervalometer(shotCount = 5),
            FlowStep.Pause(wakeOnPause = false, label = "Check focus"),
            FlowStep.Astro(shotCount = 10),
        )
        val serialized = FlowStep.serializeList(steps)
        val restored = FlowStep.deserializeList(serialized)
        assertEquals(3, restored.size)
        val pause = restored[1] as FlowStep.Pause
        assertFalse(pause.wakeOnPause)
        assertEquals("Check focus", pause.label)
    }

    @Test
    fun `legacy dark-frame JSON round-trips through DarkFrame variant`() {
        // Pre-refactor save: exposure/count/gap were under darkFrame* keys.
        val json = JSONObject().apply {
            put("type", "DARK_FRAME")
            put("darkFrameCount", 7)
            put("darkFrameExposureMs", 12_000L)
            put("darkFrameGapMs", 3_000L)
        }
        val step = FlowStep.fromJson(json) as FlowStep.DarkFrame
        assertEquals(7, step.shotCount)
        assertEquals(12_000L, step.exposureMs)
        assertEquals(3_000L, step.gapMs)
    }

    @Test
    fun `legacy ramp JSON round-trips through Ramp variant`() {
        // Pre-refactor save: ramp fields were under ramp* keys.
        val json = JSONObject().apply {
            put("type", "RAMP")
            put("rampStartExposureMs", 1_000L)
            put("rampEndExposureMs", 5_000L)
            put("rampSteps", 25)
            put("rampIntervalMs", 2_000L)
        }
        val step = FlowStep.fromJson(json) as FlowStep.Ramp
        assertEquals(1_000L, step.startExposureMs)
        assertEquals(5_000L, step.endExposureMs)
        assertEquals(25, step.steps)
        assertEquals(2_000L, step.intervalMs)
    }

    // ── SavedFlow favorite and tags ─────────────────────────────────────

    @Test
    fun `SavedFlow favorite and tags round-trip through JSON`() {
        val flow = SavedFlow(
            name = "Test Flow",
            steps = listOf(FlowStep.Pause()),
            favorite = true,
            tags = listOf("Astro", "Wedding"),
        )
        val json = flow.toJson()
        val restored = SavedFlow.fromJson(json)
        assertEquals("Test Flow", restored.name)
        assertTrue(restored.favorite)
        assertEquals(listOf("Astro", "Wedding"), restored.tags)
    }

    @Test
    fun `SavedFlow missing favorite and tags defaults gracefully`() {
        val json = JSONObject().apply {
            put("name", "Legacy Flow")
        }
        val flow = SavedFlow.fromJson(json)
        assertFalse("Legacy JSON without favorite should default to false", flow.favorite)
        assertTrue("Legacy JSON without tags should default to empty list", flow.tags.isEmpty())
    }

    @Test
    fun `SavedFlow serializeList preserves favorite and tags`() {
        val flows = listOf(
            SavedFlow(name = "A", steps = emptyList(), favorite = true, tags = listOf("Astro")),
            SavedFlow(name = "B", steps = emptyList(), favorite = false, tags = listOf("Timelapse", "Wedding")),
        )
        val serialized = SavedFlow.serializeList(flows)
        val restored = SavedFlow.deserializeList(serialized)
        assertEquals(2, restored.size)
        assertTrue(restored[0].favorite)
        assertEquals(listOf("Astro"), restored[0].tags)
        assertFalse(restored[1].favorite)
        assertEquals(listOf("Timelapse", "Wedding"), restored[1].tags)
    }
}
