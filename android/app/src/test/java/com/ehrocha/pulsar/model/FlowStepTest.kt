package com.ehrocha.pulsar.model

import com.ehrocha.pulsar.AppConfig
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class FlowStepTest {

    @Test
    fun `default wakeOnPause is true`() {
        val step = FlowStep(type = FlowStepType.PAUSE)
        assertTrue(step.wakeOnPause)
    }

    @Test
    fun `wakeOnPause false round-trips through JSON`() {
        val step = FlowStep(type = FlowStepType.PAUSE, wakeOnPause = false, pauseLabel = "Reframe")
        val json = step.toJson()
        val restored = FlowStep.fromJson(json)
        assertFalse(restored.wakeOnPause)
        assertEquals("Reframe", restored.pauseLabel)
    }

    @Test
    fun `wakeOnPause true round-trips through JSON`() {
        val step = FlowStep(type = FlowStepType.PAUSE, wakeOnPause = true)
        val json = step.toJson()
        val restored = FlowStep.fromJson(json)
        assertTrue(restored.wakeOnPause)
    }

    @Test
    fun `missing wakeOnPause in JSON defaults to true`() {
        // Simulates loading a flow saved before this feature existed
        val json = JSONObject().apply {
            put("type", "PAUSE")
            put("pauseLabel", "Old step")
        }
        val step = FlowStep.fromJson(json)
        assertTrue("Legacy JSON without wakeOnPause should default to true", step.wakeOnPause)
    }

    @Test
    fun `serializeList preserves wakeOnPause`() {
        val steps = listOf(
            FlowStep(type = FlowStepType.INTERVALOMETER, shotCount = 5),
            FlowStep(type = FlowStepType.PAUSE, wakeOnPause = false, pauseLabel = "Check focus"),
            FlowStep(type = FlowStepType.ASTRO, shotCount = 10),
        )
        val serialized = FlowStep.serializeList(steps)
        val restored = FlowStep.deserializeList(serialized)
        assertEquals(3, restored.size)
        assertFalse(restored[1].wakeOnPause)
        assertEquals("Check focus", restored[1].pauseLabel)
    }

    @Test
    fun `intervalometer step ignores wakeOnPause`() {
        val step = FlowStep(type = FlowStepType.INTERVALOMETER, wakeOnPause = false)
        val json = step.toJson()
        val restored = FlowStep.fromJson(json)
        // Field preserved but irrelevant for non-pause steps
        assertFalse(restored.wakeOnPause)
        assertEquals(FlowStepType.INTERVALOMETER, restored.type)
    }
}
