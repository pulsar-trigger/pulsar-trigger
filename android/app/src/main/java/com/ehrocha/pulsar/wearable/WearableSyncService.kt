/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Copyright (C) 2026 Pulsar Trigger contributors
 */

package com.ehrocha.pulsar.wearable

/**
 * Mirror of the wire constants in [com.ehrocha.pulsar.wear.RunStateProtocol]
 * (the watch module). Kept duplicated rather than shared via a third
 * common module — the strings are stable and the dep-graph simplicity is
 * worth more than a 1-file `:common` module.
 */
internal object RunStateWire {
    const val PATH_RUN_STATE = "/pulsar/run_state"
    const val PATH_CMD_STOP = "/pulsar/cmd/stop"

    const val KEY_CONNECTED = "connected"
    const val KEY_RUNNING = "running"
    const val KEY_MODE_LABEL = "modeLabel"
    const val KEY_SHOTS_TAKEN = "shotsTaken"
    const val KEY_PLANNED_SHOTS = "plannedShots"
    const val KEY_TIME_REMAINING_MS = "timeRemainingMs"
    const val KEY_DEVICE_STATE = "deviceState"
    const val KEY_TS = "ts"
}
