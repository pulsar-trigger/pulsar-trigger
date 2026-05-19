/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Copyright (C) 2026 Pulsar Trigger contributors
 */

package com.ehrocha.pulsar.model

import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** `useAutofocus` was added to every bulb-capable FlowStep variant in v0.229
 *  to gate the CCAPI per-shot `af` flag. Defaults must stay false on every
 *  variant (so legacy presets keep their bulb-astro behaviour); the field
 *  must round-trip through JSON; missing keys on legacy saves must read as
 *  false. */
class FlowStepAutofocusTest {

    @Test
    fun `Intervalometer default useAutofocus is false`() {
        assertFalse(FlowStep.Intervalometer().useAutofocus)
    }

    @Test
    fun `Astro default useAutofocus is false`() {
        assertFalse(FlowStep.Astro().useAutofocus)
    }

    @Test
    fun `DarkFrame default useAutofocus is false`() {
        assertFalse(FlowStep.DarkFrame().useAutofocus)
    }

    @Test
    fun `Ramp default useAutofocus is false`() {
        assertFalse(FlowStep.Ramp().useAutofocus)
    }

    @Test
    fun `Intervalometer useAutofocus true round-trips`() {
        val step = FlowStep.Intervalometer(useAutofocus = true)
        val restored = FlowStep.fromJson(step.toJson()) as FlowStep.Intervalometer
        assertTrue(restored.useAutofocus)
    }

    @Test
    fun `Astro useAutofocus true round-trips`() {
        val step = FlowStep.Astro(useAutofocus = true)
        val restored = FlowStep.fromJson(step.toJson()) as FlowStep.Astro
        assertTrue(restored.useAutofocus)
    }

    @Test
    fun `Ramp useAutofocus true round-trips`() {
        val step = FlowStep.Ramp(useAutofocus = true)
        val restored = FlowStep.fromJson(step.toJson()) as FlowStep.Ramp
        assertTrue(restored.useAutofocus)
    }

    @Test
    fun `legacy Intervalometer JSON without useAutofocus reads as false`() {
        // Pre-v0.229 save: no useAutofocus key at all.
        val json = JSONObject().apply {
            put("type", "INTERVALOMETER")
            put("intervalMs", 5000L)
            put("exposureMs", 2000L)
            put("shotCount", 30)
            put("delayMs", 0L)
        }
        val step = FlowStep.fromJson(json) as FlowStep.Intervalometer
        assertFalse("legacy save must default useAutofocus=false", step.useAutofocus)
    }

    @Test
    fun `legacy Astro JSON without useAutofocus reads as false`() {
        val json = JSONObject().apply {
            put("type", "ASTRO")
            put("focalLength", 16)
            put("cropFactor", 1.0)
            put("ruleDivisor", 500)
            put("gapMs", 4000L)
            put("shotCount", 30)
            put("delayMs", 0L)
        }
        val step = FlowStep.fromJson(json) as FlowStep.Astro
        assertFalse(step.useAutofocus)
    }
}
