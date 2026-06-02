/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Copyright (C) 2026 Pulsar Trigger contributors
 */

package com.ehrocha.pulsar.wear

/**
 * Shared wire constants between the phone (writer) and the watch (reader).
 * Mirror this file in the phone module to keep paths + keys in lockstep —
 * the data-layer wire is untyped so a drift here = silent breakage.
 */
object RunStateProtocol {
    /** DataClient item path the phone writes when the run state changes. */
    const val PATH_RUN_STATE = "/pulsar/run_state"

    /** MessageClient path the watch sends to abort the active run. */
    const val PATH_CMD_STOP = "/pulsar/cmd/stop"

    // DataMap keys on PATH_RUN_STATE — fixed schema.
    const val KEY_CONNECTED = "connected"
    const val KEY_RUNNING = "running"
    const val KEY_MODE_LABEL = "modeLabel"
    const val KEY_SHOTS_TAKEN = "shotsTaken"
    const val KEY_PLANNED_SHOTS = "plannedShots"
    const val KEY_TIME_REMAINING_MS = "timeRemainingMs"
    const val KEY_DEVICE_STATE = "deviceState"
    const val KEY_TS = "ts"   // Phone wallclock when written — disambiguates duplicate writes.
}

/** In-watch representation of the phone state. The DataClient listener
 *  hydrates one of these on each change and the UI re-renders. */
data class WearRunState(
    val connected: Boolean = false,
    val running: Boolean = false,
    val modeLabel: String = "",
    val shotsTaken: Int = 0,
    val plannedShots: Int = 0,
    val timeRemainingMs: Long = 0L,
    val deviceState: String = "",
)
