/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Copyright (C) 2026 Pulsar Trigger contributors
 */

package com.ehrocha.pulsar.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.FlightTakeoff
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ehrocha.pulsar.R
import com.ehrocha.pulsar.transport.aircraft.AircraftSighting
import com.ehrocha.pulsar.viewmodel.PulsarViewModel
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Tool screen — "what aircraft are overhead right now". Pulls the lat/lon
 * from the Astro Dashboard (so the user only configures location in one
 * place) and polls the configured [com.ehrocha.pulsar.transport.aircraft.AircraftFeed]
 * at its recommended cadence. Auto-starts on entry, stops on dispose.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AircraftWatchScreen(vm: PulsarViewModel, onBack: () -> Unit) {
    val sightings by vm.aircraftSightings.collectAsState()
    val watching by vm.aircraftWatching.collectAsState()
    val error by vm.aircraftWatchError.collectAsState()
    val lastUpdateMs by vm.aircraftWatchLastUpdateMs.collectAsState()
    val radiusKm by vm.aircraftWatchRadiusKm.collectAsState()
    val location = vm.aircraftWatchLocation()

    // Auto-start on entry, stop on dispose. Mirrors how the Canon BLE setup
    // screen owns its scan lifecycle.
    DisposableEffect(Unit) {
        if (location != null) vm.startAircraftWatch()
        onDispose { vm.stopAircraftWatch() }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(R.string.aircraft_watch_title),
                        fontWeight = FontWeight.SemiBold,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.back),
                        )
                    }
                },
                actions = {
                    IconButton(
                        onClick = { vm.refreshAircraftWatch() },
                        enabled = watching,
                    ) {
                        Icon(
                            Icons.Default.Refresh,
                            contentDescription = stringResource(R.string.aircraft_watch_refresh),
                        )
                    }
                },
            )
        },
    ) { pad ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(pad)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (location == null) {
                NoLocationCard()
                return@Scaffold
            }

            HeaderCard(
                lat = location.first,
                lon = location.second,
                radiusKm = radiusKm,
                onRadiusChange = vm::setAircraftWatchRadiusKm,
                watching = watching,
                lastUpdateMs = lastUpdateMs,
                providerName = vm.aircraftFeedName,
            )

            error?.let { ErrorBanner(it) }

            if (sightings.isEmpty() && watching && error == null) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    if (lastUpdateMs == 0L) {
                        CircularProgressIndicator(strokeWidth = 2.dp)
                    } else {
                        Text(
                            stringResource(R.string.aircraft_watch_no_aircraft),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(sightings, key = { it.icaoHex }) { s -> AircraftRow(s) }
                }
            }
        }
    }
}

@Composable
private fun HeaderCard(
    lat: Double,
    lon: Double,
    radiusKm: Int,
    onRadiusChange: (Int) -> Unit,
    watching: Boolean,
    lastUpdateMs: Long,
    providerName: String,
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.FlightTakeoff,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    String.format(Locale.US, "%.4f°, %.4f°", lat, lon),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.aircraft_watch_radius_label, radiusKm),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Slider(
                value = radiusKm.toFloat(),
                onValueChange = { onRadiusChange(it.roundToInt()) },
                valueRange = 5f..200f,
                steps = 38,  // 5 → 200 in 5 km steps = 39 stops, 38 between
                modifier = Modifier.fillMaxWidth(),
            )
            val statusText = when {
                !watching -> stringResource(R.string.aircraft_watch_paused)
                lastUpdateMs == 0L -> stringResource(R.string.aircraft_watch_fetching)
                else -> stringResource(
                    R.string.aircraft_watch_updated_at,
                    formatTime(lastUpdateMs),
                    providerName,
                )
            }
            Text(
                statusText,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun NoLocationCard() {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                stringResource(R.string.aircraft_watch_no_location_title),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                stringResource(R.string.aircraft_watch_no_location_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ErrorBanner(error: String) {
    val text = when (error) {
        "no_location" -> stringResource(R.string.aircraft_watch_no_location_title)
        "fetch_failed" -> stringResource(R.string.aircraft_watch_fetch_failed)
        else -> error
    }
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.errorContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text,
            modifier = Modifier.padding(12.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onErrorContainer,
        )
    }
}

@Composable
private fun AircraftRow(s: AircraftSighting) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    s.callsign ?: s.icaoHex.uppercase(),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    "${s.icaoHex.uppercase()}${s.originCountry?.let { " · $it" } ?: ""}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontFamily = FontFamily.Monospace,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    formatFlightLine(s),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    String.format(Locale.US, "%.1f km", s.distanceKm),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    "${bearingArrow(s.bearingDeg)} ${s.bearingDeg.roundToInt()}°",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private fun formatFlightLine(s: AircraftSighting): String {
    val alt = s.altitudeFt?.let { "${it.roundToInt()} ft" }
    val spd = s.groundSpeedKt?.let { "${it.roundToInt()} kt" }
    val hdg = s.headingDeg?.let { "${it.roundToInt()}°" }
    val vr = s.verticalRateFpm?.let {
        val sign = if (it >= 0) "↑" else "↓"
        "$sign ${kotlin.math.abs(it).roundToInt()} fpm"
    }
    return listOfNotNull(
        alt,
        spd,
        hdg,
        vr,
        if (s.onGround) "GND" else null,
    ).joinToString(" · ")
}

/** Cheap unicode arrow per 45° octant — enough to glance at heading. */
private fun bearingArrow(deg: Double): String {
    val n = ((deg + 22.5) / 45.0).toInt().mod(8)
    return when (n) {
        0 -> "↑"; 1 -> "↗"; 2 -> "→"; 3 -> "↘"
        4 -> "↓"; 5 -> "↙"; 6 -> "←"; else -> "↖"
    }
}

private fun formatTime(epochMs: Long): String {
    val t = Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault())
    return DateTimeFormatter.ofPattern("HH:mm:ss", Locale.getDefault()).format(t)
}
