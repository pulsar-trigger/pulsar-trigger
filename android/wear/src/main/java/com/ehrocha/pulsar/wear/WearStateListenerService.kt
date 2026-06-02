/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Copyright (C) 2026 Pulsar Trigger contributors
 */

package com.ehrocha.pulsar.wear

import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.WearableListenerService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Wear-side listener for run-state updates pushed by the phone via the
 * Wearable DataClient. State is published through [latest], which the
 * activity's Compose tree observes.
 */
class WearStateListenerService : WearableListenerService() {

    override fun onDataChanged(events: DataEventBuffer) {
        for (event in events) {
            if (event.type != DataEvent.TYPE_CHANGED) continue
            val item = event.dataItem
            if (item.uri.path != RunStateProtocol.PATH_RUN_STATE) continue
            val map = DataMapItem.fromDataItem(item).dataMap
            _latest.value = WearRunState(
                connected = map.getBoolean(RunStateProtocol.KEY_CONNECTED, false),
                running = map.getBoolean(RunStateProtocol.KEY_RUNNING, false),
                modeLabel = map.getString(RunStateProtocol.KEY_MODE_LABEL, ""),
                shotsTaken = map.getInt(RunStateProtocol.KEY_SHOTS_TAKEN, 0),
                plannedShots = map.getInt(RunStateProtocol.KEY_PLANNED_SHOTS, 0),
                timeRemainingMs = map.getLong(RunStateProtocol.KEY_TIME_REMAINING_MS, 0L),
                deviceState = map.getString(RunStateProtocol.KEY_DEVICE_STATE, ""),
            )
        }
    }

    companion object {
        // The activity reads from this — making it a singleton on the
        // service side keeps the listener wire-up trivial (no binding
        // dance, no LocalBroadcastManager).
        private val _latest = MutableStateFlow(WearRunState())
        val latest: StateFlow<WearRunState> = _latest.asStateFlow()
    }
}
