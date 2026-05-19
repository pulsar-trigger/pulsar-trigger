/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Copyright (C) 2026 Pulsar Trigger contributors
 */

package com.ehrocha.pulsar.model

import com.ehrocha.pulsar.ble.TriggerMode
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** UserMode is the user-saved preset. Verifies the AF toggle round-trip and
 *  that unknown / hostile JSON is rejected by fromJson rather than absorbed
 *  into a partly-defaulted Body. */
class UserModeAutofocusTest {

    @Test
    fun `default useAutofocus is false`() {
        assertFalse(UserMode.Body().useAutofocus)
    }

    @Test
    fun `useAutofocus true round-trips`() {
        val mode = UserMode(
            name = "AF on preset",
            body = UserMode.Body(
                fwMode = TriggerMode.TIMELAPSE,
                useAutofocus = true,
            ),
        )
        val restored = UserMode.fromJson(mode.toJson())
        assertTrue("AF should survive a JSON round-trip", restored!!.body.useAutofocus)
    }

    @Test
    fun `legacy preset without useAutofocus reads as false`() {
        // Pre-v0.229 preset shape: no useAutofocus key.
        val legacy = JSONObject().apply {
            put("schema", UserMode.SCHEMA_ID)
            put("kind", "trigger")
            put("name", "Old preset")
            put("body", JSONObject().apply {
                put("fwMode", "INTERVALOMETER")
                put("params", JSONObject().apply {
                    put("intervalMs", 5000)
                    put("exposureMs", 1000)
                    put("shotCount", 10)
                    put("delayMs", 0)
                })
            })
        }
        val restored = UserMode.fromJson(legacy)
        assertFalse("legacy preset must default useAutofocus to false", restored!!.body.useAutofocus)
    }

    @Test
    fun `bad schema is rejected`() {
        val bad = JSONObject().apply {
            put("schema", "alien-mode/1")
            put("kind", "trigger")
            put("name", "Bogus")
            put("body", JSONObject().apply { put("fwMode", "INTERVALOMETER") })
        }
        assertNull("fromJson should return null on unknown schema", UserMode.fromJson(bad))
    }

    @Test
    fun `non-preset fwMode is rejected`() {
        // CUSTOM_FLOW / PRESS_HOLD etc. aren't representable as a single-step
        // preset — the importer must refuse them rather than create a broken
        // UserMode that the picker can't run.
        val bad = JSONObject().apply {
            put("schema", UserMode.SCHEMA_ID)
            put("kind", "trigger")
            put("name", "Bad mode")
            put("body", JSONObject().apply {
                put("fwMode", "CUSTOM_FLOW")
                put("params", JSONObject())
            })
        }
        assertNull(UserMode.fromJson(bad))
    }

    @Test
    fun `astro preset preserves focal length and crop factor`() {
        val mode = UserMode(
            name = "Astro RF16",
            body = UserMode.Body(
                fwMode = TriggerMode.ASTRO,
                focalLength = 16,
                cropFactor = 1.0f,
                ruleDivisor = 500,
            ),
        )
        val restored = UserMode.fromJson(mode.toJson())!!
        assertEquals(16, restored.body.focalLength)
        assertEquals(1.0f, restored.body.cropFactor, 0.001f)
        assertEquals(500, restored.body.ruleDivisor)
    }
}
