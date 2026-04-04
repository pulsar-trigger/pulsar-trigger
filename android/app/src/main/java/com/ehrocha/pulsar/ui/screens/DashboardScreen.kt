/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Copyright (C) 2026 Pulsar Trigger contributors
 */

package com.ehrocha.pulsar.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ehrocha.pulsar.R
import com.ehrocha.pulsar.astro.*
import kotlinx.coroutines.launch
import java.util.Locale

@Composable
fun DashboardScreen(
    dashboardManager: AstroDashboardManager,
    onBack: () -> Unit,
) {
    val state by dashboardManager.state.collectAsState()
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        dashboardManager.refresh()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
    ) {
        // ── Top bar ──────────────────────────────────────────────────
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(vertical = 8.dp),
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
            }
            Text(
                stringResource(R.string.dashboard_title),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = { scope.launch { dashboardManager.refresh() } }) {
                Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.refresh))
            }
        }

        if (state.loading && state.location == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator()
                    Spacer(Modifier.height(16.dp))
                    Text(stringResource(R.string.status_fetching_data))
                }
            }
            return
        }

        if (state.error != null && state.location == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Default.LocationOff,
                        contentDescription = null,
                        modifier = Modifier.size(48.dp),
                        tint = MaterialTheme.colorScheme.error,
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(state.error!!, textAlign = TextAlign.Center)
                    Spacer(Modifier.height(16.dp))
                    OutlinedButton(onClick = { scope.launch { dashboardManager.refresh() } }) {
                        Text(stringResource(R.string.retry))
                    }
                }
            }
            return
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // ── Location card ────────────────────────────────────────
            state.location?.let { loc ->
                DashCard(title = stringResource(R.string.card_location), icon = Icons.Default.MyLocation) {
                    Text(
                        String.format(Locale.US, "%.4f° %s, %.4f° %s",
                            abs(loc.latitude),
                            if (loc.latitude >= 0) stringResource(R.string.location_north) else stringResource(R.string.location_south),
                            abs(loc.longitude),
                            if (loc.longitude >= 0) stringResource(R.string.location_east) else stringResource(R.string.location_west),
                        ),
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium,
                    )
                    loc.altitude?.let { alt ->
                        Text(
                            String.format(Locale.US, stringResource(R.string.location_altitude), alt),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            // ── Bortle card ──────────────────────────────────────────
            state.bortle?.let { bortle ->
                DashCard(title = stringResource(R.string.card_bortle), icon = Icons.Default.DarkMode) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(48.dp)
                                .clip(CircleShape)
                                .background(Color(bortle.color)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                "${bortle.classNumber}",
                                fontWeight = FontWeight.Black,
                                fontSize = 20.sp,
                                color = if (bortle.classNumber <= 4) Color.White else Color.Black,
                            )
                        }
                        Spacer(Modifier.width(16.dp))
                        Column {
                            Text(
                                "Class ${bortle.classNumber} — ${bortle.className}",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                            )
                            Text(
                                bortle.description,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    AstroRating(bortle.classNumber)
                }
            } ?: run {
                if (!state.loading) {
                    DashCard(title = stringResource(R.string.card_bortle), icon = Icons.Default.DarkMode) {
                        Text(
                            stringResource(R.string.bortle_unavailable),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            // ── Moon card ────────────────────────────────────────────
            state.moon?.let { moon ->
                DashCard(title = stringResource(R.string.card_moon), icon = Icons.Default.NightsStay) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(moon.emoji, fontSize = 48.sp)
                        Spacer(Modifier.width(16.dp))
                        Column {
                            Text(
                                moon.phaseName,
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                            )
                            Text(
                                String.format(Locale.US, stringResource(R.string.moon_illuminated), moon.illuminationPct),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Text(
                                String.format(Locale.US, stringResource(R.string.moon_age), moon.ageInDays),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        moon.rise?.let {
                            InfoChip(stringResource(R.string.label_moonrise), formatTime(it))
                        }
                        moon.set?.let {
                            InfoChip(stringResource(R.string.label_moonset), formatTime(it))
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Surface(
                        color = if (moon.goodForAstro)
                            Color(0xFF2E7D32).copy(alpha = 0.15f)
                        else
                            Color(0xFFE65100).copy(alpha = 0.15f),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            text = if (moon.goodForAstro)
                                stringResource(R.string.moon_good)
                            else
                                stringResource(R.string.moon_bright),
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.padding(8.dp),
                            color = if (moon.goodForAstro) Color(0xFF2E7D32) else Color(0xFFE65100),
                        )
                    }
                }
            }

            // ── Weather card ─────────────────────────────────────────
            state.weather?.let { weather ->
                DashCard(title = stringResource(R.string.card_weather), icon = Icons.Default.Cloud) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            weatherEmoji(weather.weatherCode),
                            fontSize = 40.sp,
                        )
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text(
                                String.format(Locale.US, "%.1f°C", weather.temperatureC),
                                style = MaterialTheme.typography.headlineSmall,
                                fontWeight = FontWeight.Bold,
                            )
                            Text(
                                weatherDescription(weather.weatherCode),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }

                    Spacer(Modifier.height(8.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                    ) {
                        WeatherStat("☁️", "${weather.cloudCoverPct}%", stringResource(R.string.weather_cloud))
                        WeatherStat("💧", "${weather.humidity}%", stringResource(R.string.weather_humidity))
                        WeatherStat("💨", String.format(Locale.US, "%.0f km/h", weather.windSpeedKmh), stringResource(R.string.weather_wind))
                    }

                    // Cloud cover verdict for astronomy
                    Spacer(Modifier.height(8.dp))
                    Surface(
                        color = when {
                            weather.cloudCoverPct <= 20 -> Color(0xFF2E7D32).copy(alpha = 0.15f)
                            weather.cloudCoverPct <= 50 -> Color(0xFFF9A825).copy(alpha = 0.15f)
                            else -> Color(0xFFE65100).copy(alpha = 0.15f)
                        },
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            text = when {
                                weather.cloudCoverPct <= 20 -> stringResource(R.string.weather_clear)
                                weather.cloudCoverPct <= 50 -> stringResource(R.string.weather_partly_cloudy)
                                else -> stringResource(R.string.weather_too_cloudy)
                            },
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.padding(8.dp),
                            color = when {
                                weather.cloudCoverPct <= 20 -> Color(0xFF2E7D32)
                                weather.cloudCoverPct <= 50 -> Color(0xFFF9A825)
                                else -> Color(0xFFE65100)
                            },
                        )
                    }
                }
            }

            // ── Hourly forecast ──────────────────────────────────────
            state.weather?.hourlyForecast?.takeIf { it.isNotEmpty() }?.let { hours ->
                DashCard(title = stringResource(R.string.card_forecast), icon = Icons.Default.Schedule) {
                    hours.forEach { h ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                formatTime(h.time),
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.width(52.dp),
                                fontWeight = FontWeight.Medium,
                            )
                            Text(
                                weatherEmoji(h.weatherCode),
                                modifier = Modifier.width(28.dp),
                            )
                            // Cloud bar
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .height(8.dp)
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(MaterialTheme.colorScheme.surfaceVariant),
                            ) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxHeight()
                                        .fillMaxWidth(h.cloudCoverPct / 100f)
                                        .clip(RoundedCornerShape(4.dp))
                                        .background(
                                            when {
                                                h.cloudCoverPct <= 20 -> Color(0xFF2E7D32)
                                                h.cloudCoverPct <= 50 -> Color(0xFFF9A825)
                                                else -> Color(0xFFE65100)
                                            }
                                        ),
                                )
                            }
                            Text(
                                "${h.cloudCoverPct}%",
                                style = MaterialTheme.typography.labelSmall,
                                modifier = Modifier.width(36.dp),
                                textAlign = TextAlign.End,
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
        }
    }
}

// ── Reusable components ──────────────────────────────────────────────────────

@Composable
private fun DashCard(
    title: String,
    icon: ImageVector,
    content: @Composable ColumnScope.() -> Unit,
) {
    Text(
        title,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
    )
    Surface(
        shape = RoundedCornerShape(12.dp),
        tonalElevation = 2.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            content = content,
        )
    }
}

@Composable
private fun InfoChip(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun WeatherStat(emoji: String, value: String, label: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(emoji, fontSize = 20.sp)
        Text(value, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun AstroRating(bortleClass: Int) {
    val rating = when {
        bortleClass <= 2 -> "Excellent"
        bortleClass <= 3 -> "Very Good"
        bortleClass <= 4 -> "Good"
        bortleClass <= 5 -> "Fair"
        bortleClass <= 6 -> "Poor"
        else -> "Not Recommended"
    }
    val color = when {
        bortleClass <= 2 -> Color(0xFF2E7D32)
        bortleClass <= 4 -> Color(0xFF558B2F)
        bortleClass <= 5 -> Color(0xFFF9A825)
        bortleClass <= 6 -> Color(0xFFE65100)
        else -> Color(0xFFC62828)
    }
    Surface(
        color = color.copy(alpha = 0.15f),
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            "Astrophotography: $rating",
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium,
            color = color,
            modifier = Modifier.padding(8.dp),
        )
    }
}

private fun formatTime(isoTime: String): String {
    // Handles "2026-04-03T14:00" → "14:00"
    return isoTime.substringAfter("T", isoTime).take(5)
}

private fun abs(d: Double): Double = kotlin.math.abs(d)
