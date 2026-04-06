/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Copyright (C) 2026 Pulsar Trigger contributors
 */

package com.ehrocha.pulsar.planner

import java.time.LocalDate
import java.time.LocalTime

data class PlannerEvent(
    val id: String,
    val name: String,
    val startDate: LocalDate,
    val endDate: LocalDate,
)

data class PlannerSession(
    val id: String,
    val eventId: String,
    val name: String,
    val latitude: Double,
    val longitude: Double,
    val date: LocalDate,
    val startTime: LocalTime? = null,
    val endTime: LocalTime? = null,
    val lastChecked: Long = 0L,
    val verdict: PlannerVerdict = PlannerVerdict.UNKNOWN,
    val summary: String = "",
)

enum class PlannerVerdict {
    UNKNOWN, EXCELLENT, GOOD, FAIR, POOR
}

data class PlannerState(
    val events: List<PlannerEvent> = emptyList(),
    val sessions: List<PlannerSession> = emptyList(),
)
