/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Copyright (C) 2026 Pulsar Trigger contributors
 */

package com.ehrocha.pulsar.transport

import com.ehrocha.pulsar.canonble.CanonBleLog
import com.ehrocha.pulsar.canonble.CanonBleTransport
import com.ehrocha.pulsar.ptp.PtpClient
import com.ehrocha.pulsar.ptp.PtpIpTransport
import com.ehrocha.pulsar.ptp.PtpTransport
import com.ehrocha.pulsar.transport.ccapi.CcapiTransport
import kotlinx.coroutines.delay

private const val TAG = "Compat"

/** Wire-level capability probe. **Read-only** — no shutter releases, no
 *  property writes, no body-state changes. The only side effects are a
 *  brief Live View stream toggle (which the body recovers from cleanly)
 *  and the standard `GetDevicePropValue` reads.
 *
 *  Lands in the shared [CanonBleLog] ring so the Diagnostics share carries
 *  the report. Use case: testers on EOS RP / R5 / R6 / R7 / R8 / R10 run
 *  this once and email the diag file so we can populate a body-by-body
 *  compatibility matrix without ever owning the body.
 *
 *  Caller is the viewmodel; pass the active transport (must implement
 *  [CameraTransport]). */
suspend fun runCompatibilityReport(transport: CameraTransport) {
    CanonBleLog.i(TAG, "── Compatibility report — start ──")
    CanonBleLog.i(TAG, "transport.kind=${transport.kind}  label=${transport.label.value}")
    CanonBleLog.i(TAG, "caps: bulb=${transport.supportsBulb} settings=${transport.supportsSettings} " +
        "liveView=${transport.supportsLiveView} lensInfo=${transport.supportsLensInfo} " +
        "battery=${transport.supportsBatteryReadout} afToggle=${transport.supportsAfToggle}")

    when (transport) {
        is PtpTransport -> reportPtpUsb(transport)
        is PtpIpTransport -> reportPtpIp(transport)
        is CcapiTransport -> reportCcapi(transport)
        is CanonBleTransport -> reportCanonBle(transport)
    }

    // Live view round-trip — safe on any body that advertises it. If
    // `supportsLiveView=false` the transport's defaults make this a no-op.
    if (transport.supportsLiveView) {
        CanonBleLog.i(TAG, "liveView probe: startLiveView…")
        val started = transport.startLiveView()
        CanonBleLog.i(TAG, "  startLiveView returned $started " +
            "(lastError=${transport.lastLiveViewError ?: "null"})")
        if (started) {
            delay(250)
            val frame = transport.getLiveViewFrame()
            CanonBleLog.i(TAG, "  getLiveViewFrame: ${frame?.size ?: "null"} bytes")
            transport.stopLiveView()
            CanonBleLog.i(TAG, "  stopLiveView done")
        }
    }

    if (transport.supportsLensInfo) {
        val lens = transport.getLensInfo()
        CanonBleLog.i(TAG, "lens probe: mounted=${lens?.mounted} name='${lens?.name}' " +
            "focalMm=${lens?.focalMm} zoomRange=${lens?.zoomRangeMm}")
    }

    CanonBleLog.i(TAG, "── Compatibility report — end ──")
}

// ── PTP USB ────────────────────────────────────────────────────────────────

private suspend fun reportPtpUsb(t: PtpTransport) {
    val info = t.deviceInfo
    CanonBleLog.i(TAG, "device: '${info.manufacturer}' / '${info.model}' " +
        "fw='${info.deviceVersion}' sn='${info.serialNumber}'")
    CanonBleLog.i(TAG, "vendorExtensionId=${info.vendorExtensionId} " +
        "(11=Canon EOS, 6=MTP; both treated as Canon)")
    CanonBleLog.i(TAG, "operations: ${info.supportedOperations.size}, " +
        "properties: ${info.supportedDeviceProperties.size}")
    reportCanonOpsAndProps(info.supportedOperations, info.supportedDeviceProperties)
    runCatching { t.readBatteryPercent() }.getOrNull()?.let {
        CanonBleLog.i(TAG, "battery probe: $it%")
    }
}

// ── PTP/IP ─────────────────────────────────────────────────────────────────

private fun reportPtpIp(t: PtpIpTransport) {
    val info = t.deviceInfo
    CanonBleLog.i(TAG, "device: '${info.manufacturer}' / '${info.model}' " +
        "fw='${info.deviceVersion}' sn='${info.serialNumber}'")
    CanonBleLog.i(TAG, "vendorExtensionId=${info.vendorExtensionId} " +
        "(EOS R reports 6 over Wi-Fi vs 11 over USB; both ok)")
    CanonBleLog.i(TAG, "operations: ${info.supportedOperations.size}, " +
        "properties: ${info.supportedDeviceProperties.size}")
    reportCanonOpsAndProps(info.supportedOperations, info.supportedDeviceProperties)
}

private suspend fun PtpIpTransport.readBatteryPercentSafe(): Int? =
    runCatching { readBatteryPercent() }.getOrNull()

// ── CCAPI ──────────────────────────────────────────────────────────────────

private fun reportCcapi(t: CcapiTransport) {
    // CcapiTransport exposes endpoint capability through its inner client;
    // log what we already cache + key paths. Per docs/ccapi.md the endpoint
    // matrix is the right multi-body signal.
    CanonBleLog.i(TAG, "ccapi: label='${t.label.value}'  connected=${t.connected.value}")
    // The CcapiTransport class doesn't expose the full endpoint matrix
    // publicly today — leaving a hook for that here. The capability flags
    // logged in the header give us most of the multi-body signal anyway.
}

// ── Canon BLE direct ───────────────────────────────────────────────────────

private fun reportCanonBle(t: CanonBleTransport) {
    CanonBleLog.i(TAG, "canon-ble: label='${t.label.value}' " +
        "connected=${t.connected.value} bulb=${t.supportsBulb} afToggle=${t.supportsAfToggle}")
    // Protocol mode (BR-E1 vs smartphone) is internal; the AF-toggle flag
    // tells us which path is active (BR-E1 honors the toggle, smartphone
    // mode reports supportsAfToggle=false).
}

// ── Canon op + prop matrix (shared PTP / PTP-IP) ───────────────────────────

private fun reportCanonOpsAndProps(ops: Collection<Int>, props: Collection<Int>) {
    val opChecks = linkedMapOf(
        0x1014 to "InitiateCapture",
        0x9128 to "Canon RemoteReleaseOn",
        0x9129 to "Canon RemoteReleaseOff",
        0x9125 to "Canon RemoteRelease (alt)",
        0x9153 to "Canon GetViewFinderData",
        0x9155 to "Canon DriveLens",
        0x1015 to "GetDevicePropValue",
        0x1016 to "SetDevicePropValue",
    )
    val propChecks = linkedMapOf(
        0x5001 to "BatteryLevel (std PTP)",
        0xD157 to "Canon LensName",
        0xD1B0 to "Canon EvfOutput",
        0xD102 to "Canon ShutterSpeed",
        0xD101 to "Canon Aperture",
        0xD103 to "Canon ISO",
    )
    CanonBleLog.i(TAG, "ops present:")
    for ((code, name) in opChecks) {
        CanonBleLog.i(TAG, "  0x${"%04X".format(code)}  ${if (code in ops) "✓" else "·"}  $name")
    }
    CanonBleLog.i(TAG, "props present:")
    for ((code, name) in propChecks) {
        CanonBleLog.i(TAG, "  0x${"%04X".format(code)}  ${if (code in props) "✓" else "·"}  $name")
    }
    // Reference the unused constants so the import is "live" if Canon ops
    // get extended later (silences future code-cleanup tools).
    @Suppress("UNUSED_VARIABLE")
    val _ref = PtpClient.OP_INITIATE_CAPTURE
}
