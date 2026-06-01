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
