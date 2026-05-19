/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Copyright (C) 2026 Pulsar Trigger contributors
 */

package com.ehrocha.pulsar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Astro exposure calculations. These are the numbers the Astro wizard
 *  feeds straight into `exposureMs` for bulb runs — silent regressions
 *  show up as stars trailing or stacked frames being underexposed. */
class AstroExposureTest {

    @Test
    fun `500 rule on a 50mm full frame`() {
        // 500 / (50 × 1.0) = 10s exactly.
        val ms = AppConfig.astroExposureMs(focalLength = 50, cropFactor = 1.0f, ruleDivisor = 500)
        assertEquals(10_000L, ms)
    }

    @Test
    fun `400 rule on a 50mm full frame`() {
        // 400 / (50 × 1.0) = 8s.
        val ms = AppConfig.astroExposureMs(focalLength = 50, cropFactor = 1.0f, ruleDivisor = 400)
        assertEquals(8_000L, ms)
    }

    @Test
    fun `500 rule on RF 16mm full frame matches Eduardo's setup`() {
        // 500 / (16 × 1.0) = 31.25s.
        val ms = AppConfig.astroExposureMs(focalLength = 16, cropFactor = 1.0f, ruleDivisor = 500)
        assertEquals(31_250L, ms)
    }

    @Test
    fun `crop factor shortens exposure`() {
        // Same 50mm on APS-C Canon (1.6×) → 500 / 80 = 6.25s.
        val ms = AppConfig.astroExposureMs(focalLength = 50, cropFactor = 1.6f, ruleDivisor = 500)
        assertEquals(6_250L, ms)
    }

    @Test
    fun `NPF rule on 16mm full frame returns a sane value`() {
        // NPF is pixel-pitch dependent so we don't pin to an exact ms, but
        // it must be in a reasonable bucket and shorter than the 500 rule
        // (NPF is stricter).
        val npf = AppConfig.astroExposureMs(
            focalLength = 16, cropFactor = 1.0f, ruleDivisor = AppConfig.NPF_RULE_DIVISOR,
        )
        val fiveHundred = AppConfig.astroExposureMs(
            focalLength = 16, cropFactor = 1.0f, ruleDivisor = 500,
        )
        assertTrue("NPF must be in the second-range for a 16mm full-frame setup, got ${npf}ms",
            npf in 5_000L..30_000L)
        assertTrue("NPF should be stricter (shorter) than the 500 rule",
            npf < fiveHundred)
    }

    @Test
    fun `minimum exposure floor is honoured`() {
        // A long focal × high crop pushes the math below the floor. The
        // clamp prevents shipping near-zero-ms commands that would just
        // jitter the shutter.
        val ms = AppConfig.astroExposureMs(focalLength = 600, cropFactor = 2.0f, ruleDivisor = 500)
        assertTrue("exposure must be clamped to MIN_ASTRO_EXPOSURE_MS",
            ms >= AppConfig.MIN_ASTRO_EXPOSURE_MS)
    }
}
