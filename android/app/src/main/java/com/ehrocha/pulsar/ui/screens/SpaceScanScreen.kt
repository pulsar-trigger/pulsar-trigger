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
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
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
import com.ehrocha.pulsar.ui.components.SignalSweep
import com.ehrocha.pulsar.ui.components.SpaceField
import com.ehrocha.pulsar.ui.theme.Grotesk
import com.ehrocha.pulsar.ui.theme.LocalNightMode
import com.ehrocha.pulsar.ui.theme.Mono
import com.ehrocha.pulsar.ui.theme.PlanetAmber
import com.ehrocha.pulsar.ui.theme.PlanetAzure
import com.ehrocha.pulsar.ui.theme.PlanetCopper
import com.ehrocha.pulsar.ui.theme.PlanetMint
import com.ehrocha.pulsar.ui.theme.PlanetRose
import com.ehrocha.pulsar.ui.theme.PlanetSlate
import com.ehrocha.pulsar.ui.theme.PulsarTheme
import com.ehrocha.pulsar.ui.theme.ThemeMode
import com.ehrocha.pulsar.viewmodel.PulsarViewModel
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * SPACE visual style scan landing — a tiny solar system.
 *
 * A real **pulsar** burns in the upper field (core + glow + radio-pulse rings +
 * a slow signature sweep); the six transports are **planet-worlds** on tilted
 * elliptical orbits fanning out below it. The background ([SpaceField]) is a
 * starfield with the occasional meteor. Each world keeps the role palette
 * except its own hue (the one deliberate break from SIGNAL); in RedLight night
 * mode the hues red-shift so dark adaptation survives.
 */
@Composable
fun SpaceScanScreen(
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
    val ctx = LocalContext.current

    // Corner-cascade (solar-system diagram): the pulsar sits in the upper-left
    // corner and the worlds march outward along nested orbits on a down-right
    // diagonal — small/near bodies first, gas-giant farthest — so each planet
    // gets its own ring + screen region instead of crowding a single band.
    val planets = remember {
        listOf(
            PlanetSpec(TransportKind.CANON_BLE, Icons.Default.Bluetooth,
                R.string.transport_tile_canon_ble_title, R.string.transport_short_canon_ble, listOf("bulb"),
                PlanetRose, 22.dp, ring = false, ghost = false, orbit = 0.32f, angleDeg = 24f),
            PlanetSpec(TransportKind.BLE_ESP, Icons.Default.Bluetooth,
                R.string.transport_tile_pulsar_ble_title, R.string.transport_short_ble_esp, listOf("bulb"),
                PlanetAzure, 30.dp, ring = false, ghost = false, orbit = 0.52f, angleDeg = 66f),
            PlanetSpec(null, Icons.Default.Science,
                R.string.transport_tile_simulator_title, R.string.transport_short_sim, listOf("demo"),
                PlanetMint, 28.dp, ring = false, ghost = true, orbit = 0.66f, angleDeg = 28f),
            PlanetSpec(TransportKind.PTP_USB, Icons.Default.Usb,
                R.string.transport_tile_ptp_title, R.string.transport_short_usb, listOf("lv", "bulb"),
                PlanetCopper, 34.dp, ring = false, ghost = false, orbit = 0.80f, angleDeg = 56f),
            PlanetSpec(TransportKind.PTP_IP, Icons.Default.Wifi,
                R.string.transport_tile_ptp_ip_title, R.string.transport_short_ptp_ip, listOf("lv", "bulb"),
                PlanetSlate, 40.dp, ring = false, ghost = false, orbit = 0.95f, angleDeg = 35f),
            PlanetSpec(TransportKind.CCAPI, Icons.Default.Wifi,
                R.string.transport_tile_ccapi_title, R.string.transport_short_ccapi, listOf("lv", "bat", "bulb"),
                PlanetAmber, 48.dp, ring = true, ghost = false, orbit = 1.08f, angleDeg = 53f),
        )
    }

    Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        SpaceField(Modifier.fillMaxSize())

        Column(modifier = Modifier.fillMaxSize()) {
            SpaceBrandHeader()

            // ── Viewport: the decorative orrery (pulsar · orbits · planets),
            // now scenery only — the cockpit below does the picking. ──────────
            DecorOrrery(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                planets = planets,
                // No "recent" highlight at rest — the scan screen starts with
                // every transport unselected. Only an ACTIVE reconnect glows
                // its target.
                recentKind = null,
                reconnectingKind = if (reconnecting) lastConnection?.kind else null,
            )

            // ── Cockpit console: select your destination ─────────────────────
            Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                Text(
                    stringResource(R.string.scan_space_select_destination).uppercase(),
                    fontFamily = Mono,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 3.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp),
                )

                val tint = planetTint()
                planets.chunked(2).forEach { row ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        row.forEach { spec ->
                            DestinationButton(
                                spec = spec,
                                hue = tint(spec.hue),
                                recent = spec.kind != null && spec.kind == lastConnection?.kind,
                                connecting = spec.kind != null && reconnecting && spec.kind == lastConnection?.kind,
                                modifier = Modifier.weight(1f),
                                onClick = { if (spec.kind == null) onSimulatorSelected() else onTransportSelected(spec.kind) },
                            )
                        }
                        if (row.size == 1) Spacer(Modifier.weight(1f))
                    }
                }

                lastConnection?.let { last ->
                    SpaceReconnectRow(
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

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    SpaceToolPad(Icons.Default.Bluetooth, stringResource(R.string.section_devices), onManageDevicesSelected)
                    SpaceToolPad(Icons.Default.Description, stringResource(R.string.scan_tool_diagnostics)) {
                        shareDiagnostics(ctx, vm.canonDiagnosticsText())
                    }
                }

                Spacer(Modifier.height(16.dp))
            }
        }
    }
}

private data class PlanetSpec(
    /** null == Simulator (it has no [TransportKind]). */
    val kind: TransportKind?,
    val icon: ImageVector,
    val titleRes: Int,
    /** Short caption for the orrery (planet names must stay tight). */
    val shortRes: Int,
    val caps: List<String>,
    val hue: Color,
    val sizeDp: Dp,
    val ring: Boolean,
    val ghost: Boolean,
    /** Orbit radius as a fraction of the field's reach (0..1). */
    val orbit: Float,
    /** Angle on the orbit ellipse; 0° = right, 90° = straight down. */
    val angleDeg: Float,
)

/** Brand header at the very top of the Space scan screen — the Pulsar mark +
 *  PULSAR wordmark + PSR-5T·version silkscreen. */
@Composable
private fun SpaceBrandHeader() {
    Column(
        modifier = Modifier.fillMaxWidth().padding(top = 20.dp, bottom = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            androidx.compose.foundation.Image(
                painter = androidx.compose.ui.res.painterResource(R.drawable.ic_pulsar_mark),
                contentDescription = null,
                modifier = Modifier.size(34.dp),
            )
            Text(
                stringResource(R.string.app_name).uppercase(),
                fontFamily = com.ehrocha.pulsar.ui.theme.Display,
                fontWeight = FontWeight.Black,
                letterSpacing = 6.sp,
                fontSize = 26.sp,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        Text(
            "PSR-5T · v${com.ehrocha.pulsar.BuildConfig.VERSION_NAME}",
            fontFamily = Mono,
            fontSize = 9.sp,
            letterSpacing = 1.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}

@Composable
private fun DecorOrrery(
    modifier: Modifier,
    planets: List<PlanetSpec>,
    recentKind: TransportKind?,
    reconnectingKind: TransportKind?,
) {
    val colors = PulsarTheme.colors
    val primary = MaterialTheme.colorScheme.primary
    val onSurface = MaterialTheme.colorScheme.onSurface
    val orbitColor = colors.liveStart.copy(alpha = 0.16f)
    val tint = planetTint()

    val beamAngle by rememberInfiniteTransition(label = "pulsar").animateFloat(
        0f, (2f * PI).toFloat(),
        infiniteRepeatable(tween(11000, easing = LinearEasing), RepeatMode.Restart), label = "beam",
    )
    val pulse by rememberInfiniteTransition(label = "ring").animateFloat(
        0f, 1f, infiniteRepeatable(tween(2600, easing = LinearEasing), RepeatMode.Restart), label = "pulse",
    )
    val breathe by rememberInfiniteTransition(label = "core").animateFloat(
        0f, 1f, infiniteRepeatable(tween(1300, easing = LinearEasing), RepeatMode.Reverse), label = "breathe",
    )

    // Pure scenery now: pulsar high-left, worlds cascading through the viewport;
    // the cockpit console below does the actual picking.
    Canvas(modifier) {
        val cxF = 0.14f
        val cyF = 0.34f
        val rxF = 0.84f
        val ryF = 0.56f
        val cx = size.width * cxF
        val cy = size.height * cyF
        val rx = size.width * rxF
        val ry = size.height * ryF
        val core = Offset(cx, cy)

        planets.forEach { p ->
            drawOval(
                color = orbitColor,
                topLeft = Offset(cx - p.orbit * rx, cy - p.orbit * ry),
                size = Size(2f * p.orbit * rx, 2f * p.orbit * ry),
                style = Stroke(width = 1.dp.toPx()),
            )
        }

        val glowR = 56.dp.toPx()
        drawCircle(
            brush = Brush.radialGradient(
                listOf(primary.copy(alpha = 0.12f + 0.22f * breathe), Color.Transparent),
                center = core, radius = glowR,
            ),
            radius = glowR, center = core,
        )

        val maxRing = size.width * 0.50f
        listOf(pulse, (pulse + 0.5f).mod(1f)).forEach { ph ->
            drawCircle(
                color = colors.liveEnd.copy(alpha = ((1f - ph) * 0.40f).coerceIn(0f, 1f)),
                radius = ph * maxRing, center = core,
                style = Stroke(width = 1.5.dp.toPx()),
            )
        }

        val beamLen = size.width * 0.74f
        val beamHalf = 9.dp.toPx()
        val near = colors.liveEnd.copy(alpha = 0.26f)
        val far = colors.liveStart.copy(alpha = 0f)
        drawPulsarBeam(core, beamAngle, beamLen, beamHalf, near, far)
        drawPulsarBeam(core, beamAngle + PI.toFloat(), beamLen, beamHalf, near, far)

        drawCircle(colors.liveEnd.copy(alpha = 0.5f), radius = 10.dp.toPx(), center = core)
        drawCircle(onSurface, radius = 5.5.dp.toPx(), center = core)
        drawCircle(Color.White, radius = 2.5.dp.toPx(), center = core)

        // decorative worlds on their orbits — no labels, no tap
        planets.forEach { p ->
            val a = (p.angleDeg * PI / 180f).toFloat()
            val pc = Offset(cx + p.orbit * rx * cos(a), cy + p.orbit * ry * sin(a))
            val r = p.sizeDp.toPx() / 2f * 0.78f
            val glow = p.kind != null && (p.kind == recentKind || p.kind == reconnectingKind)
            drawDecorPlanet(pc, r, tint(p.hue), p.ring, p.ghost, glow, colors.liveStart)
        }
    }
}

/** A scenery planet for the orrery viewport: a shaded sphere (or wireframe ghost),
 *  optional ring, and a soft glow when it's the recent/connecting world. */
private fun DrawScope.drawDecorPlanet(
    c: Offset, r: Float, hue: Color, ring: Boolean, ghost: Boolean, glow: Boolean, glowColor: Color,
) {
    if (glow) {
        val hr = r * 2.1f
        drawCircle(
            brush = Brush.radialGradient(listOf(glowColor.copy(alpha = 0.4f), Color.Transparent), center = c, radius = hr),
            radius = hr, center = c,
        )
    }
    if (ghost) {
        drawCircle(hue.copy(alpha = 0.85f), radius = r, center = c, style = Stroke(1.5.dp.toPx()))
        drawOval(
            hue.copy(alpha = 0.5f),
            topLeft = Offset(c.x - r, c.y - r * 0.42f),
            size = Size(2f * r, r * 0.84f),
            style = Stroke(1.dp.toPx()),
        )
    } else {
        if (ring) {
            drawOval(
                hue.copy(alpha = 0.55f),
                topLeft = Offset(c.x - r * 1.7f, c.y - r * 0.5f),
                size = Size(r * 3.4f, r),
                style = Stroke(2.dp.toPx()),
            )
        }
        drawCircle(
            brush = Brush.radialGradient(
                listOf(lerp(hue, Color.White, 0.5f), hue, lerp(hue, Color.Black, 0.5f)),
                center = Offset(c.x - r * 0.32f, c.y - r * 0.34f),
                radius = r * 1.35f,
            ),
            radius = r, center = c,
        )
    }
}

/** A pulsar beam: a cone from the bright [core] apex out to a faint wide tip. */
private fun DrawScope.drawPulsarBeam(
    core: Offset, angle: Float, length: Float, halfW: Float, near: Color, far: Color,
) {
    val dir = Offset(cos(angle), sin(angle))
    val perp = Offset(-dir.y, dir.x)
    val tip = core + dir * length
    val p1 = tip + perp * halfW
    val p2 = tip - perp * halfW
    val path = Path().apply {
        moveTo(core.x, core.y)
        lineTo(p1.x, p1.y)
        lineTo(p2.x, p2.y)
        close()
    }
    drawPath(path, Brush.linearGradient(listOf(near, far), start = core, end = tip))
}

/** In RedLight night mode, collapse a planet hue to a luminance-matched shade
 *  of the red phosphor primary (night-vision safe); otherwise keep the hue. */
@Composable
private fun planetTint(): (Color) -> Color {
    val night = LocalNightMode.current.value
    val primary = MaterialTheme.colorScheme.primary
    return remember(night, primary) {
        { base ->
            if (night == ThemeMode.RedLight) {
                lerp(Color.Black, primary, (0.30f + 0.6f * base.luminance()).coerceIn(0f, 1f))
            } else {
                base
            }
        }
    }
}

/** Cockpit destination key for one transport: a mini shaded "world" in the
 *  transport's hue + icon, the name + caps, and a status LED. Pressing it (or
 *  the recent/connecting one) lights the LED + border in that world's colour. */
@Composable
private fun DestinationButton(
    spec: PlanetSpec,
    hue: Color,
    recent: Boolean,
    connecting: Boolean,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val lit = recent || pressed || connecting
    Surface(
        onClick = onClick,
        interactionSource = source,
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        border = if (lit) BorderStroke(1.5.dp, hue)
        else BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)),
        modifier = modifier.height(78.dp),
    ) {
        Box(Modifier.fillMaxSize()) {
            Box(
                Modifier.fillMaxWidth().height(30.dp).align(Alignment.TopCenter)
                    .background(Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.06f), Color.Transparent))),
            )
            Row(
                modifier = Modifier.fillMaxSize().padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Box(modifier = Modifier.size(28.dp), contentAlignment = Alignment.Center) {
                    Canvas(Modifier.fillMaxSize()) {
                        val c = Offset(size.width / 2f, size.height / 2f)
                        val r = size.minDimension / 2f
                        drawCircle(
                            brush = Brush.radialGradient(
                                listOf(lerp(hue, Color.White, 0.5f), hue, lerp(hue, Color.Black, 0.5f)),
                                center = Offset(c.x - r * 0.3f, c.y - r * 0.3f),
                                radius = r * 1.3f,
                            ),
                            radius = r, center = c,
                        )
                    }
                    Icon(
                        spec.icon, contentDescription = null,
                        tint = Color.White.copy(alpha = 0.9f),
                        modifier = Modifier.size(15.dp),
                    )
                }
                Column(Modifier.weight(1f)) {
                    Text(
                        stringResource(spec.titleRes),
                        fontFamily = Grotesk, fontWeight = FontWeight.SemiBold,
                        fontSize = 13.sp, lineHeight = 14.sp,
                        maxLines = 2, overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    if (connecting) {
                        SignalSweep(modifier = Modifier.size(22.dp, 9.dp))
                    } else {
                        Text(
                            spec.caps.joinToString(" · "),
                            fontFamily = Mono, fontSize = 9.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                Box(
                    Modifier.size(7.dp).clip(CircleShape)
                        .background(if (lit) hue else MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)),
                )
            }
        }
    }
}

/** Reconnect to the last connection — filled CTA with a leading sweep while
 *  reconnecting and a trailing X to forget the bond. */
@Composable
private fun SpaceReconnectRow(
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

/** Ground-station utility chip — translucent so the starfield shows through. */
@Composable
private fun RowScope.SpaceToolPad(icon: ImageVector, label: String, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.7f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)),
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
