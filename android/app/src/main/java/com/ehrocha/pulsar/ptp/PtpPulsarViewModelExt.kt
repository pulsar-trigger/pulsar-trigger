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
 * USB PTP leaf helpers split out of [PulsarViewModel]. Cross-transport
 * concerns (connect/disconnect, mutual exclusion, the USB attach-broadcast
 * collector that owns soft-pause-and-resume) still live in the main viewmodel.
 *
 * Pauses the run-loop while [PulsarViewModel._ptpReconnecting] is armed,
 * resuming when the OS reports a matching (vid, pid) ATTACHED and the
 * viewmodel calls [PtpTransport.reopen] in place. Throws if the transport
 * gets replaced entirely so the caller bails.
 */
internal suspend fun PulsarViewModel.awaitPtpUsbReady(ptp: PtpTransport) {
    if (!_ptpReconnecting.value && _ptpTransport.value === ptp) return
    val priorState = _status.value?.state
    try {
        while (true) {
            coroutineContext.ensureActive()
            if (_ptpTransport.value !== ptp) {
                throw IllegalStateException("USB PTP transport dropped during pause")
            }
            if (!_ptpReconnecting.value) return
            _status.value = _status.value?.copy(state = DeviceState.WAITING)
            delay(500)
        }
    } finally {
        if (priorState != null && _status.value?.state == DeviceState.WAITING) {
            _status.value = _status.value?.copy(state = priorState)
        }
    }
}

/** Periodic USB PTP battery poll — 30 s cadence. Body-side push events
 *  don't exist on PTP so we poll. Exits cleanly if the body runtime-
 *  downgrades `supportsBatteryReadout` (EOS R / RP: prop advertised but
 *  rejected with `rc=0x2005`). */
internal fun PulsarViewModel.startPtpBatteryPolling(transport: PtpTransport): Job {
    ptpPollJob?.cancel()
    val job = viewModelScope.launch {
        // Seed immediately so the user doesn't see 0% for 30 s.
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
    ptpPollJob = job
    return job
}
