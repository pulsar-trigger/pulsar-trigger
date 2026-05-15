/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Copyright (C) 2026 Pulsar Trigger contributors
 */

package com.ehrocha.pulsar.model

import com.ehrocha.pulsar.AppConfig
import com.ehrocha.pulsar.ble.TriggerMode
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * User-authored trigger-mode preset. Single firmware mode + its parameter set.
 *
 * See `docs/mode-schema.md` for the on-disk schema. PRESS_HOLD / PRESS_LOCK /
 * TRACKER / CUSTOM_FLOW aren't representable as a preset — they're imperative
 * controls (PRESS_*) or app-orchestrated (CUSTOM_FLOW).
 */
data class UserMode(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val description: String = "",
    val tags: List<String> = emptyList(),
    val body: Body,
) {
    data class Body(
        val fwMode: TriggerMode = TriggerMode.INTERVALOMETER,
        val intervalMs: Long = AppConfig.DEFAULT_INTERVAL_MS,
        val exposureMs: Long = AppConfig.DEFAULT_EXPOSURE_MS,
        val shotCount: Int = AppConfig.DEFAULT_SHOT_COUNT,
        val delayMs: Long = AppConfig.DEFAULT_DELAY_MS,
        // Astro / Ramp specifics — ignored when fwMode doesn't use them.
        val focalLength: Int = AppConfig.DEFAULT_FOCAL_LENGTH,
        val cropFactor: Float = AppConfig.DEFAULT_CROP_FACTOR,
        val ruleDivisor: Int = AppConfig.DEFAULT_RULE_DIVISOR,
        val rampStartExposureMs: Long = 500L,
        val rampEndExposureMs: Long = 10_000L,
        val rampSteps: Int = 50,
    )

    fun toJson(): JSONObject = JSONObject().apply {
        put("schema", SCHEMA_ID)
        put("kind", "trigger")
        put("id", id)
        put("name", name)
        if (description.isNotEmpty()) put("description", description)
        if (tags.isNotEmpty()) put("tags", JSONArray().apply { tags.forEach { put(it) } })
        put("body", JSONObject().apply {
            put("fwMode", body.fwMode.name)
            put("params", JSONObject().apply {
                put("intervalMs", body.intervalMs)
                put("exposureMs", body.exposureMs)
                put("shotCount", body.shotCount)
                put("delayMs", body.delayMs)
                if (body.fwMode == TriggerMode.ASTRO) {
                    put("focalLength", body.focalLength)
                    put("cropFactor", body.cropFactor.toDouble())
                    put("ruleDivisor", body.ruleDivisor)
                }
                if (body.fwMode == TriggerMode.RAMP) {
                    put("rampStartExposureMs", body.rampStartExposureMs)
                    put("rampEndExposureMs", body.rampEndExposureMs)
                    put("rampSteps", body.rampSteps)
                }
            })
        })
    }

    companion object {
        const val SCHEMA_ID = "pulsar-mode/1"
        const val BUNDLE_SCHEMA_ID = "pulsar-mode-bundle/1"

        /** Maximum user modes that can be saved at once. */
        const val MAX_USER_MODES = 5

        fun fromJson(json: JSONObject): UserMode? {
            if (json.optString("schema") != SCHEMA_ID) return null
            if (json.optString("kind") != "trigger") return null
            val name = json.optString("name").takeIf { it.isNotEmpty() } ?: return null
            val bodyJson = json.optJSONObject("body") ?: return null
            val fw = TriggerMode.entries.firstOrNull { it.name == bodyJson.optString("fwMode") }
                ?: return null
            if (fw == TriggerMode.PRESS_HOLD || fw == TriggerMode.PRESS_LOCK ||
                fw == TriggerMode.TRACKER || fw == TriggerMode.CUSTOM_FLOW) return null
            val params = bodyJson.optJSONObject("params") ?: JSONObject()
            val body = Body(
                fwMode = fw,
                intervalMs = params.optLong("intervalMs", AppConfig.DEFAULT_INTERVAL_MS),
                exposureMs = params.optLong("exposureMs", AppConfig.DEFAULT_EXPOSURE_MS),
                shotCount = params.optInt("shotCount", AppConfig.DEFAULT_SHOT_COUNT),
                delayMs = params.optLong("delayMs", AppConfig.DEFAULT_DELAY_MS),
                focalLength = params.optInt("focalLength", AppConfig.DEFAULT_FOCAL_LENGTH),
                cropFactor = params.optDouble("cropFactor", AppConfig.DEFAULT_CROP_FACTOR.toDouble()).toFloat(),
                ruleDivisor = params.optInt("ruleDivisor", AppConfig.DEFAULT_RULE_DIVISOR),
                rampStartExposureMs = params.optLong("rampStartExposureMs", 500L),
                rampEndExposureMs = params.optLong("rampEndExposureMs", 10_000L),
                rampSteps = params.optInt("rampSteps", 50),
            )
            return UserMode(
                id = json.optString("id").takeIf { it.isNotEmpty() } ?: UUID.randomUUID().toString(),
                name = name,
                description = json.optString("description", ""),
                tags = json.optJSONArray("tags")?.let { arr ->
                    (0 until arr.length()).map { arr.getString(it) }
                } ?: emptyList(),
                body = body,
            )
        }

        fun serializeList(modes: List<UserMode>): String =
            JSONArray().apply { modes.forEach { put(it.toJson()) } }.toString()

        fun deserializeList(s: String): List<UserMode> {
            if (s.isBlank()) return emptyList()
            val arr = JSONArray(s)
            return (0 until arr.length()).mapNotNull {
                runCatching { fromJson(arr.getJSONObject(it)) }.getOrNull()
            }
        }

        /** Wrap a list of modes in a bundle envelope for sharing. */
        fun bundleJson(modes: List<UserMode>): String = JSONObject().apply {
            put("schema", BUNDLE_SCHEMA_ID)
            put("modes", JSONArray().apply { modes.forEach { put(it.toJson()) } })
        }.toString(2)

        /** Single-mode export string. */
        fun singleJson(mode: UserMode): String = mode.toJson().toString(2)

        /** Parse a single-mode or bundle file. */
        fun parseImport(s: String): List<UserMode> {
            val obj = runCatching { JSONObject(s) }.getOrNull() ?: return emptyList()
            return when (obj.optString("schema")) {
                BUNDLE_SCHEMA_ID -> obj.optJSONArray("modes")?.let { arr ->
                    (0 until arr.length()).mapNotNull {
                        runCatching { fromJson(arr.getJSONObject(it)) }.getOrNull()
                    }
                } ?: emptyList()
                SCHEMA_ID -> listOfNotNull(fromJson(obj))
                else -> emptyList()
            }
        }
    }
}
