/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Copyright (C) 2026 Pulsar Trigger contributors
 */

package com.ehrocha.pulsar.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ehrocha.pulsar.AppConfig
import com.ehrocha.pulsar.R

import com.ehrocha.pulsar.astro.*
import com.ehrocha.pulsar.planner.PlannerEvent
import com.ehrocha.pulsar.planner.PlannerManager
import com.ehrocha.pulsar.planner.PlannerSession
import kotlinx.coroutines.launch
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionDetailScreen(
    session: PlannerSession,
    event: PlannerEvent,
    plannerManager: PlannerManager,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val dashManager = remember { AstroDashboardManager(context) }
    val state by dashManager.state.collectAsState()
    val scope = rememberCoroutineScope()
    var isRefreshing by remember { mutableStateOf(false) }

    suspend fun doRefresh() {
        isRefreshing = true
        dashManager.refreshForLocation(
            session.latitude,
            session.longitude,
            session.name,
            session.date,
        )
        plannerManager.putCachedDashboard(session.id, dashManager.serializeState())
        isRefreshing = false
    }

    LaunchedEffect(session.id) {
        // Try cached data first
        val cached = plannerManager.getCachedDashboard(session.id)
        if (cached != null && dashManager.restoreState(cached)) {
            return@LaunchedEffect
        }
        doRefresh()
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
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    session.name,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    "${event.name} \u00b7 ${session.date.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.LONG))}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = {
                val geoUri = Uri.parse(
                    "geo:${session.latitude},${session.longitude}?q=${session.latitude},${session.longitude}(${Uri.encode(session.name)})"
                )
                val intent = Intent(Intent.ACTION_VIEW, geoUri)
                context.startActivity(intent)
            }) {
                Icon(Icons.Default.Navigation, contentDescription = stringResource(R.string.btn_navigate))
            }
            IconButton(onClick = {
                scope.launch { doRefresh() }
            }) {
                Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.refresh))
            }
        }

        // ── Last Updated + Pull Latest Data ──────────────────────────
        val lastUpdated = state.lastUpdated
        val isStale = lastUpdated != null && (System.currentTimeMillis() - lastUpdated) > 3_600_000L // > 1 hour
        val hasData = state.location != null

        if (hasData) {
            Surface(
                color = if (isStale) MaterialTheme.colorScheme.errorContainer
                        else MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        if (isStale) Icons.Default.Warning else Icons.Default.Schedule,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = if (isStale) MaterialTheme.colorScheme.onErrorContainer
                               else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.width(8.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = if (lastUpdated != null) {
                                stringResource(R.string.last_updated,
                                    DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
                                        .format(Date(lastUpdated)))
                            } else {
                                stringResource(R.string.assessment_unavailable)
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = if (isStale) MaterialTheme.colorScheme.onErrorContainer
                                    else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (isStale) {
                            Text(
                                stringResource(R.string.data_stale),
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                            )
                        }
                    }
                    FilledTonalButton(
                        onClick = { scope.launch { doRefresh() } },
                        enabled = !isRefreshing,
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                    ) {
                        Icon(
                            Icons.Default.Refresh,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            stringResource(R.string.pull_latest_data),
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
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
                        Icons.Default.ErrorOutline,
                        contentDescription = null,
                        modifier = Modifier.size(48.dp),
                        tint = MaterialTheme.colorScheme.error,
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(state.error!!, textAlign = TextAlign.Center)
                    Spacer(Modifier.height(16.dp))
                    OutlinedButton(onClick = {
                        scope.launch { doRefresh() }
                    }) {
                        Text(stringResource(R.string.retry))
                    }
                }
            }
            return
        }

        PullToRefreshBox(
            isRefreshing = isRefreshing,
            onRefresh = { scope.launch { doRefresh() } },
            modifier = Modifier.fillMaxSize(),
        ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // ── Summary card ──────────────────────────────────────
            state.location?.let { loc ->
                DashCard(title = stringResource(R.string.card_summary), icon = Icons.Default.MyLocation) {
                    loc.cityName?.let { city ->
                        Text(
                            city,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                    Text(
                        String.format(Locale.US, "%.4f° %s, %.4f° %s",
                            abs(loc.latitude),
                            if (loc.latitude >= 0) stringResource(R.string.location_north) else stringResource(R.string.location_south),
                            abs(loc.longitude),
                            if (loc.longitude >= 0) stringResource(R.string.location_east) else stringResource(R.string.location_west),
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    Spacer(Modifier.height(12.dp))

                    @Composable
                    fun VerdictRow(emoji: String, label: String, good: Boolean, extra: String? = null) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Surface(
                                color = if (good) com.ehrocha.pulsar.ui.theme.PulsarTheme.colors.positive.copy(alpha = 0.12f)
                                        else com.ehrocha.pulsar.ui.theme.PulsarTheme.colors.negative.copy(alpha = 0.12f),
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.weight(1f),
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                                ) {
                                    Text(emoji, fontSize = 14.sp)
                                    Text(
                                        label,
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Medium,
                                        color = if (good) com.ehrocha.pulsar.ui.theme.PulsarTheme.colors.positive else com.ehrocha.pulsar.ui.theme.PulsarTheme.colors.negative,
                                    )
                                }
                            }
                            if (extra != null) {
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    extra,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }

                    @Composable
                    fun UnknownVerdictRow(emoji: String) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Surface(
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.weight(1f),
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                                ) {
                                    Text(emoji, fontSize = 14.sp, color = Color.Gray)
                                    Text(
                                        stringResource(R.string.verdict_unknown),
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Medium,
                                        color = Color.Gray,
                                    )
                                }
                            }
                        }
                    }

                    // Moon
                    state.moon?.let { moon ->
                        VerdictRow(
                            moon.emoji,
                            if (moon.goodForAstro) stringResource(R.string.verdict_moon_good)
                            else stringResource(R.string.verdict_moon_bright),
                            moon.goodForAstro,
                        )
                    } ?: if (!state.loading) UnknownVerdictRow("🌑") else Unit

                    // Weather
                    state.weather?.let { weather ->
                        val hasRain = weather.precipitationMm > 0.1
                        val good = weather.cloudCoverPct <= AppConfig.CLOUD_COVER_CLEAR_THRESHOLD && !hasRain
                        VerdictRow(
                            weatherEmoji(weather.weatherCode),
                            when {
                                hasRain -> stringResource(R.string.verdict_rain)
                                weather.cloudCoverPct <= AppConfig.CLOUD_COVER_CLEAR_THRESHOLD -> stringResource(R.string.verdict_clear)
                                weather.cloudCoverPct <= AppConfig.CLOUD_COVER_PARTLY_THRESHOLD -> stringResource(R.string.verdict_partly)
                                else -> stringResource(R.string.verdict_cloudy)
                            },
                            good,
                        )
                    } ?: if (!state.loading) UnknownVerdictRow("☁️") else Unit

                    // Milky Way
                    state.milkyWay?.let { mw ->
                        VerdictRow(
                            "🌌",
                            if (mw.visible) stringResource(R.string.verdict_mw_visible)
                            else stringResource(R.string.verdict_mw_not_visible),
                            mw.visible,
                        )
                    } ?: if (!state.loading) UnknownVerdictRow("🌌") else Unit

                    // Bortle
                    state.bortle?.let { b ->
                        val bInt = b.bortleClass.toInt().coerceIn(1, 9)
                        VerdictRow(
                            "💡",
                            stringResource(R.string.verdict_bortle, bInt),
                            bInt <= 4,
                        )
                    } ?: if (!state.loading) UnknownVerdictRow("💡") else Unit

                    // Best photo windows
                    if (state.bestWindows.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            stringResource(R.string.card_best_windows),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Bold,
                        )
                        state.bestWindows.forEach { w ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    when (w.rating) { 3 -> "⭐"; 2 -> "👍"; else -> "👌" },
                                    fontSize = 16.sp,
                                    modifier = Modifier.width(28.dp),
                                )
                                Text(
                                    "${w.startTime} – ${w.endTime}",
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.weight(1f),
                                )
                                Surface(
                                    color = when (w.rating) {
                                        3 -> com.ehrocha.pulsar.ui.theme.PulsarTheme.colors.positive; 2 -> com.ehrocha.pulsar.ui.theme.PulsarTheme.colors.positiveMuted; else -> com.ehrocha.pulsar.ui.theme.PulsarTheme.colors.caution
                                    }.copy(alpha = 0.15f),
                                    shape = RoundedCornerShape(8.dp),
                                ) {
                                    Text(
                                        text = when (w.rating) {
                                            3 -> stringResource(R.string.window_excellent)
                                            2 -> stringResource(R.string.window_good)
                                            else -> stringResource(R.string.window_fair)
                                        },
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                        color = when (w.rating) {
                                            3 -> com.ehrocha.pulsar.ui.theme.PulsarTheme.colors.positive; 2 -> com.ehrocha.pulsar.ui.theme.PulsarTheme.colors.positiveMuted; else -> com.ehrocha.pulsar.ui.theme.PulsarTheme.colors.caution
                                        },
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // ── Per-card error chips ────────────────────────────────
            val errors = listOfNotNull(
                state.weatherError?.let { "☁️ $it" },
                state.bortleError?.let { "💡 $it" },
            )
            if (errors.isNotEmpty()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    errors.forEach { msg ->
                        Surface(
                            color = com.ehrocha.pulsar.ui.theme.PulsarTheme.colors.negative.copy(alpha = 0.12f),
                            shape = RoundedCornerShape(8.dp),
                        ) {
                            Text(
                                msg,
                                style = MaterialTheme.typography.labelSmall,
                                color = com.ehrocha.pulsar.ui.theme.PulsarTheme.colors.negative,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            )
                        }
                    }
                }
            }

            // ── Sun card ─────────────────────────────────────────────
            state.sun?.let { sun ->
                DashCard(title = stringResource(R.string.card_sun), icon = Icons.Default.WbSunny) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                    ) {
                        sun.sunrise?.let {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("🌅", fontSize = 32.sp)
                                Text(formatTime(it), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                Text(stringResource(R.string.label_sunrise), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        sun.sunset?.let {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("🌇", fontSize = 32.sp)
                                Text(formatTime(it), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                Text(stringResource(R.string.label_sunset), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
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
                        moon.rise?.let { InfoChip(stringResource(R.string.label_moonrise), formatTime(it)) }
                        moon.set?.let { InfoChip(stringResource(R.string.label_moonset), formatTime(it)) }
                    }
                    Spacer(Modifier.height(8.dp))
                    Surface(
                        color = if (moon.goodForAstro)
                            com.ehrocha.pulsar.ui.theme.PulsarTheme.colors.positive.copy(alpha = 0.15f)
                        else
                            com.ehrocha.pulsar.ui.theme.PulsarTheme.colors.negative.copy(alpha = 0.15f),
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
                            color = if (moon.goodForAstro) com.ehrocha.pulsar.ui.theme.PulsarTheme.colors.positive else com.ehrocha.pulsar.ui.theme.PulsarTheme.colors.negative,
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
                        Text(weatherEmoji(weather.weatherCode), fontSize = 40.sp)
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
                        WeatherStat("🌧️", String.format(Locale.US, "%.1f mm", weather.precipitationMm), stringResource(R.string.weather_rain))
                        WeatherStat("💧", "${weather.humidity}%", stringResource(R.string.weather_humidity))
                        WeatherStat("💨", String.format(Locale.US, "%.0f km/h", weather.windSpeedKmh), stringResource(R.string.weather_wind))
                    }
                }
            }

            // ── Twilight timeline card ───────────────────────────────
            state.twilight?.let { tw ->
                DashCard(
                    title = stringResource(R.string.card_twilight),
                    icon = Icons.Default.Gradient,
                ) {
                    val phases = listOfNotNull(
                        tw.civilEnd?.let { stringResource(R.string.tw_civil_end) to it },
                        tw.nauticalEnd?.let { stringResource(R.string.tw_nautical_end) to it },
                        tw.astroEnd?.let { stringResource(R.string.tw_astro_end) to it },
                        tw.astroStart?.let { stringResource(R.string.tw_astro_start) to it },
                        tw.nauticalStart?.let { stringResource(R.string.tw_nautical_start) to it },
                        tw.civilStart?.let { stringResource(R.string.tw_civil_start) to it },
                    )
                    val colors = listOf(
                        com.ehrocha.pulsar.ui.theme.PulsarTheme.colors.skyTwilight,
                        com.ehrocha.pulsar.ui.theme.PulsarTheme.colors.skyNautical,
                        com.ehrocha.pulsar.ui.theme.PulsarTheme.colors.skyDark,
                        com.ehrocha.pulsar.ui.theme.PulsarTheme.colors.skyNautical,
                        com.ehrocha.pulsar.ui.theme.PulsarTheme.colors.skyTwilight,
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(12.dp)
                            .clip(RoundedCornerShape(8.dp)),
                    ) {
                        colors.forEach { c ->
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxHeight()
                                    .background(c),
                            )
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    phases.forEach { (label, time) ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 2.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(
                                label,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                formatTime(time),
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Medium,
                            )
                        }
                    }
                }
            }

            // ── Planets card ─────────────────────────────────────────
            if (state.planets.isNotEmpty()) {
                DashCard(
                    title = stringResource(R.string.card_planets),
                    icon = Icons.Default.Public,
                ) {
                    state.planets.forEach { planet ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(planet.emoji, fontSize = 20.sp, modifier = Modifier.width(32.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    planet.name,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Bold,
                                )
                                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    planet.rise?.let {
                                        Text(
                                            "↑${formatTime(it)}",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                    planet.set?.let {
                                        Text(
                                            "↓${formatTime(it)}",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                            }
                            Text(
                                String.format(Locale.US, "%.0f°", planet.altitude),
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Medium,
                            )
                            Spacer(Modifier.width(4.dp))
                            Surface(
                                color = if (planet.visible) com.ehrocha.pulsar.ui.theme.PulsarTheme.colors.positive.copy(alpha = 0.15f)
                                        else com.ehrocha.pulsar.ui.theme.PulsarTheme.colors.negative.copy(alpha = 0.15f),
                                shape = RoundedCornerShape(8.dp),
                            ) {
                                Text(
                                    if (planet.visible) stringResource(R.string.planet_visible)
                                    else stringResource(R.string.planet_low),
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = if (planet.visible) com.ehrocha.pulsar.ui.theme.PulsarTheme.colors.positive else com.ehrocha.pulsar.ui.theme.PulsarTheme.colors.negative,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                )
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
        }
        } // PullToRefreshBox
    }
}
