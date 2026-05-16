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
) {
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
        )
    }
}
