/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Copyright (C) 2026 Pulsar Trigger contributors
 */

package com.ehrocha.pulsar.planner

import java.time.LocalDate

data class SavedLocation(
    val id: String,
    val name: String,
    val latitude: Double,
    val longitude: Double,
)

data class PlannerEntry(
    val id: String,
    val location: SavedLocation,
    val date: LocalDate,
    val lastChecked: Long = 0L,       // System.currentTimeMillis()
    val verdict: PlannerVerdict = PlannerVerdict.UNKNOWN,
    val summary: String = "",
)

enum class PlannerVerdict {
    UNKNOWN, EXCELLENT, GOOD, FAIR, POOR
}

data class PlannerState(
    val locations: List<SavedLocation> = emptyList(),
    val entries: List<PlannerEntry> = emptyList(),
)
