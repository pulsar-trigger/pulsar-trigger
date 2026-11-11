/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Copyright (C) 2026 Pulsar Trigger contributors
 */

package com.ehrocha.pulsar.service

import com.ehrocha.pulsar.model.FlowStep
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RunNoticeFormatTest {

    @Test fun shotsWithPlan() = assertEquals("12 / 300", RunNoticeFormat.shots(12, 300))
    @Test fun shotsContinuous() = assertEquals("12 / ∞", RunNoticeFormat.shots(12, 0))
    @Test fun shotsUnknownPlan() = assertEquals("7", RunNoticeFormat.shots(7, null))

    @Test fun remainingUnknown() {
        assertNull(RunNoticeFormat.remaining(0))
        assertNull(RunNoticeFormat.remaining(-5))
    }

    @Test fun remainingSecondsRoundUp() {
        assertEquals("1s", RunNoticeFormat.remaining(1))
        assertEquals("12s", RunNoticeFormat.remaining(12_000))
    }

    @Test fun remainingMinutes() = assertEquals("4m 05s", RunNoticeFormat.remaining(245_000))
    @Test fun remainingHours() = assertEquals("1h 02m", RunNoticeFormat.remaining(3_720_000))

    @Test fun plannedShotsSumsAndSkipsPauses() {
        val steps = listOf(
            FlowStep.Intervalometer(shotCount = 10),
            FlowStep.Pause(),
            FlowStep.Ramp(steps = 5),
            FlowStep.DarkFrame(shotCount = 3),
        )
        assertEquals(18, RunNoticeFormat.plannedShots(steps))
    }

    @Test fun plannedShotsContinuousStepMakesTheRunOpenEnded() {
        val steps = listOf(FlowStep.Intervalometer(shotCount = 10), FlowStep.Astro(shotCount = 0))
        assertEquals(0, RunNoticeFormat.plannedShots(steps))
    }

    @Test fun cameraTransportRunsArePhoneTimed() {
        assertTrue(RunNoticeFormat.isPhoneTimed(true, firmwareTimesShots = false,
            steps = listOf(FlowStep.Intervalometer())))
    }

    @Test fun singleStepEsp32RunIsFirmwareTimed() {
        assertFalse(RunNoticeFormat.isPhoneTimed(true, firmwareTimesShots = true,
            steps = listOf(FlowStep.Astro())))
    }

    @Test fun esp32RampAndMultiStepAreSequencedByThePhone() {
        assertTrue(RunNoticeFormat.isPhoneTimed(true, firmwareTimesShots = true,
            steps = listOf(FlowStep.Ramp())))
        assertTrue(RunNoticeFormat.isPhoneTimed(true, firmwareTimesShots = true,
            steps = listOf(FlowStep.Intervalometer(), FlowStep.DarkFrame())))
    }

    @Test fun nothingIsPhoneTimedWithoutARunningFlow() {
        assertFalse(RunNoticeFormat.isPhoneTimed(false, firmwareTimesShots = false,
            steps = listOf(FlowStep.Ramp())))
    }
}
