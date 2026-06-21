/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Copyright (C) 2026 Pulsar Trigger contributors
 */

package com.ehrocha.pulsar.ui.screens

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ehrocha.pulsar.R
import com.ehrocha.pulsar.model.LastConnection
import com.ehrocha.pulsar.transport.TransportKind
import com.ehrocha.pulsar.ui.components.SignalSweep
import com.ehrocha.pulsar.ui.theme.Display
import com.ehrocha.pulsar.ui.theme.Grotesk
import com.ehrocha.pulsar.ui.theme.Mono
import com.ehrocha.pulsar.ui.theme.PulsarTheme
import com.ehrocha.pulsar.viewmodel.PulsarViewModel
import kotlin.math.abs
import kotlin.math.min

/**
 * Initial post-permission screen — the **Driver-IC board** (SIGNAL).
 *
 * Pulsar is the chip; the six transports are connector pads fanned around it,
 * wired by hand-routed PCB traces over a faint via-dot field. Tap a pad to
 * route the signal to that transport. The whole screen is one instrument face
 * (no stacked tile cards), which also keeps it on a single non-scrolling page.
 *
 * Everything draws through role colors (`PulsarTheme.colors` / `colorScheme`)
 * so Phosphor-Red night mode resolves the traces to its red luminance ramp,
 * and the live gradient only ever lights the trace that's actively connecting
 * (the one law).
 */
@Composable
fun ScanLandingScreen(
    vm: PulsarViewModel,
    onTransportSelected: (TransportKind) -> Unit,
    onSimulatorSelected: () -> Unit,
    onConnected: () -> Unit,
    onManageDevicesSelected: () -> Unit,
) {
    val connected by vm.connected.collectAsState()
    val lastConnection by vm.lastConnection.collectAsState()
    val canonBleConnecting by vm.canonBleConnecting.collectAsState()
    val ptpConnecting by vm.ptpConnecting.collectAsState()
    val ccapiConnecting by vm.canonCcapiConnecting.collectAsState()
    val ptpIpConnecting by vm.ptpIpConnecting.collectAsState()
    val reconnecting = when (lastConnection?.kind) {
        TransportKind.CANON_BLE -> canonBleConnecting
        TransportKind.PTP_USB -> ptpConnecting
        TransportKind.CCAPI -> ccapiConnecting
        TransportKind.PTP_IP -> ptpIpConnecting
        else -> false
    }
    if (connected) {
        LaunchedEffect(Unit) { onConnected() }
    }
    val ctx = androidx.compose.ui.platform.LocalContext.current

    // Pads fan around the chip: index 0/2/4 = left column (top/mid/bottom),
    // 1/3/5 = right. Rows are grouped by bus AND by label length: the long
    // "Canon Wi-Fi …" names take the WIDE top row, the short BLE names flank
    // the chip on the narrow mid row, wired transports sit on the wide bottom.
    val pads = remember {
        listOf(
            // top row (wide)
            PadSpec(TransportKind.CCAPI, Icons.Default.Wifi,
                R.string.transport_tile_ccapi_title, listOf("lv", "bat", "bulb")),
            PadSpec(TransportKind.PTP_IP, Icons.Default.Wifi,
                R.string.transport_tile_ptp_ip_title, listOf("lv", "bulb")),
            // mid row (narrow, flanks the chip)
            PadSpec(TransportKind.BLE_ESP, Icons.Default.Bluetooth,
                R.string.transport_tile_pulsar_ble_title, listOf("bulb")),
            PadSpec(TransportKind.CANON_BLE, Icons.Default.Bluetooth,
                R.string.transport_tile_canon_ble_title, listOf("bulb")),
            // bottom row (wide)
            PadSpec(TransportKind.PTP_USB, Icons.Default.Usb,
                R.string.transport_tile_ptp_title, listOf("lv", "bulb")),
            PadSpec(null, Icons.Default.Science,
                R.string.transport_tile_simulator_title, listOf("demo")),
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 16.dp),
    ) {
        Spacer(Modifier.height(16.dp))

        // ── The board (fills the page; never scrolls) ────────────────────────
        TransportBoard(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            pads = pads,
            recentKind = lastConnection?.kind,
            reconnectingKind = if (reconnecting) lastConnection?.kind else null,
            versionName = com.ehrocha.pulsar.BuildConfig.VERSION_NAME,
            onSelect = { kind -> if (kind == null) onSimulatorSelected() else onTransportSelected(kind) },
        )

        // ── Recent route ─────────────────────────────────────────────────────
        lastConnection?.let { last ->
            ReconnectRow(
                last = last,
                reconnecting = reconnecting,
                onReconnect = { vm.reconnectLast() },
                onForget = {
                    val device = last.toManagedDevice()
                    if (device != null) vm.forgetDevice(device) else vm.forgetLastConnection()
                },
            )
            Spacer(Modifier.height(8.dp))
        }

        // ── Service pads (edge connector) ────────────────────────────────────
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ToolPad(Icons.Default.Bluetooth, stringResource(R.string.section_devices), onManageDevicesSelected)
            ToolPad(Icons.Default.Description, stringResource(R.string.scan_tool_diagnostics)) {
                shareDiagnostics(ctx, vm.canonDiagnosticsText())
            }
        }

        Spacer(Modifier.height(16.dp))
    }
}

private data class PadSpec(
    /** null == Simulator (it has no [TransportKind]). */
    val kind: TransportKind?,
    val icon: ImageVector,
    val titleRes: Int,
    val caps: List<String>,
)

/** Decorative PCB bus routed behind the board — fixed fractional polylines
 *  that pack the field with parallel runs, fan-outs and edge buses so the
 *  carbon reads like a populated board. Purely cosmetic; the six functional
 *  traces are drawn separately and brighter. */
private val DECOR_TRACES = listOf(
    // top ribbon bus (parallel runs)
    listOf(0.05f to 0.045f, 0.95f to 0.045f),
    listOf(0.07f to 0.065f, 0.93f to 0.065f),
    listOf(0.05f to 0.085f, 0.95f to 0.085f),
    listOf(0.09f to 0.105f, 0.42f to 0.105f),
    listOf(0.58f to 0.105f, 0.91f to 0.105f),
    // top fan-outs dropping toward the pads
    listOf(0.16f to 0.105f, 0.16f to 0.15f, 0.22f to 0.21f),
    listOf(0.27f to 0.085f, 0.27f to 0.15f),
    listOf(0.73f to 0.085f, 0.73f to 0.15f),
    listOf(0.84f to 0.105f, 0.84f to 0.15f, 0.78f to 0.21f),
    // left edge vertical bundle + branch stubs
    listOf(0.040f to 0.12f, 0.040f to 0.88f),
    listOf(0.065f to 0.16f, 0.065f to 0.45f),
    listOf(0.065f to 0.55f, 0.065f to 0.84f),
    listOf(0.040f to 0.28f, 0.12f to 0.28f),
    listOf(0.040f to 0.50f, 0.10f to 0.50f),
    listOf(0.040f to 0.72f, 0.12f to 0.72f),
    // right edge vertical bundle + branch stubs
    listOf(0.960f to 0.12f, 0.960f to 0.88f),
    listOf(0.935f to 0.16f, 0.935f to 0.45f),
    listOf(0.935f to 0.55f, 0.935f to 0.84f),
    listOf(0.960f to 0.28f, 0.88f to 0.28f),
    listOf(0.960f to 0.50f, 0.90f to 0.50f),
    listOf(0.960f to 0.72f, 0.88f to 0.72f),
    // bottom ribbon bus
    listOf(0.05f to 0.955f, 0.95f to 0.955f),
    listOf(0.07f to 0.935f, 0.93f to 0.935f),
    listOf(0.05f to 0.915f, 0.95f to 0.915f),
    listOf(0.09f to 0.895f, 0.42f to 0.895f),
    listOf(0.58f to 0.895f, 0.91f to 0.895f),
    // bottom fan-outs
    listOf(0.18f to 0.895f, 0.18f to 0.85f, 0.24f to 0.79f),
    listOf(0.30f to 0.915f, 0.30f to 0.85f),
    listOf(0.70f to 0.915f, 0.70f to 0.85f),
    listOf(0.82f to 0.895f, 0.82f to 0.85f, 0.76f to 0.79f),
)

private enum class Comp { RES, LED, NPN, SMD, IC }
private class DecorComp(val type: Comp, val x: Float, val y: Float)

/** Scattered SMD silkscreen — resistors, LEDs, transistors, chip caps and a
 *  couple of small ICs — placed in the field margins (hidden where a pad sits
 *  on top). */
private val DECOR_COMPONENTS = listOf(
    DecorComp(Comp.IC, 0.50f, 0.055f),
    DecorComp(Comp.RES, 0.31f, 0.05f),
    DecorComp(Comp.LED, 0.63f, 0.05f),
    DecorComp(Comp.NPN, 0.72f, 0.055f),
    DecorComp(Comp.SMD, 0.40f, 0.105f),
    DecorComp(Comp.SMD, 0.60f, 0.105f),
    DecorComp(Comp.RES, 0.10f, 0.34f),
    DecorComp(Comp.NPN, 0.90f, 0.34f),
    DecorComp(Comp.LED, 0.10f, 0.62f),
    DecorComp(Comp.SMD, 0.90f, 0.62f),
    DecorComp(Comp.RES, 0.35f, 0.93f),
    DecorComp(Comp.NPN, 0.50f, 0.93f),
    DecorComp(Comp.IC, 0.64f, 0.93f),
)

@Composable
private fun TransportBoard(
    modifier: Modifier,
    pads: List<PadSpec>,
    recentKind: TransportKind?,
    reconnectingKind: TransportKind?,
    versionName: String,
    onSelect: (TransportKind?) -> Unit,
) {
    val colors = PulsarTheme.colors
    val outline = MaterialTheme.colorScheme.outline
    val breathe by rememberInfiniteTransition(label = "chip").animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1300, easing = LinearEasing), RepeatMode.Reverse),
        label = "core",
    )
    // The pulsar beacons: a pulse leaves the chip and travels each trace out to
    // its transport, in sync — the source emitting signal down every bus.
    val pulse by rememberInfiniteTransition(label = "bus").animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1600, easing = LinearEasing), RepeatMode.Restart),
        label = "flow",
    )

    BoxWithConstraints(modifier) {
        val w = maxWidth
        val h = maxHeight

        // A big, present IC; pads sized to the room each row has. The mid row
        // flanks the chip so it's the tight one (short BLE labels live there);
        // the wide top/bottom rows carry the long "Canon Wi-Fi …" names.
        val chip = (w * 0.30f).coerceIn(118.dp, 142.dp)
        val legW = 9.dp
        val padWMid = (w / 2 - chip / 2 - legW - 6.dp).coerceIn(112.dp, 150.dp)
        val padWOuter = (w * 0.46f).coerceIn(150.dp, 190.dp)
        val padH = 70.dp

        // Rows a fixed distance off centre (not stretched to the page height),
        // so the board stays a compact instrument with room for the PCB field
        // above and below it.
        val rowGap = (h * 0.20f).coerceIn(150.dp, 200.dp)
        val rowsY = listOf(h / 2 - rowGap, h / 2, h / 2 + rowGap)
        fun padWidth(row: Int): Dp = if (row == 1) padWMid else padWOuter

        val traceFaint = colors.liveStart.copy(alpha = 0.22f)
        val viaFaint = colors.liveStart.copy(alpha = 0.32f)
        val decorTrace = colors.liveStart.copy(alpha = 0.10f)
        val decorVia = colors.liveEnd.copy(alpha = 0.16f)
        val compLine = colors.liveStart.copy(alpha = 0.17f)
        val compFill = colors.liveEnd.copy(alpha = 0.12f)
        val fieldDot = outline.copy(alpha = 0.045f)
        val legColor = colors.liveStart.copy(alpha = 0.5f)
        val activeBrush = Brush.linearGradient(listOf(colors.liveStart, colors.liveEnd))

        // ── PCB layer: via field, decorative bus, IC legs + the six traces ───
        Canvas(Modifier.matchParentSize()) {
            drawViaField(fieldDot)
            drawDecor(decorTrace, decorVia)
            drawComponents(compLine, compFill)

            val cx = size.width / 2f
            val cy = size.height / 2f
            val chHalf = chip.toPx() / 2f
            val legWpx = legW.toPx()
            val legHpx = 6.dp.toPx()

            // IC legs down both sides — one dock per row.
            for (row in 0..2) {
                val ly = cy + (row - 1) * (chHalf * 0.6f)
                drawRect(legColor, topLeft = Offset(cx - chHalf - legWpx, ly - legHpx / 2), size = Size(legWpx, legHpx))
                drawRect(legColor, topLeft = Offset(cx + chHalf, ly - legHpx / 2), size = Size(legWpx, legHpx))
            }

            pads.forEachIndexed { i, spec ->
                val left = i % 2 == 0
                val row = i / 2
                val padEdge = if (left) padWidth(row).toPx() else size.width - padWidth(row).toPx()
                val rowY = rowsY[row].toPx()
                // A docking leg on each pad's inner edge — each pad reads as a
                // little IC component, wired back to the driver.
                drawRect(
                    legColor,
                    topLeft = Offset(if (left) padEdge else padEdge - legWpx, rowY - legHpx / 2),
                    size = Size(legWpx, legHpx),
                )
                val anchor = Offset(if (left) padEdge + legWpx else padEdge - legWpx, rowY)
                val pin = Offset(
                    x = if (left) cx - chHalf - legWpx else cx + chHalf + legWpx,
                    y = cy + (row - 1) * (chHalf * 0.6f),
                )
                val path = tracePath(pin, anchor)
                val active = spec.kind != null && spec.kind == reconnectingKind
                if (active) {
                    drawPath(path, activeBrush, style = Stroke(width = 2.6.dp.toPx(), cap = StrokeCap.Round))
                } else {
                    drawPath(path, traceFaint, style = Stroke(width = 1.8.dp.toPx(), cap = StrokeCap.Round))
                    drawCircle(viaFaint, radius = 2.6.dp.toPx(), center = anchor)
                }

                // The beacon: a head + short fading tail travelling chip → pad.
                val pm = PathMeasure().apply { setPath(path, false) }
                val len = pm.length
                if (len > 0f) {
                    listOf(0f, 0.06f, 0.12f).forEachIndexed { idx, lag ->
                        val pp = pulse - lag
                        if (pp in 0f..1f) {
                            val edgeFade = when {
                                pp < 0.08f -> pp / 0.08f
                                pp > 0.92f -> (1f - pp) / 0.08f
                                else -> 1f
                            }
                            val a = (edgeFade * (1f - idx * 0.3f)).coerceIn(0f, 1f)
                            val r = (3.4f - idx * 1.0f).dp.toPx()
                            drawCircle(colors.liveEnd.copy(alpha = a), radius = r, center = pm.getPosition(pp * len))
                        }
                    }
                }
            }
        }

        // ── The chip (IC package + pulsing mark) ─────────────────────────────
        ChipCore(chip, breathe)

        // ── Wordmark under the chip ──────────────────────────────────────────
        Column(
            modifier = Modifier.align(Alignment.Center).offset(y = chip / 2 + 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                stringResource(R.string.app_name).uppercase(),
                fontFamily = Display,
                fontWeight = FontWeight.Black,
                letterSpacing = 4.sp,
                fontSize = 18.sp,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                "v$versionName",
                fontFamily = Mono,
                fontSize = 9.sp,
                letterSpacing = 1.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
            )
        }

        // ── The six pads ─────────────────────────────────────────────────────
        pads.forEachIndexed { i, spec ->
            val left = i % 2 == 0
            val row = i / 2
            val pw = padWidth(row)
            TransportPad(
                spec = spec,
                recent = spec.kind != null && spec.kind == recentKind,
                connecting = spec.kind != null && spec.kind == reconnectingKind,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .offset(
                        x = if (left) 0.dp else w - pw,
                        y = rowsY[row] - padH / 2,
                    )
                    .width(pw)
                    .height(padH),
                onClick = { onSelect(spec.kind) },
            )
        }
    }
}

/** Pulsar as an IC package: a carbon body with a pin-1 dot and part-number
 *  silkscreen, the pulsing brand mark printed on its face. The side legs are
 *  drawn on the board canvas where the traces dock. */
@Composable
private fun BoxScope.ChipCore(size: Dp, breathe: Float) {
    Box(
        modifier = Modifier
            .align(Alignment.Center)
            .size(size)
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.55f), RoundedCornerShape(14.dp)),
        contentAlignment = Alignment.Center,
    ) {
        // pin-1 indicator
        Box(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(10.dp)
                .size(5.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)),
        )
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(contentAlignment = Alignment.Center) {
                // breathing core glow behind the mark — the pulse
                Box(
                    modifier = Modifier
                        .size(size * 0.5f)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f + 0.16f * breathe)),
                )
                Image(
                    painter = painterResource(R.drawable.ic_pulsar_mark),
                    contentDescription = null,
                    modifier = Modifier.size(size * 0.5f),
                )
            }
            Spacer(Modifier.height(2.dp))
            Text(
                "PSR-5T",
                fontFamily = Mono,
                fontSize = 9.sp,
                letterSpacing = 2.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f),
            )
        }
    }
}

/** One transport connector pad: icon · name · capability chips · status LED. */
@Composable
private fun TransportPad(
    spec: PadSpec,
    recent: Boolean,
    connecting: Boolean,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    val colors = PulsarTheme.colors
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 2.dp,
        border = if (recent) BorderStroke(1.dp, colors.liveStart.copy(alpha = 0.55f)) else null,
        modifier = modifier,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                spec.icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(7.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    stringResource(spec.titleRes),
                    fontFamily = Grotesk,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 15.sp,
                    lineHeight = 16.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    spec.caps.joinToString(" · "),
                    fontFamily = Mono,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(6.dp))
            if (connecting) {
                SignalSweep(modifier = Modifier.size(20.dp, 10.dp))
            } else {
                Box(
                    modifier = Modifier
                        .size(7.dp)
                        .clip(CircleShape)
                        .background(
                            if (recent) colors.liveStart
                            else MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
                        ),
                )
            }
        }
    }
}

/** Bottom edge-connector chip for the pre-connect utilities. */
@Composable
private fun RowScope.ToolPad(icon: ImageVector, label: String, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp,
        modifier = Modifier.weight(1f).height(44.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                label,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** A faint via-dot grid behind everything — the bare board. */
private fun DrawScope.drawViaField(color: Color) {
    val step = 40.dp.toPx()
    val r = 1.1.dp.toPx()
    var y = step / 2f
    while (y < size.height) {
        var x = step / 2f
        while (x < size.width) {
            drawCircle(color, radius = r, center = Offset(x, y))
            x += step
        }
        y += step
    }
}

/** Draw the [DECOR_TRACES] polylines + a via at every vertex. */
private fun DrawScope.drawDecor(trace: Color, via: Color) {
    val sw = 1.4.dp.toPx()
    val viaR = 2.0.dp.toPx()
    for (poly in DECOR_TRACES) {
        for (k in 0 until poly.size - 1) {
            val a = Offset(poly[k].first * size.width, poly[k].second * size.height)
            val b = Offset(poly[k + 1].first * size.width, poly[k + 1].second * size.height)
            drawLine(trace, a, b, strokeWidth = sw, cap = StrokeCap.Round)
        }
        for (pt in poly) {
            drawCircle(via, radius = viaR, center = Offset(pt.first * size.width, pt.second * size.height))
        }
    }
}

/** Draw the scattered [DECOR_COMPONENTS] as faint SMD silkscreen. */
private fun DrawScope.drawComponents(line: Color, fill: Color) {
    val stroke = Stroke(width = 1.3.dp.toPx(), cap = StrokeCap.Round)
    for (comp in DECOR_COMPONENTS) {
        val c = Offset(comp.x * size.width, comp.y * size.height)
        when (comp.type) {
            Comp.RES -> {
                val bw = 16.dp.toPx(); val bh = 6.dp.toPx(); val lead = 6.dp.toPx()
                drawRoundRect(line, topLeft = Offset(c.x - bw / 2, c.y - bh / 2), size = Size(bw, bh),
                    cornerRadius = CornerRadius(2.dp.toPx()), style = stroke)
                drawLine(line, Offset(c.x - bw / 2 - lead, c.y), Offset(c.x - bw / 2, c.y), strokeWidth = stroke.width)
                drawLine(line, Offset(c.x + bw / 2, c.y), Offset(c.x + bw / 2 + lead, c.y), strokeWidth = stroke.width)
            }
            Comp.LED -> {
                val r = 5.dp.toPx()
                drawCircle(line, radius = r, center = c, style = stroke)
                drawCircle(fill, radius = 1.6.dp.toPx(), center = c)
                drawLine(line, Offset(c.x - r - 5.dp.toPx(), c.y), Offset(c.x - r, c.y), strokeWidth = stroke.width)
                drawLine(line, Offset(c.x + r, c.y), Offset(c.x + r + 5.dp.toPx(), c.y), strokeWidth = stroke.width)
            }
            Comp.NPN -> {
                val s = 13.dp.toPx(); val leg = 5.dp.toPx()
                drawRoundRect(line, topLeft = Offset(c.x - s / 2, c.y - s * 0.4f), size = Size(s, s * 0.8f),
                    cornerRadius = CornerRadius(2.dp.toPx()), style = stroke)
                drawLine(line, Offset(c.x - s * 0.25f, c.y + s * 0.4f), Offset(c.x - s * 0.25f, c.y + s * 0.4f + leg), strokeWidth = stroke.width)
                drawLine(line, Offset(c.x + s * 0.25f, c.y + s * 0.4f), Offset(c.x + s * 0.25f, c.y + s * 0.4f + leg), strokeWidth = stroke.width)
                drawLine(line, Offset(c.x, c.y - s * 0.4f), Offset(c.x, c.y - s * 0.4f - leg), strokeWidth = stroke.width)
            }
            Comp.SMD -> {
                val bw = 12.dp.toPx(); val bh = 7.dp.toPx(); val pad = 3.dp.toPx()
                drawRoundRect(fill, topLeft = Offset(c.x - bw / 2, c.y - bh / 2), size = Size(bw, bh),
                    cornerRadius = CornerRadius(1.5.dp.toPx()))
                drawRect(fill, topLeft = Offset(c.x - bw / 2 - pad, c.y - bh / 2), size = Size(pad, bh))
                drawRect(fill, topLeft = Offset(c.x + bw / 2, c.y - bh / 2), size = Size(pad, bh))
            }
            Comp.IC -> {
                val s = 20.dp.toPx(); val leg = 4.dp.toPx()
                drawRoundRect(fill, topLeft = Offset(c.x - s / 2, c.y - s / 2), size = Size(s, s),
                    cornerRadius = CornerRadius(2.dp.toPx()))
                drawRoundRect(line, topLeft = Offset(c.x - s / 2, c.y - s / 2), size = Size(s, s),
                    cornerRadius = CornerRadius(2.dp.toPx()), style = stroke)
                for (k in 0 until 4) {
                    val lx = c.x - s / 2 + s * (k + 0.5f) / 4f
                    drawLine(line, Offset(lx, c.y - s / 2), Offset(lx, c.y - s / 2 - leg), strokeWidth = stroke.width)
                    drawLine(line, Offset(lx, c.y + s / 2), Offset(lx, c.y + s / 2 + leg), strokeWidth = stroke.width)
                }
                drawCircle(line, radius = 1.3.dp.toPx(), center = Offset(c.x - s / 2 + 3.dp.toPx(), c.y - s / 2 + 3.dp.toPx()))
            }
        }
    }
}

/** Single-bend 45° PCB route from [s] to [e]: the longer axis runs straight,
 *  then a 45° diagonal lands exactly on the target. */
private fun tracePath(s: Offset, e: Offset): Path {
    val dx = e.x - s.x
    val dy = e.y - s.y
    val adx = abs(dx)
    val ady = abs(dy)
    val p = Path()
    p.moveTo(s.x, s.y)
    if (adx >= ady) {
        val midX = s.x + (if (dx >= 0f) 1f else -1f) * (adx - ady)
        p.lineTo(midX, s.y)
    } else {
        val midY = s.y + (if (dy >= 0f) 1f else -1f) * (ady - adx)
        p.lineTo(s.x, midY)
    }
    p.lineTo(e.x, e.y)
    return p
}

/** Reconnect to the last connection. Filled primary CTA with a leading
 *  SignalSweep while reconnecting and a trailing X to forget the bond. */
@Composable
private fun ReconnectRow(
    last: LastConnection,
    reconnecting: Boolean,
    onReconnect: () -> Unit,
    onForget: () -> Unit,
) {
    val transportLabel = stringResource(
        when (last.kind) {
            TransportKind.BLE_ESP -> R.string.transport_tile_pulsar_ble_title
            TransportKind.CCAPI -> R.string.transport_tile_ccapi_title
            TransportKind.PTP_USB -> R.string.transport_tile_ptp_title
            TransportKind.CANON_BLE -> R.string.transport_tile_canon_ble_title
            TransportKind.PTP_IP -> R.string.transport_tile_ptp_ip_title
        }
    )
    Surface(
        onClick = { if (!reconnecting) onReconnect() },
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.primaryContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(start = 16.dp, end = 4.dp, top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(modifier = Modifier.size(22.dp), contentAlignment = Alignment.Center) {
                if (reconnecting) {
                    SignalSweep(
                        modifier = Modifier.size(22.dp, 12.dp),
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                } else {
                    Icon(
                        Icons.Default.History,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    last.label,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    maxLines = 1,
                )
                Text(
                    transportLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.75f),
                    maxLines = 1,
                )
            }
            IconButton(onClick = onForget, modifier = Modifier.size(32.dp)) {
                Icon(
                    Icons.Default.Close,
                    contentDescription = stringResource(R.string.scan_landing_forget),
                    tint = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f),
                    modifier = Modifier.size(16.dp),
                )
            }
        }
    }
}
