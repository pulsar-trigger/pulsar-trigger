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
import androidx.compose.foundation.shape.CutCornerShape
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ehrocha.pulsar.R
import com.ehrocha.pulsar.model.LastConnection
import com.ehrocha.pulsar.transport.TransportKind
import com.ehrocha.pulsar.ui.components.GridField
import com.ehrocha.pulsar.ui.components.PcbField
import com.ehrocha.pulsar.ui.components.SignalSweep
import com.ehrocha.pulsar.ui.theme.Display
import com.ehrocha.pulsar.ui.theme.Grotesk
import com.ehrocha.pulsar.ui.theme.Mono
import com.ehrocha.pulsar.ui.theme.LocalVisualStyle
import com.ehrocha.pulsar.ui.theme.PulsarTheme
import com.ehrocha.pulsar.ui.theme.VisualStyle
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
    // Style dispatch: SPACE → the orrery; CLASSIC → the original tile-grid;
    // CIRCUIT → the Driver-IC board below.
    when (LocalVisualStyle.current.value) {
        VisualStyle.SPACE -> {
            SpaceScanScreen(vm, onTransportSelected, onSimulatorSelected, onConnected, onManageDevicesSelected)
            return
        }
        VisualStyle.CLASSIC -> {
            ScanLandingClassic(vm, onTransportSelected, onSimulatorSelected, onConnected, onManageDevicesSelected)
            return
        }
        // GRID shares the Driver-IC board layout below; its backdrop swaps to
        // the neon grid (gated where the board field is drawn).
        VisualStyle.CIRCUIT, VisualStyle.GRID -> Unit
    }
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

    // Six pads scattered around a wide DIP chip — three in the upper field,
    // three in the lower, each at its own height so it reads like a populated
    // board, not a grid. Top holds the live-view Canon transports; the bottom
    // the BLE pair + Simulator. (x, y) are board-fraction centres.
    val pads = remember {
        listOf(
            PadSpec(TransportKind.CCAPI, Icons.Default.Wifi,
                R.string.transport_tile_ccapi_title, listOf("lv", "bat", "bulb"), 0.19f, 0.19f),
            PadSpec(TransportKind.PTP_USB, Icons.Default.Usb,
                R.string.transport_tile_ptp_title, listOf("lv", "bulb"), 0.50f, 0.115f),
            PadSpec(TransportKind.PTP_IP, Icons.Default.Wifi,
                R.string.transport_tile_ptp_ip_title, listOf("lv", "bulb"), 0.81f, 0.21f),
            PadSpec(TransportKind.BLE_ESP, Icons.Default.Bluetooth,
                R.string.transport_tile_pulsar_ble_title, listOf("bulb"), 0.19f, 0.81f),
            PadSpec(TransportKind.CANON_BLE, Icons.Default.Bluetooth,
                R.string.transport_tile_canon_ble_title, listOf("bulb"), 0.50f, 0.885f),
            PadSpec(null, Icons.Default.Science,
                R.string.transport_tile_simulator_title, listOf("demo"), 0.81f, 0.79f),
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
            // No "recent" highlight at rest — start with all transports
            // unselected; only an active reconnect highlights its target.
            recentKind = null,
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
    /** Pad CENTRE as a fraction of board width/height — scattered, not gridded.
     *  [yf] < 0.5 docks on the chip's top edge, else the bottom. */
    val xf: Float,
    val yf: Float,
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

        // Wide DIP package laid across the middle; pads scatter above and below.
        val chipW = (w * 0.82f).coerceIn(260.dp, 380.dp)
        val chipH = 84.dp
        val padW = (w * 0.30f).coerceIn(108.dp, 140.dp)
        val padH = 82.dp
        val legW = 9.dp

        val traceFaint = colors.liveStart.copy(alpha = 0.22f)
        val viaFaint = colors.liveStart.copy(alpha = 0.32f)
        val legColor = colors.liveStart.copy(alpha = 0.5f)
        val activeBrush = Brush.linearGradient(listOf(colors.liveStart, colors.liveEnd))

        val isGrid = LocalVisualStyle.current.value == VisualStyle.GRID
        // Backdrop — PCB field for Circuit; for Grid, the LIVE neon grid with
        // light cycles, and the IC chrome below is dropped so it stops echoing
        // Circuit — the transports sit as programs on the bare Grid.
        if (isGrid) {
            GridField(Modifier.matchParentSize(), animated = true)
        } else {
            PcbField(Modifier.matchParentSize(), animated = false)
        }

        // ── IC layer (Circuit only): DIP pins + functional traces + beacon ──
        if (!isGrid) Canvas(Modifier.matchParentSize()) {
            val cx = size.width / 2f
            val cy = size.height / 2f
            val chHW = chipW.toPx() / 2f
            val chHH = chipH.toPx() / 2f
            val legWpx = legW.toPx()
            val legHpx = 6.dp.toPx()
            val padHpx = padH.toPx()
            val pinInset = 16.dp.toPx()

            // Decorative DIP pins evenly along the top + bottom long edges.
            val nPins = 7
            for (k in 0 until nPins) {
                val px = cx - chHW + pinInset + (chipW.toPx() - 2f * pinInset) * k / (nPins - 1)
                drawRect(legColor.copy(alpha = 0.3f), Offset(px - legHpx / 2f, cy - chHH - legWpx * 0.7f), Size(legHpx, legWpx * 0.7f))
                drawRect(legColor.copy(alpha = 0.3f), Offset(px - legHpx / 2f, cy + chHH), Size(legHpx, legWpx * 0.7f))
            }

            pads.forEach { spec ->
                val padCx = spec.xf * size.width
                val padCy = spec.yf * size.height
                val top = spec.yf < 0.5f
                // The upper pads CROSS: each top pad docks on the DIP pin
                // mirrored across the chip centre, so the far-right leg reaches
                // the far-left pad and vice-versa (centre stays straight). The
                // bottom row docks on the nearer pin as before.
                val pinTargetX = if (top) 2f * cx - padCx else padCx
                val pinX = pinTargetX.coerceIn(cx - chHW + pinInset, cx + chHW - pinInset)
                val pin = Offset(pinX, if (top) cy - chHH - legWpx else cy + chHH + legWpx)
                val padEdgeY = if (top) padCy + padHpx / 2f else padCy - padHpx / 2f
                val anchor = Offset(padCx, if (top) padEdgeY + legWpx else padEdgeY - legWpx)
                // a leg on the chip edge and one on the pad edge
                drawRect(legColor, Offset(pinX - legHpx / 2f, if (top) cy - chHH - legWpx else cy + chHH), Size(legHpx, legWpx))
                drawRect(legColor, Offset(padCx - legHpx / 2f, if (top) padEdgeY else padEdgeY - legWpx), Size(legHpx, legWpx))

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

        // ── Centre brand: Circuit's DIP chip, or Grid's Tron-style wordmark ──
        if (isGrid) GridWordmark(versionName) else ChipCore(chipW, chipH, versionName, breathe)

        // ── The six pads ─────────────────────────────────────────────────────
        // Circuit scatters them like ICs soldered to a board; Grid docks them as
        // programs in two clean rows flanking the PULSAR wordmark — live-view
        // transports up top (toward the I/O tower), local ones along the front.
        pads.forEachIndexed { gi, spec ->
            val xf = if (isGrid) 0.17f + 0.33f * (gi % 3) else spec.xf
            val yf = if (isGrid) (if (gi < 3) 0.20f else 0.80f) else spec.yf
            TransportPad(
                spec = spec,
                recent = spec.kind != null && spec.kind == recentKind,
                connecting = spec.kind != null && spec.kind == reconnectingKind,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .offset(
                        x = (w * xf - padW / 2).coerceIn(0.dp, w - padW),
                        y = h * yf - padH / 2,
                    )
                    .width(padW)
                    .height(padH),
                onClick = { onSelect(spec.kind) },
            )
        }
    }
}

/** Grid centre brand — a PULSAR wordmark + version framed by two neon rules,
 *  in the spirit of the Tron logo (glowing, wide-tracked, bar-framed). Replaces
 *  the DIP [ChipCore] when the Grid style is active. */
@Composable
private fun BoxScope.GridWordmark(versionName: String) {
    val colors = PulsarTheme.colors
    val rule = Brush.horizontalGradient(
        listOf(Color.Transparent, colors.liveStart.copy(alpha = 0.7f), Color.Transparent),
    )
    Column(
        modifier = Modifier.align(Alignment.Center).fillMaxWidth(0.8f),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.fillMaxWidth().height(1.5.dp).background(rule))
        Spacer(Modifier.height(10.dp))
        Text(
            "PULSAR",
            fontFamily = Mono,
            fontWeight = FontWeight.Black,
            fontSize = 30.sp,
            letterSpacing = 8.sp,
            color = colors.liveStart,
            textAlign = TextAlign.Center,
            style = TextStyle(shadow = Shadow(colors.liveStart.copy(alpha = 0.85f), Offset.Zero, 22f)),
        )
        Spacer(Modifier.height(6.dp))
        Text(
            "v$versionName",
            fontFamily = Mono,
            fontSize = 11.sp,
            letterSpacing = 5.sp,
            color = colors.liveEnd.copy(alpha = 0.9f),
        )
        Spacer(Modifier.height(10.dp))
        Box(Modifier.fillMaxWidth().height(1.5.dp).background(rule))
    }
}

/** Pulsar as a wide DIP package laid across the middle: carbon body, a notched
 *  + dotted pin-1 end, the pulsing brand mark on the left and the PULSAR /
 *  part-number silkscreen on the right. The pins are drawn on the board canvas
 *  along the long edges where the traces dock. */
@Composable
private fun BoxScope.ChipCore(width: Dp, height: Dp, versionName: String, breathe: Float) {
    Box(
        modifier = Modifier
            .align(Alignment.Center)
            .size(width, height)
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.55f), RoundedCornerShape(14.dp)),
        contentAlignment = Alignment.Center,
    ) {
        // pin-1 notch carved into the left edge
        Box(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .offset(x = (-7).dp)
                .size(14.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.background),
        )
        // pin-1 dot
        Box(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(10.dp)
                .size(5.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)),
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                // breathing core glow behind the mark — the pulse
                Box(
                    modifier = Modifier
                        .size(height * 0.6f)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f + 0.16f * breathe)),
                )
                Image(
                    painter = painterResource(R.drawable.ic_pulsar_mark),
                    contentDescription = null,
                    modifier = Modifier.size(height * 0.58f),
                )
            }
            Column {
                Text(
                    stringResource(R.string.app_name).uppercase(),
                    fontFamily = Display,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 5.sp,
                    fontSize = 22.sp,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    "PSR-5T · v$versionName",
                    fontFamily = Mono,
                    fontSize = 9.sp,
                    letterSpacing = 1.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                )
            }
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
    // Grid standardizes the pad on the same "program" cell as the launcher
    // tiles: angular cut corner + a glowing neon edge (lit to the live gradient
    // when recent/connecting). Circuit keeps the soldered SMD look.
    val isGrid = LocalVisualStyle.current.value == VisualStyle.GRID
    val lit = recent || connecting
    Surface(
        onClick = onClick,
        shape = if (isGrid) CutCornerShape(topStart = 12.dp, bottomEnd = 12.dp) else RoundedCornerShape(8.dp),
        color = if (isGrid) MaterialTheme.colorScheme.surfaceContainer else MaterialTheme.colorScheme.surface,
        tonalElevation = if (isGrid) 0.dp else 2.dp,
        border = when {
            isGrid && lit -> BorderStroke(2.dp, Brush.linearGradient(listOf(colors.liveStart, colors.liveEnd)))
            isGrid -> BorderStroke(1.5.dp, SolidColor(colors.liveStart.copy(alpha = 0.5f)))
            recent -> BorderStroke(1.dp, colors.liveStart.copy(alpha = 0.55f))
            else -> null
        },
        modifier = modifier,
    ) {
        Box(Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier.fillMaxSize().padding(horizontal = 6.dp, vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Icon(
                    spec.icon,
                    contentDescription = null,
                    tint = if (isGrid) colors.liveStart else MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(22.dp),
                )
                Spacer(Modifier.height(5.dp))
                Text(
                    stringResource(spec.titleRes),
                    fontFamily = Grotesk,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 14.sp,
                    lineHeight = 15.sp,
                    maxLines = 2,
                    textAlign = TextAlign.Center,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(3.dp))
                if (connecting) {
                    SignalSweep(modifier = Modifier.size(22.dp, 10.dp))
                } else {
                    Text(
                        spec.caps.joinToString(" · "),
                        fontFamily = Mono,
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (recent && !connecting) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(6.dp)
                        .size(7.dp)
                        .clip(CircleShape)
                        .background(colors.liveStart),
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
