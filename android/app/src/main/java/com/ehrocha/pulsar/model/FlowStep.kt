/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Copyright (C) 2026 Pulsar Trigger contributors
 */

package com.ehrocha.pulsar.model

import android.content.Context
import com.ehrocha.pulsar.AppConfig
import com.ehrocha.pulsar.R
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Discriminator for picker/icon/label lookups. Each enum corresponds to
 *  exactly one [FlowStep] subtype; use the subtype directly when operating
 *  on a step's content. */
enum class FlowStepType {
    INTERVALOMETER,
    ASTRO,
    PAUSE,
    DARK_FRAME,
    RAMP,
}

/**
 * One step in a flow. Variants carry only the fields they actually use —
 * unlike the prior union-struct layout where every field lived on every
 * step. Consumers operate on a step via an exhaustive `when (step)` block.
 *
 * On-disk JSON keeps a `"type"` discriminator key; per-variant fields are
 * written and read in their matching branch only. Files written before
 * this refactor (every field stored as a sibling) still load — extra keys
 * are ignored, missing ones default. New saves are leaner.
 */
sealed class FlowStep {
    abstract val id: String
    abstract val type: FlowStepType

    data class Intervalometer(
        override val id: String = UUID.randomUUID().toString(),
        val intervalMs: Long = AppConfig.DEFAULT_INTERVAL_MS,
        val exposureMs: Long = AppConfig.DEFAULT_EXPOSURE_MS,
        val shotCount: Int = AppConfig.DEFAULT_SHOT_COUNT,
        val delayMs: Long = AppConfig.DEFAULT_DELAY_MS,
    ) : FlowStep() {
        override val type get() = FlowStepType.INTERVALOMETER
    }

    data class Astro(
        override val id: String = UUID.randomUUID().toString(),
        val focalLength: Int = AppConfig.DEFAULT_FOCAL_LENGTH,
        val cropFactor: Float = AppConfig.DEFAULT_CROP_FACTOR,
        val ruleDivisor: Int = AppConfig.DEFAULT_RULE_DIVISOR,
        val gapMs: Long = AppConfig.DEFAULT_ASTRO_GAP_MS,
        val shotCount: Int = AppConfig.DEFAULT_SHOT_COUNT,
        val delayMs: Long = AppConfig.DEFAULT_ASTRO_DELAY_MS,
    ) : FlowStep() {
        override val type get() = FlowStepType.ASTRO
    }

    data class DarkFrame(
        override val id: String = UUID.randomUUID().toString(),
        val exposureMs: Long = AppConfig.DEFAULT_EXPOSURE_MS,
        val shotCount: Int = 10,
        val gapMs: Long = AppConfig.DEFAULT_ASTRO_GAP_MS,
    ) : FlowStep() {
        override val type get() = FlowStepType.DARK_FRAME
    }

    data class Ramp(
        override val id: String = UUID.randomUUID().toString(),
        val startExposureMs: Long = 500L,
        val endExposureMs: Long = 10_000L,
        val steps: Int = 50,
        val intervalMs: Long = AppConfig.DEFAULT_INTERVAL_MS,
    ) : FlowStep() {
        override val type get() = FlowStepType.RAMP
    }

    data class Pause(
        override val id: String = UUID.randomUUID().toString(),
        val label: String = "Adjust camera settings",
        val wakeOnPause: Boolean = true,
    ) : FlowStep() {
        override val type get() = FlowStepType.PAUSE
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("type", type.name)
        when (val s = this@FlowStep) {
            is Intervalometer -> {
                put("intervalMs", s.intervalMs)
                put("exposureMs", s.exposureMs)
                put("shotCount", s.shotCount)
                put("delayMs", s.delayMs)
            }
            is Astro -> {
                put("focalLength", s.focalLength)
                put("cropFactor", s.cropFactor.toDouble())
                put("ruleDivisor", s.ruleDivisor)
                put("gapMs", s.gapMs)
                put("shotCount", s.shotCount)
                put("delayMs", s.delayMs)
            }
            is DarkFrame -> {
                put("exposureMs", s.exposureMs)
                put("shotCount", s.shotCount)
                put("gapMs", s.gapMs)
            }
            is Ramp -> {
                put("startExposureMs", s.startExposureMs)
                put("endExposureMs", s.endExposureMs)
                put("steps", s.steps)
                put("intervalMs", s.intervalMs)
            }
            is Pause -> {
                put("label", s.label)
                put("wakeOnPause", s.wakeOnPause)
            }
        }
    }

    companion object {
        /** Construct a step of the requested type with default field values. */
        fun forType(type: FlowStepType): FlowStep = when (type) {
            FlowStepType.INTERVALOMETER -> Intervalometer()
            FlowStepType.ASTRO -> Astro()
            FlowStepType.DARK_FRAME -> DarkFrame()
            FlowStepType.RAMP -> Ramp()
            FlowStepType.PAUSE -> Pause()
        }

        fun fromJson(json: JSONObject): FlowStep {
            val id = json.optString("id").takeIf { it.isNotEmpty() }
                ?: UUID.randomUUID().toString()
            val type = FlowStepType.entries.firstOrNull { it.name == json.optString("type") }
                ?: FlowStepType.PAUSE
            return when (type) {
                FlowStepType.INTERVALOMETER -> Intervalometer(
                    id = id,
                    intervalMs = json.optLong("intervalMs", AppConfig.DEFAULT_INTERVAL_MS),
                    exposureMs = json.optLong("exposureMs", AppConfig.DEFAULT_EXPOSURE_MS),
                    shotCount = json.optInt("shotCount", AppConfig.DEFAULT_SHOT_COUNT),
                    delayMs = json.optLong("delayMs", AppConfig.DEFAULT_DELAY_MS),
                )
                FlowStepType.ASTRO -> Astro(
                    id = id,
                    focalLength = json.optInt("focalLength", AppConfig.DEFAULT_FOCAL_LENGTH),
                    cropFactor = json.optDouble("cropFactor", AppConfig.DEFAULT_CROP_FACTOR.toDouble()).toFloat(),
                    ruleDivisor = json.optInt("ruleDivisor", AppConfig.DEFAULT_RULE_DIVISOR),
                    gapMs = json.optLong("gapMs", AppConfig.DEFAULT_ASTRO_GAP_MS),
                    shotCount = json.optInt("shotCount", AppConfig.DEFAULT_SHOT_COUNT),
                    delayMs = json.optLong("delayMs", AppConfig.DEFAULT_ASTRO_DELAY_MS),
                )
                FlowStepType.DARK_FRAME -> DarkFrame(
                    id = id,
                    // Pre-refactor files stored the exposure under "darkFrameExposureMs"
                    // and the count under "darkFrameCount". Fall back to those keys so
                    // existing saved flows still load.
                    exposureMs = json.optLong(
                        "exposureMs",
                        json.optLong("darkFrameExposureMs", AppConfig.DEFAULT_EXPOSURE_MS),
                    ),
                    shotCount = json.optInt(
                        "shotCount",
                        json.optInt("darkFrameCount", 10),
                    ),
                    gapMs = json.optLong(
                        "gapMs",
                        json.optLong("darkFrameGapMs", AppConfig.DEFAULT_ASTRO_GAP_MS),
                    ),
                )
                FlowStepType.RAMP -> Ramp(
                    id = id,
                    startExposureMs = json.optLong(
                        "startExposureMs",
                        json.optLong("rampStartExposureMs", 500L),
                    ),
                    endExposureMs = json.optLong(
                        "endExposureMs",
                        json.optLong("rampEndExposureMs", 10_000L),
                    ),
                    steps = json.optInt("steps", json.optInt("rampSteps", 50)),
                    intervalMs = json.optLong(
                        "intervalMs",
                        json.optLong("rampIntervalMs", AppConfig.DEFAULT_INTERVAL_MS),
                    ),
                )
                FlowStepType.PAUSE -> Pause(
                    id = id,
                    label = json.optString("label",
                        json.optString("pauseLabel", "Adjust camera settings")),
                    wakeOnPause = json.optBoolean("wakeOnPause", true),
                )
            }
        }

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
fun FlowStep.summaryLabel(context: Context): String = when (this) {
    is FlowStep.Intervalometer -> context.getString(R.string.step_summary_intervalometer, shotCount, exposureMs, intervalMs)
    is FlowStep.Astro -> {
        val expS = AppConfig.astroExposureS(focalLength, cropFactor, ruleDivisor)
        context.getString(R.string.step_summary_astro, shotCount, "%.1f".format(expS), focalLength)
    }
    is FlowStep.Pause -> label
    is FlowStep.DarkFrame -> context.getString(R.string.step_summary_dark_frame, shotCount, exposureMs)
    is FlowStep.Ramp -> context.getString(R.string.step_summary_ramp, steps, startExposureMs, endExposureMs)
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
        val label = if (cropFactor == 1.0f) "NPF – ${focalLengthMm}mm FF"
                    else "NPF – ${focalLengthMm}mm (${cropFactor}×)"
        return SavedFlow(
            name = label,
            steps = listOf(
                FlowStep.Pause(
                    label = "Confirm focus and adjust camera settings",
                    wakeOnPause = true,
                ),
                FlowStep.Astro(
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
                FlowStep.Pause(
                    label = "Put the lens cap on and keep the same temperature",
                    wakeOnPause = true,
                ),
                FlowStep.DarkFrame(
                    shotCount = count,
                    exposureMs = exposureMs,
                    gapMs = AppConfig.DEFAULT_ASTRO_GAP_MS,
                ),
            ),
            builtIn = true,
            tags = listOf("Dark Frames"),
        )
    }

    val ALL: List<SavedFlow> = listOf(
        astroPreset(14),
        astroPreset(24),
        astroPreset(50),
        astroPreset(85),
        astroPreset(135),
        astroPreset(200),
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
