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

/** How long to keep re-probing `GET /ccapi` after a Wi-Fi blip before
 *  giving up and tearing down the CCAPI transport entirely. Covers the
 *  common case of the camera dropping off Wi-Fi for a router roam,
 *  toggling the radio briefly, etc. */
private const val CANON_RECONNECT_TIMEOUT_MS = 120_000L
private const val CANON_RECONNECT_BACKOFF_MS = 3_000L

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
/** Re-probe `GET /ccapi` on a backoff until the camera responds or the
 *  timeout fires. Keeps the existing transport / UI state intact so the
 *  user doesn't get bounced back to the scan screen for a transient blip.
 *  Returns true on recovery, false on giving up. Called from the CCAPI
 *  poll loop's exception handler in the viewmodel. */
internal suspend fun PulsarViewModel.attemptCanonCcapiReconnect(
    transport: CcapiTransport,
): Boolean {
    _canonCcapiReconnecting.value = true
    val deadline = System.currentTimeMillis() + CANON_RECONNECT_TIMEOUT_MS
    try {
        while (System.currentTimeMillis() < deadline &&
            _canonCcapiTransport.value === transport
        ) {
            coroutineContext.ensureActive()
            val r = transport.reconnect()
            if (r is com.ehrocha.pulsar.transport.ccapi.CcapiClient.Result.Ok) {
                android.util.Log.i("CcapiReconnect", "Canon reconnect succeeded")
                return true
            }
            delay(CANON_RECONNECT_BACKOFF_MS)
        }
        return false
    } finally {
        _canonCcapiReconnecting.value = false
    }
}

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
