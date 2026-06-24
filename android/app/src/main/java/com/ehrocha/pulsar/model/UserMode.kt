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
    /** Set when this preset was imported from the network catalog — the entry
     *  id + version it came from. Drives the imported badge, the Uninstall
     *  action, and the catalog's installed/update state (all derived from
     *  existence, so deleting it self-corrects). Null = user-created. */
    val catalogId: String? = null,
    val catalogVersion: Int? = null,
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
        /** Whether to send `af: true` on CCAPI shutter calls. Ignored on
         *  the BLE/ESP32 path. Default off so bulb astro runs don't try to
         *  AF on stars between shots. */
        val useAutofocus: Boolean = false,
    ) {
        /** Clamp every numeric field to its supported range. Applied on IMPORT
         *  (catalog / shared file) so an untrusted entry can't introduce
         *  out-of-range or unsupported settings. Idempotent for valid data. */
        fun sanitized(): Body = copy(
            intervalMs = intervalMs.coerceIn(AppConfig.MIN_INTERVAL_MS, 86_400_000L), // ≤ 24 h
            exposureMs = exposureMs.coerceIn(AppConfig.MIN_EXPOSURE_MS, 3_600_000L),   // ≤ 1 h
            shotCount = shotCount.coerceIn(AppConfig.MIN_SHOT_COUNT, 100_000),
            delayMs = delayMs.coerceIn(0L, 86_400_000L),
            focalLength = focalLength.coerceIn(AppConfig.MIN_FOCAL_LENGTH, AppConfig.MAX_FOCAL_LENGTH),
            cropFactor = cropFactor.coerceIn(1f, 3f),
            ruleDivisor = ruleDivisor.coerceIn(0, 1000),
            rampStartExposureMs = rampStartExposureMs.coerceIn(AppConfig.MIN_EXPOSURE_MS, 3_600_000L),
            rampEndExposureMs = rampEndExposureMs.coerceIn(AppConfig.MIN_EXPOSURE_MS, 3_600_000L),
            rampSteps = rampSteps.coerceIn(1, 1000),
        )
    }

    /** This preset with its [Body] clamped to supported ranges — for import. */
    fun sanitized(): UserMode = copy(body = body.sanitized())

    fun toJson(): JSONObject = JSONObject().apply {
        put("schema", SCHEMA_ID)
        put("kind", "trigger")
        put("id", id)
        put("name", name)
        if (description.isNotEmpty()) put("description", description)
        if (tags.isNotEmpty()) put("tags", JSONArray().apply { tags.forEach { put(it) } })
        catalogId?.let { put("catalogId", it) }
        catalogVersion?.let { put("catalogVersion", it) }
        put("body", JSONObject().apply {
            put("fwMode", body.fwMode.name)
            put("params", JSONObject().apply {
                put("intervalMs", body.intervalMs)
                put("exposureMs", body.exposureMs)
                put("shotCount", body.shotCount)
                put("delayMs", body.delayMs)
                put("useAutofocus", body.useAutofocus)
                if (body.fwMode == TriggerMode.ASTRO || body.fwMode == TriggerMode.STAR_TRAILS) {
                    // Star Trails reuses focalLength + cropFactor, and stashes the
                    // sensor index in ruleDivisor.
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

        /** Maximum user modes that can be saved at once — a high safety bound
         *  (prevents unbounded SharedPreferences growth), not a UX limit. */
        const val MAX_USER_MODES = 200

        fun fromJson(json: JSONObject): UserMode? {
            if (json.optString("schema") != SCHEMA_ID) return null
            if (json.optString("kind") != "trigger") return null
            val name = json.optString("name").takeIf { it.isNotEmpty() } ?: return null
            val bodyJson = json.optJSONObject("body") ?: return null
            val fw = TriggerMode.entries.firstOrNull { it.name == bodyJson.optString("fwMode") }
                ?: return null
            // Allow-list: only modes that are storable as a preset.
            val allowed = setOf(
                TriggerMode.INTERVALOMETER, TriggerMode.ASTRO,
                TriggerMode.DARK_FRAME, TriggerMode.RAMP, TriggerMode.TIMELAPSE,
                TriggerMode.STAR_TRAILS,
            )
            if (fw !in allowed) return null
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
                useAutofocus = params.optBoolean("useAutofocus", false),
            )
            return UserMode(
                id = json.optString("id").takeIf { it.isNotEmpty() } ?: UUID.randomUUID().toString(),
                name = name,
                description = json.optString("description", ""),
                tags = json.optJSONArray("tags")?.let { arr ->
                    (0 until arr.length()).map { arr.getString(it) }
                } ?: emptyList(),
                catalogId = json.optString("catalogId").takeIf { it.isNotEmpty() },
                catalogVersion = if (json.has("catalogVersion")) json.optInt("catalogVersion") else null,
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
