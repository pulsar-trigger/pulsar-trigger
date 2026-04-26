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
    DARK_FRAME,
    RAMP,
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
    // Dark Frame
    val darkFrameCount: Int = 10,
    val darkFrameExposureMs: Long = AppConfig.DEFAULT_EXPOSURE_MS,
    val darkFrameGapMs: Long = AppConfig.DEFAULT_ASTRO_GAP_MS,
    // Ramp (Holy Grail timelapse)
    val rampStartExposureMs: Long = 500L,
    val rampEndExposureMs: Long = 10000L,
    val rampSteps: Int = 50,
    val rampIntervalMs: Long = AppConfig.DEFAULT_INTERVAL_MS,
    // Pause
    val pauseLabel: String = "Adjust camera settings",
    /** When true, the screen wakes and vibrates when this pause step is reached. */
    val wakeOnPause: Boolean = true,
    /** Phone-camera-only: if non-null, lock the manual ISO to this value for the
     *  duration of the step (e.g. low ISO for a long-exposure foreground frame). */
    val isoOverride: Int? = null,
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
        put("darkFrameCount", darkFrameCount)
        put("darkFrameExposureMs", darkFrameExposureMs)
        put("darkFrameGapMs", darkFrameGapMs)
        put("rampStartExposureMs", rampStartExposureMs)
        put("rampEndExposureMs", rampEndExposureMs)
        put("rampSteps", rampSteps)
        put("rampIntervalMs", rampIntervalMs)
        put("pauseLabel", pauseLabel)
        put("wakeOnPause", wakeOnPause)
        if (isoOverride != null) put("isoOverride", isoOverride)
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
            darkFrameCount = json.optInt("darkFrameCount", 10),
            darkFrameExposureMs = json.optLong("darkFrameExposureMs", AppConfig.DEFAULT_EXPOSURE_MS),
            darkFrameGapMs = json.optLong("darkFrameGapMs", AppConfig.DEFAULT_ASTRO_GAP_MS),
            rampStartExposureMs = json.optLong("rampStartExposureMs", 500L),
            rampEndExposureMs = json.optLong("rampEndExposureMs", 10000L),
            rampSteps = json.optInt("rampSteps", 50),
            rampIntervalMs = json.optLong("rampIntervalMs", AppConfig.DEFAULT_INTERVAL_MS),
            pauseLabel = json.optString("pauseLabel", "Adjust camera settings"),
            wakeOnPause = json.optBoolean("wakeOnPause", true),
            isoOverride = if (json.has("isoOverride")) json.getInt("isoOverride") else null,
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
    FlowStepType.DARK_FRAME -> context.getString(R.string.step_summary_dark_frame, darkFrameCount, darkFrameExposureMs)
    FlowStepType.RAMP -> context.getString(R.string.step_summary_ramp, rampSteps, rampStartExposureMs, rampEndExposureMs)
}

fun FlowStepType.displayName(context: Context): String = when (this) {
    FlowStepType.INTERVALOMETER -> context.getString(R.string.step_type_intervalometer)
    FlowStepType.ASTRO -> context.getString(R.string.step_type_astro)
    FlowStepType.PAUSE -> context.getString(R.string.step_type_pause)
    FlowStepType.DARK_FRAME -> context.getString(R.string.step_type_dark_frame)
    FlowStepType.RAMP -> context.getString(R.string.step_type_ramp)
}

// ── Saved Flow (named flow preset) ──────────────────────────────────────────

/** Built-in NPF-rule presets for common focal lengths.
 *  Uses [AppConfig.astroExposureMs] with [AppConfig.NPF_RULE_DIVISOR] to compute
 *  scientifically grounded exposure times based on sensor pixel pitch. */
object FlowPresets {
    private fun astroPreset(focalLengthMm: Int, cropFactor: Float = 1.0f): SavedFlow {
        val exposureMs = AppConfig.astroExposureMs(focalLengthMm, cropFactor, AppConfig.NPF_RULE_DIVISOR)
        val label = if (cropFactor == 1.0f) "NPF – ${focalLengthMm}mm FF"
                    else "NPF – ${focalLengthMm}mm (${cropFactor}×)"
        return SavedFlow(
            name = label,
            steps = listOf(
                FlowStep(
                    type = FlowStepType.PAUSE,
                    pauseLabel = "Confirm focus and adjust camera settings",
                    wakeOnPause = true,
                ),
                FlowStep(
                    type = FlowStepType.ASTRO,
                    focalLength = focalLengthMm,
                    cropFactor = cropFactor,
                    ruleDivisor = AppConfig.NPF_RULE_DIVISOR,
                    gapMs = AppConfig.DEFAULT_ASTRO_GAP_MS,
                    shotCount = 100,
                    delayMs = AppConfig.DEFAULT_ASTRO_DELAY_MS,
                ),
            ),
            builtIn = true,
            tags = listOf("Astro"),
        )
    }

    private fun darkFramePreset(exposureMs: Long, count: Int, label: String): SavedFlow {
        return SavedFlow(
            name = label,
            steps = listOf(
                FlowStep(
                    type = FlowStepType.PAUSE,
                    pauseLabel = "Put the lens cap on and keep the same temperature",
                    wakeOnPause = true,
                ),
                FlowStep(
                    type = FlowStepType.DARK_FRAME,
                    darkFrameCount = count,
                    darkFrameExposureMs = exposureMs,
                    darkFrameGapMs = AppConfig.DEFAULT_ASTRO_GAP_MS,
                ),
            ),
            builtIn = true,
            tags = listOf("Dark Frames"),
        )
    }

    val ALL: List<SavedFlow> = listOf(
        // Astro NPF presets — full frame
        astroPreset(14),
        astroPreset(24),
        astroPreset(50),
        astroPreset(85),
        astroPreset(135),
        astroPreset(200),
        // Dark frame presets — common astro exposure durations
        darkFramePreset(exposureMs = 15_000L, count = 20, label = "Dark Frames – 15s"),
        darkFramePreset(exposureMs = 30_000L, count = 20, label = "Dark Frames – 30s"),
        darkFramePreset(exposureMs = 60_000L, count = 20, label = "Dark Frames – 60s"),
        darkFramePreset(exposureMs = 120_000L, count = 20, label = "Dark Frames – 120s"),
    )
}

data class SavedFlow(
    val name: String,
    val steps: List<FlowStep>,
    val builtIn: Boolean = false,
    val favorite: Boolean = false,
    val tags: List<String> = emptyList(),
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("name", name)
        put("steps", JSONArray().also { arr ->
            steps.forEach { arr.put(it.toJson()) }
        })
        put("favorite", favorite)
        put("tags", JSONArray().also { arr ->
            tags.forEach { arr.put(it) }
        })
    }

    companion object {
        fun fromJson(json: JSONObject): SavedFlow = SavedFlow(
            name = json.getString("name"),
            steps = json.optJSONArray("steps")?.let { arr ->
                (0 until arr.length()).map { FlowStep.fromJson(arr.getJSONObject(it)) }
            } ?: emptyList(),
            favorite = json.optBoolean("favorite", false),
            tags = json.optJSONArray("tags")?.let { arr ->
                (0 until arr.length()).map { arr.getString(it) }
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
