/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Copyright (C) 2026 Pulsar Trigger contributors
 */

package com.ehrocha.pulsar.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ehrocha.pulsar.R
import com.ehrocha.pulsar.model.LastConnection
import com.ehrocha.pulsar.transport.TransportKind
import com.ehrocha.pulsar.viewmodel.PulsarViewModel

/**
 * Initial post-permission screen. Replaces the old all-in-one ScanScreen as
 * the user's entry point. The user picks a transport from a 2×2 tile grid
 * (Pulsar BLE, Canon Wi-Fi, USB PTP, Canon BLE) plus a "Use simulator"
 * link below; tapping a tile navigates to that transport's setup screen.
 *
 * If [PulsarViewModel.lastConnection] is non-null, a reconnect CTA appears
 * above the grid for one-tap return to the most-recent device.
 *
 * Phase 2 of the scan-screen overhaul: tiles route to the legacy
 * [ScanScreen] for now (filtered visually by the user's choice — they'll
 * only look at the section they care about). Phase 3 replaces those
 * routes with per-transport setup screens.
 */
@Composable
fun ScanLandingScreen(
    vm: PulsarViewModel,
    onTransportSelected: (TransportKind) -> Unit,
    onSimulatorSelected: () -> Unit,
    onConnected: () -> Unit,
) {
    val connected by vm.connected.collectAsState()
    val lastConnection by vm.lastConnection.collectAsState()
    val canonBleConnecting by vm.canonBleConnecting.collectAsState()
    val ptpConnecting by vm.ptpConnecting.collectAsState()
    val ccapiConnecting by vm.canonCcapiConnecting.collectAsState()
    // Reconnect is in flight for the last-used transport — drive the card's
    // spinner so a tap isn't ambiguous (Canon BLE autoConnect can wait a while).
    val reconnecting = when (lastConnection?.kind) {
        TransportKind.CANON_BLE -> canonBleConnecting
        TransportKind.PTP_USB -> ptpConnecting
        TransportKind.CCAPI -> ccapiConnecting
        else -> false
    }
    if (connected) {
        LaunchedEffect(Unit) { onConnected() }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp)
    ) {
        Spacer(Modifier.height(32.dp))

        // Brand header — slim form: gradient mark + wordmark, no tagline.
        // The detailed tagline was double duty with the "Pick a transport"
        // subtitle below; dropping it (and halving the spacers around it)
        // wins ~70 dp of vertical real estate on the landing.
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(
                        Brush.linearGradient(
                            listOf(
                                MaterialTheme.colorScheme.primary,
                                MaterialTheme.colorScheme.primary.copy(alpha = 0.6f),
                            )
                        )
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Default.Bluetooth,
                    contentDescription = null,
                    tint = Color.White,
                )
            }
            Spacer(Modifier.width(14.dp))
            Text(
                stringResource(R.string.app_name),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Black,
                letterSpacing = 2.sp,
                color = MaterialTheme.colorScheme.primary,
            )
        }

        Spacer(Modifier.height(20.dp))

        Text(
            stringResource(R.string.scan_landing_pick_transport),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
        )
        Text(
            stringResource(R.string.scan_landing_subtitle),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(16.dp))

        // ── Transport tiles: 2×2 grid + wide simulator tile below ─────────
        val transportTiles = listOf(
            TileSpec(
                kind = TransportKind.BLE_ESP,
                icon = Icons.Default.Bluetooth,
                titleRes = R.string.transport_tile_pulsar_ble_title,
                subtitleRes = R.string.transport_tile_pulsar_ble_subtitle,
            ),
            TileSpec(
                kind = TransportKind.CCAPI,
                icon = Icons.Default.Wifi,
                titleRes = R.string.transport_tile_ccapi_title,
                subtitleRes = R.string.transport_tile_ccapi_subtitle,
            ),
            TileSpec(
                kind = TransportKind.PTP_USB,
                icon = Icons.Default.Usb,
                titleRes = R.string.transport_tile_ptp_title,
                subtitleRes = R.string.transport_tile_ptp_subtitle,
            ),
            TileSpec(
                kind = TransportKind.CANON_BLE,
                icon = Icons.Default.Bluetooth,
                titleRes = R.string.transport_tile_canon_ble_title,
                subtitleRes = R.string.transport_tile_canon_ble_subtitle,
            ),
        )
        transportTiles.chunked(2).forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                row.forEach { spec ->
                    TransportTile(spec, modifier = Modifier.weight(1f)) {
                        onTransportSelected(spec.kind)
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
        }

        // Simulator: same tile family, wider/shorter so it reads as a peer
        // option but visually distinct from the four real transports.
        SimulatorTile(onClick = onSimulatorSelected)

        // ── Known device (last connection) ────────────────────────────────
        // No section heading — the primaryContainer-tinted row is its own
        // visual anchor and labels itself ("MonsteRP / Canon BLE"). When
        // multi-device history lands, restore the heading + scrollable list.
        lastConnection?.let { last ->
            Spacer(Modifier.height(20.dp))
            ReconnectCard(
                last = last,
                reconnecting = reconnecting,
                onReconnect = { vm.reconnectLast() },
                onForget = { vm.forgetLastConnection() },
            )
        }

        Spacer(Modifier.height(16.dp))

        // ── Collect diagnostics — reachable while disconnected, so connection
        //    issues (e.g. a failed reconnect) can be captured without adb.
        val diagCtx = androidx.compose.ui.platform.LocalContext.current
        TextButton(onClick = { shareDiagnostics(diagCtx, vm.canonDiagnosticsText()) }) {
            Text(
                stringResource(R.string.tools_collect_diagnostics),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(24.dp))
    }
}

private data class TileSpec(
    val kind: TransportKind,
    val icon: ImageVector,
    val titleRes: Int,
    val subtitleRes: Int,
)

@Composable
private fun TransportTile(spec: TileSpec, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 2.dp,
        modifier = modifier.aspectRatio(1f),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                modifier = Modifier.size(44.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        spec.icon,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(26.dp),
                    )
                }
            }
            Column {
                Text(
                    stringResource(spec.titleRes),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    stringResource(spec.subtitleRes),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** Wide horizontal tile for the simulator option. Same surface family as the
 *  transport tiles but laid out icon-left / text-right at half the height —
 *  signals "peer option" without competing for visual weight with the four
 *  real transports above it. */
@Composable
private fun SimulatorTile(onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 2.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                modifier = Modifier.size(44.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.Default.Science,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(26.dp),
                    )
                }
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.transport_tile_simulator_title),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    stringResource(R.string.transport_tile_simulator_subtitle),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** Slim one-line row for a known device. Tap = reconnect, trailing X =
 *  forget. Spinner replaces the leading icon while reconnect is in flight,
 *  and taps are swallowed so the user can't stack attempts. Sized and
 *  styled to match the transport tile family so it reads as a continuation
 *  of the surface set above. */
@Composable
private fun ReconnectCard(
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
        }
    )
    Surface(
        onClick = { if (!reconnecting) onReconnect() },
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.primaryContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(start = 14.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(modifier = Modifier.size(24.dp), contentAlignment = Alignment.Center) {
                if (reconnecting) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
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
            IconButton(
                onClick = onForget,
                modifier = Modifier.size(32.dp),
            ) {
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
