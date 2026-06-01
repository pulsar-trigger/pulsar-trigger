/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Copyright (C) 2026 Pulsar Trigger contributors
 */

package com.ehrocha.pulsar.transport.ccapi

import com.ehrocha.pulsar.ble.DeviceState
import com.ehrocha.pulsar.viewmodel.PulsarViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext

/**
 * CCAPI-specific leaf helpers split out of [PulsarViewModel] for
 * readability. Cross-transport concerns (connect/disconnect, mutual
 * exclusion, error mapping) still live in the main viewmodel.
 *
 * Holds the run-loop until the CCAPI transport is no longer paused, then
 * returns. Throws if the transport was dropped entirely so the caller
 * bails instead of firing shots into the void. The flow's [DeviceState]
 * is flipped to WAITING while paused so the RunningView shows the paused
 * affordance rather than RUNNING.
 */
internal suspend fun PulsarViewModel.awaitCcapiReady(ccapi: CcapiTransport) {
    if (!_canonCcapiReconnecting.value && _canonCcapiTransport.value === ccapi) return
    val priorState = _status.value?.state
    try {
        while (true) {
            coroutineContext.ensureActive()
            if (_canonCcapiTransport.value !== ccapi) {
                throw IllegalStateException("Canon transport dropped during pause")
            }
            if (!_canonCcapiReconnecting.value) return
            _status.value = _status.value?.copy(state = DeviceState.WAITING)
            delay(500)
        }
    } finally {
        if (priorState != null && _status.value?.state == DeviceState.WAITING) {
            _status.value = _status.value?.copy(state = priorState)
        }
    }
}
