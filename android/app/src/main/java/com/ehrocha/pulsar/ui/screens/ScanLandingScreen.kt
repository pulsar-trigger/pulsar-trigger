/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Copyright (C) 2026 Pulsar Trigger contributors
 */

package com.ehrocha.pulsar.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Icon
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
    if (connected) {
        LaunchedEffect(Unit) { onConnected() }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 24.dp)
    ) {
        Spacer(Modifier.height(64.dp))

        // Brand header — same look as the old ScanScreen for continuity.
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(48.dp)
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
            Spacer(Modifier.width(16.dp))
            Column(Modifier.fillMaxWidth()) {
                Text(
                    stringResource(R.string.app_name),
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 2.sp,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    stringResource(R.string.app_tagline),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    letterSpacing = 1.sp,
                )
            }
        }

        Spacer(Modifier.height(40.dp))

        // ── Reconnect CTA ────────────────────────────────────────────────
        lastConnection?.let { last ->
            ReconnectCard(
                last = last,
                onReconnect = { vm.reconnectLast() },
                onForget = { vm.forgetLastConnection() },
            )
            Spacer(Modifier.height(20.dp))
        }

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

        // ── 2×2 transport tile grid ──────────────────────────────────────
        val tiles = listOf(
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
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(0.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            items(tiles, key = { it.kind.name }) { spec ->
                TransportTile(spec) { onTransportSelected(spec.kind) }
            }
        }

        Spacer(Modifier.height(20.dp))

        // ── Simulator link (de-emphasised; dev/demo tool, not a real transport)
        TextButton(onClick = onSimulatorSelected) {
            Icon(
                Icons.Default.Science,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.scan_landing_use_simulator))
        }
    }
}

private data class TileSpec(
    val kind: TransportKind,
    val icon: ImageVector,
    val titleRes: Int,
    val subtitleRes: Int,
)

@Composable
private fun TransportTile(spec: TileSpec, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 2.dp,
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1f),
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

@Composable
private fun ReconnectCard(
    last: LastConnection,
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
        onClick = onReconnect,
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.primaryContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Default.History,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.size(28.dp),
            )
            Spacer(Modifier.width(14.dp))
            Column(Modifier.fillMaxWidth()) {
                Text(
                    stringResource(R.string.scan_landing_reconnect_to, last.label),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
                Text(
                    stringResource(R.string.scan_landing_reconnect_via, transportLabel),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f),
                )
                Spacer(Modifier.height(4.dp))
                TextButton(
                    onClick = onForget,
                    contentPadding = PaddingValues(horizontal = 0.dp, vertical = 0.dp),
                ) {
                    Text(
                        stringResource(R.string.scan_landing_forget),
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
        }
    }
}
