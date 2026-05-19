/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Copyright (C) 2026 Pulsar Trigger contributors
 */

package com.ehrocha.pulsar.ui.screens

import android.content.Context
import android.hardware.usb.UsbManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.ehrocha.pulsar.R
import com.ehrocha.pulsar.ptp.PtpProbe
import kotlinx.coroutines.launch

/**
 * Temporary feasibility probe for the USB PTP transport. Plug an EOS body in
 * via USB-C, tap "Run probe", grant USB permission. We send a single PTP
 * `GetDeviceInfo` and report what we got. If it works the protocol path is
 * clear and we'd build the real transport on top of `PtpProbe`'s primitives.
 *
 * This screen is intentionally minimal — no settings, no transport state,
 * no history. Delete once we've decided to build (replaced by real PTP UI)
 * or abandon (delete probe + file + manifest feature).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UsbProbeScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf<ProbeStatus>(ProbeStatus.Idle) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.usb_probe_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    }
                },
            )
        },
    ) { pad ->
        Column(
            modifier = Modifier
                .padding(pad)
                .padding(16.dp)
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(R.string.usb_probe_blurb),
                style = MaterialTheme.typography.bodyMedium,
            )

            Button(
                onClick = {
                    status = ProbeStatus.Running
                    scope.launch {
                        status = runProbe(context)
                    }
                },
                enabled = status !is ProbeStatus.Running,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.usb_probe_run))
            }

            when (val s = status) {
                ProbeStatus.Idle -> {}
                ProbeStatus.Running -> Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Spacer(Modifier.height(8.dp))
                    CircularProgressIndicator(modifier = Modifier.size(36.dp))
                    Spacer(Modifier.height(8.dp))
                    Text(stringResource(R.string.usb_probe_running))
                }
                is ProbeStatus.Done -> Text(
                    text = s.report,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                )
            }
        }
    }
}

private sealed class ProbeStatus {
    object Idle : ProbeStatus()
    object Running : ProbeStatus()
    data class Done(val report: String) : ProbeStatus()
}

private suspend fun runProbe(ctx: Context): ProbeStatus.Done {
    val usb = ctx.getSystemService(Context.USB_SERVICE) as UsbManager
    val sb = StringBuilder()
    sb.appendLine("[1/3] enumerating USB devices…")
    val device = PtpProbe.findCameraDevice(usb)
    if (device == null) {
        if (usb.deviceList.isEmpty()) {
            sb.appendLine("  FAIL: no USB devices connected")
            sb.appendLine("  Tip: connect the camera with a USB-C ↔ USB-C cable,")
            sb.appendLine("       power the camera ON, then re-run.")
        } else {
            sb.appendLine("  FAIL: ${usb.deviceList.size} USB device(s) attached but none expose a PTP interface")
            for ((name, d) in usb.deviceList) {
                sb.appendLine("    $name vid=${"0x%04X".format(d.vendorId)} " +
                              "pid=${"0x%04X".format(d.productId)}")
            }
        }
        return ProbeStatus.Done(sb.toString())
    }
    sb.appendLine("  found ${device.deviceName}")
    sb.appendLine("    vendorId=${"0x%04X".format(device.vendorId)}")
    sb.appendLine("    productId=${"0x%04X".format(device.productId)}")
    sb.appendLine("    productName=${device.productName ?: "?"}")

    sb.appendLine("[2/3] requesting USB permission…")
    val granted = PtpProbe.requestPermission(ctx, usb, device)
    if (!granted) {
        sb.appendLine("  FAIL: permission denied")
        return ProbeStatus.Done(sb.toString())
    }
    sb.appendLine("  OK")

    sb.appendLine("[3/3] sending PTP GetDeviceInfo…")
    when (val r = PtpProbe.probe(ctx, device)) {
        is PtpProbe.Result.Ok -> {
            val rpt = r.report
            sb.appendLine("  OK")
            sb.appendLine()
            sb.appendLine("Camera identified via PTP:")
            sb.appendLine("  vendor          ${rpt.vendorName}")
            sb.appendLine("  manufacturer    ${rpt.manufacturer}")
            sb.appendLine("  model           ${rpt.model}")
            sb.appendLine("  version         ${rpt.deviceVersion}")
            sb.appendLine("  serial          ${rpt.serialNumber}")
            sb.appendLine("  vendor ext id   ${rpt.vendorExtensionId} " +
                          "(${vendorExtName(rpt.vendorExtensionId)})")
            sb.appendLine("  supported ops   ${rpt.supportedOperationsCount}")
            sb.appendLine("    capture        ${if (rpt.supportsCapture) "yes" else "no"}")
            sb.appendLine("    Canon trigger  ${if (rpt.supportsTriggerCapture) "yes" else "no"}")
            sb.appendLine("    Canon bulb     ${if (rpt.supportsBulb) "yes" else "no"}")
            sb.appendLine()
            sb.appendLine("Verdict: PTP path is open. We can build the transport.")
        }
        PtpProbe.Result.NoUsbDevices -> sb.appendLine("  FAIL: no USB devices")
        PtpProbe.Result.NoCameraFound -> sb.appendLine("  FAIL: no camera found")
        PtpProbe.Result.NoPtpInterface ->
            sb.appendLine("  FAIL: device opened but exposes no PTP interface")
        PtpProbe.Result.PermissionDenied ->
            sb.appendLine("  FAIL: USB permission was revoked mid-flight")
        is PtpProbe.Result.OpenFailed ->
            sb.appendLine("  FAIL: ${r.reason}")
        is PtpProbe.Result.IoError ->
            sb.appendLine("  FAIL at ${r.stage}: ${r.cause.javaClass.simpleName}: ${r.cause.message}")
        is PtpProbe.Result.ProtocolError ->
            sb.appendLine("  FAIL at ${r.stage}: ${r.detail}")
    }
    return ProbeStatus.Done(sb.toString())
}

private fun vendorExtName(id: Long): String = when (id) {
    0L -> "none"
    6L -> "Microsoft MTP"
    11L -> "Canon EOS"
    65535L -> "MTP"
    else -> "vendor $id"
}
