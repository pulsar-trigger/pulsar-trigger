/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Copyright (C) 2026 Pulsar Trigger contributors
 */

package com.ehrocha.pulsar.model

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

enum class FlowStepType {
    INTERVALOMETER,
    ASTRO,
    PAUSE,
}

data class FlowStep(
    val id: String = UUID.randomUUID().toString(),
    val type: FlowStepType = FlowStepType.PAUSE,
    // Intervalometer / Astro shared params
    val intervalMs: Long = 5000,
    val exposureMs: Long = 200,
    val shotCount: Int = 10,
    val delayMs: Long = 0,
    // Astro-specific
    val focalLength: Int = 24,
    val cropFactor: Float = 1.0f,
    val ruleDivisor: Int = 500,
    val gapMs: Long = 2000,
    // Pause
    val pauseLabel: String = "Adjust camera settings",
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
    }

    companion object {
        fun fromJson(json: JSONObject): FlowStep = FlowStep(
            id = json.optString("id", UUID.randomUUID().toString()),
            type = FlowStepType.valueOf(json.getString("type")),
            intervalMs = json.optLong("intervalMs", 5000),
            exposureMs = json.optLong("exposureMs", 200),
            shotCount = json.optInt("shotCount", 10),
            delayMs = json.optLong("delayMs", 0),
            focalLength = json.optInt("focalLength", 24),
            cropFactor = json.optDouble("cropFactor", 1.0).toFloat(),
            ruleDivisor = json.optInt("ruleDivisor", 500),
            gapMs = json.optLong("gapMs", 2000),
            pauseLabel = json.optString("pauseLabel", "Adjust camera settings"),
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
fun FlowStep.summaryLabel(): String = when (type) {
    FlowStepType.INTERVALOMETER -> "$shotCount shots, ${exposureMs}ms exp, ${intervalMs}ms gap"
    FlowStepType.ASTRO -> {
        val expS = ruleDivisor.toDouble() / (focalLength * cropFactor)
        "$shotCount shots, ${String.format("%.1f", expS)}s exp (${focalLength}mm)"
    }
    FlowStepType.PAUSE -> pauseLabel
}

fun FlowStepType.displayName(): String = when (this) {
    FlowStepType.INTERVALOMETER -> "Intervalometer"
    FlowStepType.ASTRO -> "Astro"
    FlowStepType.PAUSE -> "Pause"
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
