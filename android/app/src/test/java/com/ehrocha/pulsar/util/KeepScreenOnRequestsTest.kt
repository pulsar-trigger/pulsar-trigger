/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Copyright (C) 2026 Pulsar Trigger contributors
 */

package com.ehrocha.pulsar.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KeepScreenOnRequestsTest {

    @Test fun startsReleased() {
        assertFalse(KeepScreenOnRequests().active.value)
    }

    @Test fun oneRequestHoldsTheScreen() {
        val k = KeepScreenOnRequests()
        k.request("run")
        assertTrue(k.active.value)
        k.release("run")
        assertFalse(k.active.value)
    }

    /** The audit A-H1 bug: closing Settings/Alignment/Aircraft Watch cleared
     *  the flag a running flow had set. */
    @Test fun anotherScreenReleasingDoesNotDropTheRunsRequest() {
        val k = KeepScreenOnRequests()
        k.request("run")
        k.request("alignment")
        k.release("alignment")
        assertTrue(k.active.value)
        k.release("aircraft") // never requested
        assertTrue(k.active.value)
        k.release("run")
        assertFalse(k.active.value)
    }

    @Test fun repeatedRequestsUnderOneTagHoldOnce() {
        val k = KeepScreenOnRequests()
        k.request("ota")
        k.request("ota")
        k.release("ota")
        assertFalse(k.active.value)
        assertFalse(k.isHeld("ota"))
    }

    @Test fun releaseWithNothingHeldIsHarmless() {
        val k = KeepScreenOnRequests()
        k.release("run")
        assertFalse(k.active.value)
    }
}
