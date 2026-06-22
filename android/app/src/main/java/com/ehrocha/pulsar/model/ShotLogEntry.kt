/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Copyright (C) 2026 Pulsar Trigger contributors
 */

package com.ehrocha.pulsar.model

import org.json.JSONObject

enum class ShotLogStatus { COMPLETED, STOPPED, ERROR }

/**
 * One past trigger run. Saved when a flow finishes (success, manual stop, or
 * error). Read-only history — entries are never edited after insertion.
 */
/** Snapshot of the relevant slice of [com.ehrocha.pulsar.astro.DashboardState]
 *  at the moment a run was started — what the sky / weather looked like.
 *  Null on entries from before this field shipped, or when no dashboard
 *  snapshot is cached on the device.
 *
 *  Stored alongside the run record so a user looking back at "last
 *  Tuesday's M31 session" can see the *why* of how shots came out
 *  (cloud %, moon, dew). Mirror of the in-app Dashboard summary card —
 *  values come straight from the cached DashboardState. */
data class ConditionSnapshot(
    val cityName: String? = null,
    val moonPhase: String? = null,
    val moonIlluminationPct: Double? = null,
    val moonGoodForAstro: Boolean? = null,
    val cloudCoverPct: Int? = null,
    val temperatureC: Double? = null,
    val dewPointC: Double? = null,
    val dewRisk: String? = null,   // serialized DewRisk enum name
    val bortleClass: Double? = null,
    val mwVisible: Boolean? = null,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        cityName?.let { put("city", it) }
        moonPhase?.let { put("moonPhase", it) }
        moonIlluminationPct?.let { put("moonIllum", it) }
        moonGoodForAstro?.let { put("moonAstro", it) }
        cloudCoverPct?.let { put("cloud", it) }
        temperatureC?.let { put("tempC", it) }
        dewPointC?.let { put("dewC", it) }
        dewRisk?.let { put("dewRisk", it) }
        bortleClass?.let { put("bortle", it) }
        mwVisible?.let { put("mwVisible", it) }
    }

    companion object {
        fun fromJson(j: JSONObject): ConditionSnapshot = ConditionSnapshot(
            cityName = j.optString("city").takeIf { it.isNotEmpty() },
            moonPhase = j.optString("moonPhase").takeIf { it.isNotEmpty() },
            moonIlluminationPct = j.opt("moonIllum") as? Double
                ?: (j.opt("moonIllum") as? Int)?.toDouble(),
            moonGoodForAstro = if (j.has("moonAstro")) j.optBoolean("moonAstro") else null,
            cloudCoverPct = if (j.has("cloud")) j.optInt("cloud") else null,
            temperatureC = j.opt("tempC") as? Double
                ?: (j.opt("tempC") as? Int)?.toDouble(),
            dewPointC = j.opt("dewC") as? Double
                ?: (j.opt("dewC") as? Int)?.toDouble(),
            dewRisk = j.optString("dewRisk").takeIf { it.isNotEmpty() },
            bortleClass = j.opt("bortle") as? Double
                ?: (j.opt("bortle") as? Int)?.toDouble(),
            mwVisible = if (j.has("mwVisible")) j.optBoolean("mwVisible") else null,
        )
    }
}

data class ShotLogEntry(
    val id: Long,
    val startedAtMs: Long,
    val endedAtMs: Long,
    val modeLabel: String,
    val stepCount: Int,
    val plannedShots: Int,
    val completedShots: Int,
    val exposureMs: Long,
    val intervalMs: Long,
    val status: ShotLogStatus,
    /** Sky/weather conditions at run start. Null on entries from before
     *  v0.327 (when this field was introduced) or when no cached dashboard
     *  snapshot was available on the device at run start. */
    val conditions: ConditionSnapshot? = null,
    /** The originating single run-step (Intervalometer / Astro / DarkFrame /
     *  Ramp), captured so a past session can be re-saved as a preset. Null for
     *  multi-step custom flows and for entries logged before this was added. */
    val presetStep: FlowStep? = null,
) {
    /** Whether this session carries enough captured config to become a preset
     *  (a single, preset-able run-step). Old entries return false. */
    fun canMakePreset(): Boolean = presetStep != null

    fun durationMs(): Long = endedAtMs - startedAtMs

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("startedAtMs", startedAtMs)
        put("endedAtMs", endedAtMs)
        put("modeLabel", modeLabel)
        put("stepCount", stepCount)
        put("plannedShots", plannedShots)
        put("completedShots", completedShots)
        put("exposureMs", exposureMs)
        put("intervalMs", intervalMs)
        put("status", status.name)
        conditions?.let { put("conditions", it.toJson()) }
        presetStep?.let { put("presetStep", it.toJson()) }
    }

    companion object {
        fun fromJson(j: JSONObject): ShotLogEntry = ShotLogEntry(
            id = j.optLong("id", System.currentTimeMillis()),
            startedAtMs = j.optLong("startedAtMs", 0),
            endedAtMs = j.optLong("endedAtMs", 0),
            modeLabel = j.optString("modeLabel", ""),
            stepCount = j.optInt("stepCount", 1),
            plannedShots = j.optInt("plannedShots", 0),
            completedShots = j.optInt("completedShots", 0),
            exposureMs = j.optLong("exposureMs", 0),
            intervalMs = j.optLong("intervalMs", 0),
            status = runCatching {
                ShotLogStatus.valueOf(j.optString("status", "COMPLETED"))
            }.getOrDefault(ShotLogStatus.COMPLETED),
            conditions = j.optJSONObject("conditions")?.let { ConditionSnapshot.fromJson(it) },
            presetStep = j.optJSONObject("presetStep")?.let { FlowStep.fromJson(it) },
        )
    }
}
