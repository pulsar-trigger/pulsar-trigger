/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Copyright (C) 2026 Pulsar Trigger contributors
 */

package com.ehrocha.pulsar.model

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import com.ehrocha.pulsar.AppConfig
import com.ehrocha.pulsar.R

enum class FlowStepType {
    INTERVALOMETER,
    ASTRO,
    PAUSE,
}

data class FlowStep(
    val id: String = UUID.randomUUID().toString(),
    val type: FlowStepType = FlowStepType.PAUSE,
    // Intervalometer / Astro shared params
    val intervalMs: Long = AppConfig.DEFAULT_INTERVAL_MS,
    val exposureMs: Long = AppConfig.DEFAULT_EXPOSURE_MS,
    val shotCount: Int = 10,
    val delayMs: Long = AppConfig.DEFAULT_DELAY_MS,
    // Astro-specific
    val focalLength: Int = AppConfig.DEFAULT_FOCAL_LENGTH,
    val cropFactor: Float = AppConfig.DEFAULT_CROP_FACTOR,
    val ruleDivisor: Int = AppConfig.DEFAULT_RULE_DIVISOR,
    val gapMs: Long = AppConfig.DEFAULT_ASTRO_GAP_MS,
    // Pause
    val pauseLabel: String = "Adjust camera settings",
    /** When true, the screen wakes and vibrates when this pause step is reached. */
    val wakeOnPause: Boolean = true,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("type", type.name)
        put("intervalMs", intervalMs)
        put("exposureMs", exposureMs)
        put("shotCount", shotCount)
        put("delayMs", delayMs)
        put("focalLength", focalLength)
        put("cropFactor", cropFactor.toDouble())
        put("ruleDivisor", ruleDivisor)
        put("gapMs", gapMs)
        put("pauseLabel", pauseLabel)
        put("wakeOnPause", wakeOnPause)
    }

    companion object {
        fun fromJson(json: JSONObject): FlowStep = FlowStep(
            id = json.optString("id", UUID.randomUUID().toString()),
            type = FlowStepType.entries.firstOrNull { it.name == json.optString("type") }
                ?: FlowStepType.PAUSE,
            intervalMs = json.optLong("intervalMs", AppConfig.DEFAULT_INTERVAL_MS),
            exposureMs = json.optLong("exposureMs", AppConfig.DEFAULT_EXPOSURE_MS),
            shotCount = json.optInt("shotCount", 10),
            delayMs = json.optLong("delayMs", AppConfig.DEFAULT_DELAY_MS),
            focalLength = json.optInt("focalLength", AppConfig.DEFAULT_FOCAL_LENGTH),
            cropFactor = json.optDouble("cropFactor", AppConfig.DEFAULT_CROP_FACTOR.toDouble()).toFloat(),
            ruleDivisor = json.optInt("ruleDivisor", AppConfig.DEFAULT_RULE_DIVISOR),
            gapMs = json.optLong("gapMs", AppConfig.DEFAULT_ASTRO_GAP_MS),
            pauseLabel = json.optString("pauseLabel", "Adjust camera settings"),
            wakeOnPause = json.optBoolean("wakeOnPause", true),
        )

        fun serializeList(steps: List<FlowStep>): String {
            val arr = JSONArray()
            steps.forEach { arr.put(it.toJson()) }
            return arr.toString()
        }

        fun deserializeList(json: String): List<FlowStep> {
            if (json.isBlank()) return emptyList()
            val arr = JSONArray(json)
            return (0 until arr.length()).map { fromJson(arr.getJSONObject(it)) }
        }
    }
}

/** Summary label for a step in the flow builder list. */
fun FlowStep.summaryLabel(context: Context): String = when (type) {
    FlowStepType.INTERVALOMETER -> context.getString(R.string.step_summary_intervalometer, shotCount, exposureMs, intervalMs)
    FlowStepType.ASTRO -> {
        val expS = AppConfig.astroExposureS(focalLength, cropFactor, ruleDivisor)
        context.getString(R.string.step_summary_astro, shotCount, String.format("%.1f", expS), focalLength)
    }
    FlowStepType.PAUSE -> pauseLabel
}

fun FlowStepType.displayName(context: Context): String = when (this) {
    FlowStepType.INTERVALOMETER -> context.getString(R.string.step_type_intervalometer)
    FlowStepType.ASTRO -> context.getString(R.string.step_type_astro)
    FlowStepType.PAUSE -> context.getString(R.string.step_type_pause)
}

// ── Saved Flow (named flow preset) ──────────────────────────────────────────

data class SavedFlow(
    val name: String,
    val steps: List<FlowStep>,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("name", name)
        put("steps", JSONArray().also { arr ->
            steps.forEach { arr.put(it.toJson()) }
        })
    }

    companion object {
        fun fromJson(json: JSONObject): SavedFlow = SavedFlow(
            name = json.getString("name"),
            steps = json.optJSONArray("steps")?.let { arr ->
                (0 until arr.length()).map { FlowStep.fromJson(arr.getJSONObject(it)) }
            } ?: emptyList(),
        )

        fun serializeList(flows: List<SavedFlow>): String {
            val arr = JSONArray()
            flows.forEach { arr.put(it.toJson()) }
            return arr.toString()
        }

        fun deserializeList(json: String): List<SavedFlow> {
            if (json.isBlank()) return emptyList()
            val arr = JSONArray(json)
            return (0 until arr.length()).map { fromJson(arr.getJSONObject(it)) }
        }
    }
}
