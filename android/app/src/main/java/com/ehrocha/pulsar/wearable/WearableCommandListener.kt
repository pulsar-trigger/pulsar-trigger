/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Copyright (C) 2026 Pulsar Trigger contributors
 */

package com.ehrocha.pulsar.wearable

import android.util.Log
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService
import kotlinx.coroutines.flow.MutableSharedFlow

/**
 * Phone-side receiver for commands the watch issues over MessageClient.
 * Currently the only command is Stop — when received, the viewmodel
 * picks it up via the shared [commands] flow and invokes `stopFlow()`.
 *
 * Wiring is: watch taps Stop → MessageClient.sendMessage(/pulsar/cmd/stop)
 * → this service.onMessageReceived → emit on commands → viewmodel's
 * collector calls stopFlow.
 */
class WearableCommandListener : WearableListenerService() {

    override fun onMessageReceived(event: MessageEvent) {
        when (event.path) {
            RunStateWire.PATH_CMD_STOP -> {
                Log.i(TAG, "Wear → phone: Stop")
                _commands.tryEmit(WearCommand.Stop)
            }
            else -> Log.d(TAG, "ignored path=${event.path}")
        }
    }

    companion object {
        private const val TAG = "WearListener"

        // Replay 0 so a late subscriber doesn't process a stale Stop;
        // extraBufferCapacity 4 so a quick double-tap from the watch
        // doesn't drop the second event before the viewmodel collects.
        private val _commands = MutableSharedFlow<WearCommand>(
            replay = 0,
            extraBufferCapacity = 4,
        )
        val commands: kotlinx.coroutines.flow.SharedFlow<WearCommand> = _commands
    }
}

sealed interface WearCommand {
    data object Stop : WearCommand
}
