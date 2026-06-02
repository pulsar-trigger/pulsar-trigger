/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Copyright (C) 2026 Pulsar Trigger contributors
 */

package com.ehrocha.pulsar.ptp

import androidx.lifecycle.viewModelScope
import com.ehrocha.pulsar.ble.DeviceState
import com.ehrocha.pulsar.viewmodel.PulsarViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.coroutines.coroutineContext

/**
 * PTP/IP leaf helpers split out of [PulsarViewModel]. Cross-transport
 * concerns (connect/disconnect, mutual exclusion, the camera-watcher that
 * drives reopen-in-place) still live in the main viewmodel.
 *
 * Pauses the run-loop while [PulsarViewModel._ptpIpReconnecting] is armed.
 * `PtpIpTransport.reopen()` keeps the outer reference alive, so when the
 * reconnect succeeds the runner picks up against the same instance.
 */
internal suspend fun PulsarViewModel.awaitPtpIpReady(ptpIp: PtpIpTransport) {
    if (!_ptpIpReconnecting.value && _ptpIpTransport.value === ptpIp) return
    val priorState = _status.value?.state
    try {
        while (true) {
            coroutineContext.ensureActive()
            if (_ptpIpTransport.value !== ptpIp) {
                throw IllegalStateException("PTP/IP transport dropped during pause")
            }
            if (!_ptpIpReconnecting.value) return
            _status.value = _status.value?.copy(state = DeviceState.WAITING)
            delay(500)
        }
    } finally {
        if (priorState != null && _status.value?.state == DeviceState.WAITING) {
            _status.value = _status.value?.copy(state = priorState)
        }
    }
}

/** Watches the PTP/IP transport's `connected` flow. When the wire drops
 *  while we still hold the transport (and we have a remembered camera),
 *  arms `_ptpIpReconnecting` and kicks off [attemptPtpIpReconnect] in
 *  place. The transport reference is reused across reopen so runners
 *  that captured it keep working. Wired from PulsarViewModel.connectPtpIp. */
internal fun PulsarViewModel.watchPtpIpWire(transport: PtpIpTransport) {
    viewModelScope.launch {
        transport.connected.collect { isUp ->
            if (isUp) return@collect
            if (_ptpIpTransport.value !== transport) return@collect  // stale
            val cam = lastPtpIpCamera ?: return@collect
            if (_ptpIpReconnecting.value) return@collect
            _ptpIpReconnecting.value = true
            ptpIpReconnectJob?.cancel()
            ptpIpReconnectJob = launch { attemptPtpIpReconnect(transport, cam) }
        }
    }
}

/** Re-runs the handshake against the same transport instance on a
 *  3 / 5 / 10 / 30 s backoff. Calls [PtpIpTransport.reopen] so the outer
 *  reference stays valid — runners that captured it via
 *  `runCanonBulb(transport=…)` keep working once `_connected` flips back
 *  to true. Caps at 4 attempts before giving up and clearing the camera. */
internal suspend fun PulsarViewModel.attemptPtpIpReconnect(
    transport: PtpIpTransport,
    @Suppress("UNUSED_PARAMETER") camera: com.ehrocha.pulsar.ptp.PtpIpCamera,
) {
    try {
        val backoffs = longArrayOf(3_000, 5_000, 10_000, 30_000)
        for ((i, delayMs) in backoffs.withIndex()) {
            delay(delayMs)
            coroutineContext.ensureActive()
            if (_ptpIpTransport.value !== transport) return  // user disconnected mid-retry
            android.util.Log.i("PtpIpReconnect", "attempt ${i + 1}/${backoffs.size}")
            val ok = runCatching { transport.reopen() }.getOrDefault(false)
            if (ok) {
                _ptpIpReconnecting.value = false
                // Battery poll keeps running across reconnect — it just sees
                // null reads while _connected is false, then resumes once back.
                return
            }
        }
        // Gave up — fall through to full disconnect.
        _ptpIpError.value = "reconnect_failed"
        lastPtpIpCamera = null
        _ptpIpReconnecting.value = false
        runCatching { transport.release() }
        _ptpIpTransport.value = null
        if (!bleController.connected.value && !_simulatorActive.value &&
            _canonCcapiTransport.value == null && _ptpTransport.value == null &&
            _canonBleTransport.value == null
        ) {
            _connected.value = false
            _status.value = null
        }
    } catch (_: kotlinx.coroutines.CancellationException) {
        _ptpIpReconnecting.value = false
        throw kotlinx.coroutines.CancellationException("reconnect cancelled")
    }
}

/** Periodic PTP/IP battery poll. Mirrors [startPtpBatteryPolling] — same
 *  30 s cadence, same runtime-downgrade exit. Cancelled in
 *  `disconnectPtpIp`. The body's poll-job ref lives on the viewmodel so
 *  the disconnect path can cancel it cleanly. */
internal fun PulsarViewModel.startPtpIpBatteryPolling(transport: PtpIpTransport): Job {
    ptpIpPollJob?.cancel()
    val job = viewModelScope.launch {
        transport.readBatteryPercent()?.let { pct ->
            _status.value = _status.value?.copy(batteryPct = pct)
        }
        while (isActive) {
            delay(30_000)
            if (!transport.supportsBatteryReadout) break
            val pct = transport.readBatteryPercent() ?: continue
            _status.value = _status.value?.copy(batteryPct = pct)
        }
    }
    ptpIpPollJob = job
    return job
}
