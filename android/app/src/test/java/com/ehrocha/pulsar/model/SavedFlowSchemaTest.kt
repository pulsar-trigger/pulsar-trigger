/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Copyright (C) 2026 Pulsar Trigger contributors
 */

package com.ehrocha.pulsar.model

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** SavedFlow schema versioning. Files without a schema tag are accepted as
 *  legacy v1; files with an unknown schema are rejected by fromJson; the
 *  list-deserializer skips malformed entries rather than aborting the whole
 *  load (so one bad row doesn't take down the user's whole library). */
class SavedFlowSchemaTest {

    @Test
    fun `roundtrip preserves schema and contents`() {
        val flow = SavedFlow(
            name = "Test flow",
            steps = listOf(FlowStep.Intervalometer(shotCount = 10)),
            favorite = true,
            tags = listOf("astro"),
        )
        val json = flow.toJson()
        assertEquals("pulsar-flow/1", json.optString("schema"))
        val restored = SavedFlow.fromJson(json)
        assertEquals("Test flow", restored.name)
        assertEquals(1, restored.steps.size)
        assertTrue(restored.favorite)
        assertEquals(listOf("astro"), restored.tags)
    }

    @Test
    fun `missing schema field is accepted as legacy`() {
        // Pre-v0.238 SavedFlow saves had no schema field. They must still load.
        val legacy = JSONObject().apply {
            put("name", "Old flow")
            put("steps", JSONArray())
        }
        val restored = SavedFlow.fromJson(legacy)
        assertEquals("Old flow", restored.name)
    }

    @Test
    fun `unknown schema is rejected`() {
        val future = JSONObject().apply {
            put("schema", "some-other-app/3")
            put("name", "Bogus")
        }
        var threw = false
        try { SavedFlow.fromJson(future) }
        catch (e: IllegalArgumentException) { threw = true }
        assertTrue("unknown schema should throw IllegalArgumentException", threw)
    }

    @Test
    fun `future minor of same family is accepted`() {
        // pulsar-flow/2 isn't shipped, but we treat anything in the same
        // family as acceptable so future-additive changes load cleanly on
        // older clients. Field defaults handle missing data.
        val future = JSONObject().apply {
            put("schema", "pulsar-flow/2")
            put("name", "Future flow")
            put("steps", JSONArray())
        }
        val restored = SavedFlow.fromJson(future)
        assertEquals("Future flow", restored.name)
    }

    @Test
    fun `deserializeList skips malformed entries`() {
        // First entry has a bad schema; second is valid. Loading the list
        // should yield the one good entry and silently drop the bad one
        // rather than throwing and losing the whole library.
        val arr = JSONArray()
        arr.put(JSONObject().apply { put("schema", "alien/1"); put("name", "bad") })
        arr.put(JSONObject().apply { put("name", "good") })
        val flows = SavedFlow.deserializeList(arr.toString())
        assertEquals(1, flows.size)
        assertEquals("good", flows[0].name)
    }
}
