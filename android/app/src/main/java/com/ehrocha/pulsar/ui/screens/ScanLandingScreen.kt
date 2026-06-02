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
import androidx.compose.foundation.Image
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ehrocha.pulsar.R
import com.ehrocha.pulsar.model.LastConnection
import com.ehrocha.pulsar.transport.TransportKind
import com.ehrocha.pulsar.ui.components.SectionContainer
import com.ehrocha.pulsar.viewmodel.PulsarViewModel

/**
 * Initial post-permission screen. Three grouped sections share the same
 * `SectionContainer` shape so they read as a coherent set: **Transports**
 * (the 2×2 protocol tile grid), **Recent** (the last-connection reconnect
 * row, shown only if any), **Tools** (Simulator + Diagnostics as compact
 * rows). The Simulator and Diagnostics rows match the Recent row's
 * structure on purpose — within a section the items look alike; the
 * containers themselves carry the visual hierarchy.
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

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Spacer(Modifier.height(20.dp))

        // ── Brand mark ────────────────────────────────────────────────────
        Row(verticalAlignment = Alignment.CenterVertically) {
            Image(
                painter = painterResource(R.mipmap.ic_launcher),
                contentDescription = null,
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape),
            )
            Spacer(Modifier.width(14.dp))
            Text(
                stringResource(R.string.app_name),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Black,
                letterSpacing = 2.sp,
                color = MaterialTheme.colorScheme.primary,
            )
        }

        Spacer(Modifier.height(4.dp))

        // ── Transports ────────────────────────────────────────────────────
        // 2×3 grid grouped by family: BLE row, Wi-Fi row, USB + Simulator
        // row. Simulator joins the transport tiles (instead of living in the
        // Tools section) so all the "ways to connect" sit together. Each
        // row has two tiles of similar shape so the grid reads as a single
        // matrix.
        SectionContainer(title = stringResource(R.string.scan_section_transports)) {
            val rows = listOf(
                // BLE row
                listOf(
                    TileSpec(TransportKind.BLE_ESP, Icons.Default.Bluetooth,
                        R.string.transport_tile_pulsar_ble_title,
                        R.string.transport_tile_pulsar_ble_subtitle),
                    TileSpec(TransportKind.CANON_BLE, Icons.Default.Bluetooth,
                        R.string.transport_tile_canon_ble_title,
                        R.string.transport_tile_canon_ble_subtitle),
                ),
                // Wi-Fi row
                listOf(
                    TileSpec(TransportKind.CCAPI, Icons.Default.Wifi,
                        R.string.transport_tile_ccapi_title,
                        R.string.transport_tile_ccapi_subtitle),
                    TileSpec(TransportKind.PTP_IP, Icons.Default.Wifi,
                        R.string.transport_tile_ptp_ip_title,
                        R.string.transport_tile_ptp_ip_subtitle),
                ),
                // USB + Simulator row
                listOf(
                    TileSpec(TransportKind.PTP_USB, Icons.Default.Usb,
                        R.string.transport_tile_ptp_title,
                        R.string.transport_tile_ptp_subtitle),
                    // Sentinel: a null kind means "simulator" (no
                    // TransportKind for it). Handled below.
                    null,
                ),
            )
            rows.forEach { row ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    row.forEach { spec ->
                        if (spec != null) {
                            TransportTile(spec, modifier = Modifier.weight(1f)) {
                                onTransportSelected(spec.kind)
                            }
                        } else {
                            SimulatorTile(modifier = Modifier.weight(1f), onClick = onSimulatorSelected)
                        }
                    }
                }
            }
        }

        // ── Recent ────────────────────────────────────────────────────────
        lastConnection?.let { last ->
            SectionContainer(title = stringResource(R.string.scan_section_recent)) {
                ReconnectRow(
                    last = last,
                    reconnecting = reconnecting,
                    onReconnect = { vm.reconnectLast() },
                    onForget = { vm.forgetLastConnection() },
                )
            }
        }

        // ── Tools ─────────────────────────────────────────────────────────
        // Simulator moved to the Transports grid above (it's a peer way
        // to drive the app). Tools is just diagnostics now.
        SectionContainer(title = stringResource(R.string.scan_section_tools)) {
            ActionRow(
                icon = Icons.Default.Description,
                title = stringResource(R.string.tools_collect_diagnostics),
                subtitle = stringResource(R.string.tools_collect_diagnostics_hint),
                onClick = { shareDiagnostics(ctx, vm.canonDiagnosticsText()) },
            )
        }

        Spacer(Modifier.height(16.dp))
    }
}

private data class TileSpec(
    val kind: TransportKind,
    val icon: ImageVector,
    val titleRes: Int,
    val subtitleRes: Int,
)

/** Compact tile used by the Transports section. Fixed-height to keep the
 *  section short; icon-top / text-bottom layout. */
@Composable
private fun TransportTile(spec: TileSpec, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 2.dp,
        modifier = modifier.height(108.dp),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Icon(
                spec.icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(26.dp),
            )
            Column {
                Text(
                    stringResource(spec.titleRes),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                )
                Text(
                    stringResource(spec.subtitleRes),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                )
            }
        }
    }
}

/** Simulator tile — same square format as [TransportTile] so it sits as a
 *  peer in the 2×3 transport grid (BLE row / Wi-Fi row / USB + Simulator
 *  row). Same anatomy as TransportTile; separate composable only because
 *  Simulator doesn't have a [TransportKind] entry. */
@Composable
private fun SimulatorTile(modifier: Modifier = Modifier, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 2.dp,
        modifier = modifier.height(108.dp),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Icon(
                Icons.Default.Science,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(26.dp),
            )
            Column {
                Text(
                    stringResource(R.string.transport_tile_simulator_title),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                )
                Text(
                    stringResource(R.string.transport_tile_simulator_subtitle),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                )
            }
        }
    }
}

/** Shared row used inside Tools (Diagnostics today) and structurally
 *  matched by [ReconnectRow] (which adds a trailing forget X + spinner +
 *  primary tint). Keeping the inner anatomy identical is the consistency
 *  the user asked for — same icon size, same padding, same text styles. */
@Composable
private fun ActionRow(
    icon: ImageVector,
    title: String,
    subtitle: String?,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 2.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(22.dp),
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                )
                if (subtitle != null) {
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                    )
                }
            }
        }
    }
}

/** Reconnect to the last connection. Same row anatomy as [ActionRow] but
 *  with a primary tint (this is the CTA), a leading spinner while
 *  reconnecting, and a trailing X to forget the bond. */
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
            modifier = Modifier.padding(start = 14.dp, end = 4.dp, top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(modifier = Modifier.size(22.dp), contentAlignment = Alignment.Center) {
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
