/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Copyright (C) 2026 Pulsar Trigger contributors
 */

package com.ehrocha.pulsar.widget

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.lazy.LazyColumn
import androidx.glance.appwidget.lazy.items
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import com.ehrocha.pulsar.MainActivity
import com.ehrocha.pulsar.astro.AstroDashboardManager
import com.ehrocha.pulsar.astro.DashboardState
import com.ehrocha.pulsar.astro.DewRisk
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

class DashboardWidget : GlanceAppWidget() {

    override val sizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val snapshot = DashboardSnapshotStore.load(context)
        val state: DashboardState? = snapshot?.let { snap ->
            val m = AstroDashboardManager(context)
            if (m.restoreState(snap.json)) m.state.value else null
        }
        provideContent {
            GlanceTheme {
                if (state == null) {
                    EmptyState()
                } else {
                    DashboardContent(state, snapshot.updatedAtMs)
                }
            }
        }
    }
}

class DashboardWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = DashboardWidget()
}

private fun openAppIntent(context: Context): Intent =
    Intent(context, MainActivity::class.java).apply {
        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        putExtra(MainActivity.EXTRA_OPEN_TAB, MainActivity.TAB_DASHBOARD)
    }

@Composable
private fun EmptyState() {
    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(GlanceTheme.colors.widgetBackground)
            .padding(12.dp)
            .clickable(actionStartActivity(openAppIntent(LocalContextOrNull()))),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "Pulsar — Astro dashboard",
            style = TextStyle(
                color = GlanceTheme.colors.onSurface,
                fontWeight = FontWeight.Bold,
                fontSize = 13.sp,
            ),
        )
        Spacer(GlanceModifier.height(4.dp))
        Text(
            "Open the app to populate the widget",
            style = TextStyle(
                color = GlanceTheme.colors.onSurfaceVariant,
                fontSize = 11.sp,
            ),
        )
    }
}

@Composable
private fun DashboardContent(state: DashboardState, updatedAtMs: Long) {
    val ctx = LocalContextOrNull()
    LazyColumn(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(GlanceTheme.colors.widgetBackground)
            .padding(10.dp)
            .clickable(actionStartActivity(openAppIntent(ctx))),
    ) {
        item { Header(state, updatedAtMs) }
        item { SectionSpacer() }
        state.moon?.let { item { MoonSection(it) } }
        if (state.sun != null || state.twilight != null) {
            item { SectionSpacer() }
            item { SunTwilightSection(state) }
        }
        state.weather?.let {
            item { SectionSpacer() }
            item { WeatherSection(it) }
        }
        if (state.bortle != null || state.dewPoint != null) {
            item { SectionSpacer() }
            item { SkyAndDewSection(state) }
        }
        state.milkyWay?.takeIf { it.visible }?.let {
            item { SectionSpacer() }
            item { MilkyWaySection(it) }
        }
        if (state.bestWindows.isNotEmpty()) {
            item { SectionSpacer() }
            item { WindowsHeader() }
            items(state.bestWindows.take(3)) { w -> WindowRow(w) }
        }
        val visiblePlanets = state.planets.filter { it.visible }
        if (visiblePlanets.isNotEmpty()) {
            item { SectionSpacer() }
            item { PlanetsHeader() }
            items(visiblePlanets) { p -> PlanetRow(p) }
        }
    }
}

@Composable
private fun Header(state: DashboardState, updatedAtMs: Long) {
    Row(
        modifier = GlanceModifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = GlanceModifier.defaultWeight()) {
            Text(
                state.location?.cityName ?: "—",
                style = TextStyle(
                    color = GlanceTheme.colors.onSurface,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                ),
                maxLines = 1,
            )
            Text(
                "Updated ${formatTime(updatedAtMs)}",
                style = TextStyle(
                    color = GlanceTheme.colors.onSurfaceVariant,
                    fontSize = 10.sp,
                ),
            )
        }
        if (state.weather != null) {
            Text(
                "${state.weather.temperatureC.toInt()}°C  ${state.weather.cloudCoverPct}%☁",
                style = TextStyle(
                    color = GlanceTheme.colors.onSurface,
                    fontSize = 12.sp,
                ),
            )
        }
    }
}

@Composable
private fun MoonSection(moon: com.ehrocha.pulsar.astro.MoonInfo) {
    Row(
        modifier = GlanceModifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            moon.emoji,
            style = TextStyle(fontSize = 22.sp),
        )
        Spacer(GlanceModifier.width(6.dp))
        Column(modifier = GlanceModifier.defaultWeight()) {
            Text(
                "${moon.phaseName} · ${moon.illuminationPct.toInt()}%",
                style = TextStyle(
                    color = GlanceTheme.colors.onSurface,
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp,
                ),
            )
            Text(
                buildString {
                    moon.rise?.let { append("↑ $it") }
                    if (moon.rise != null && moon.set != null) append("   ")
                    moon.set?.let { append("↓ $it") }
                },
                style = TextStyle(
                    color = GlanceTheme.colors.onSurfaceVariant,
                    fontSize = 11.sp,
                ),
            )
        }
        Text(
            if (moon.goodForAstro) "✓ astro" else "—",
            style = TextStyle(
                color = if (moon.goodForAstro)
                    GlanceTheme.colors.primary
                else GlanceTheme.colors.onSurfaceVariant,
                fontSize = 10.sp,
            ),
        )
    }
}

@Composable
private fun SunTwilightSection(state: DashboardState) {
    Column(modifier = GlanceModifier.fillMaxWidth()) {
        state.sun?.let { sun ->
            Row(modifier = GlanceModifier.fillMaxWidth()) {
                Text(
                    "☀ ${sun.sunrise ?: "—"} / ${sun.sunset ?: "—"}",
                    style = TextStyle(
                        color = GlanceTheme.colors.onSurface,
                        fontSize = 11.sp,
                    ),
                )
            }
        }
        state.twilight?.let { tw ->
            Spacer(GlanceModifier.height(2.dp))
            Text(
                "Astro twilight: ${tw.astroEnd ?: "—"} → ${tw.astroStart ?: "—"}",
                style = TextStyle(
                    color = GlanceTheme.colors.onSurfaceVariant,
                    fontSize = 10.sp,
                ),
            )
            tw.nauticalEnd?.let { ne ->
                Text(
                    "Nautical: $ne → ${tw.nauticalStart ?: "—"}    Civil: ${tw.civilEnd ?: "—"} → ${tw.civilStart ?: "—"}",
                    style = TextStyle(
                        color = GlanceTheme.colors.onSurfaceVariant,
                        fontSize = 10.sp,
                    ),
                )
            }
        }
    }
}

@Composable
private fun WeatherSection(w: com.ehrocha.pulsar.astro.WeatherInfo) {
    Column(modifier = GlanceModifier.fillMaxWidth()) {
        Text(
            "Weather",
            style = TextStyle(
                color = GlanceTheme.colors.primary,
                fontWeight = FontWeight.Bold,
                fontSize = 11.sp,
            ),
        )
        Spacer(GlanceModifier.height(2.dp))
        Text(
            "${w.temperatureC.toInt()}°C   ☁ ${w.cloudCoverPct}%   " +
                "💧 ${w.humidity}%   🌧 ${"%.1f".format(w.precipitationMm)} mm   " +
                "💨 ${w.windSpeedKmh.toInt()} km/h",
            style = TextStyle(
                color = GlanceTheme.colors.onSurface,
                fontSize = 11.sp,
            ),
        )
    }
}

@Composable
private fun SkyAndDewSection(state: DashboardState) {
    Column(modifier = GlanceModifier.fillMaxWidth()) {
        state.bortle?.let { b ->
            Text(
                "Sky: Bortle ${"%.1f".format(b.bortleClass)} · ${b.category}",
                style = TextStyle(
                    color = GlanceTheme.colors.onSurface,
                    fontSize = 11.sp,
                ),
            )
            Text(
                "Milky Way: ${b.milkyWayQuality}",
                style = TextStyle(
                    color = GlanceTheme.colors.onSurfaceVariant,
                    fontSize = 10.sp,
                ),
            )
        }
        state.dewPoint?.let { d ->
            Spacer(GlanceModifier.height(2.dp))
            val tint = when (d.risk) {
                DewRisk.CRITICAL -> GlanceTheme.colors.error
                DewRisk.WARNING -> GlanceTheme.colors.tertiary
                DewRisk.NONE -> GlanceTheme.colors.onSurface
            }
            Text(
                "Dew: ${"%.1f".format(d.dewPointC)}°C  Δ ${"%.1f".format(d.spreadC)}°C · ${d.risk.name}",
                style = TextStyle(color = tint, fontSize = 11.sp),
            )
        }
    }
}

@Composable
private fun MilkyWaySection(m: com.ehrocha.pulsar.astro.MilkyWayInfo) {
    Column(modifier = GlanceModifier.fillMaxWidth()) {
        Text(
            if (m.seasonBest) "Milky Way · in season" else "Milky Way",
            style = TextStyle(
                color = GlanceTheme.colors.primary,
                fontWeight = FontWeight.Bold,
                fontSize = 11.sp,
            ),
        )
        if (m.coreRise != null || m.coreSet != null) {
            Text(
                "Core: ${m.coreRise ?: "—"} → ${m.coreSet ?: "—"}",
                style = TextStyle(
                    color = GlanceTheme.colors.onSurface,
                    fontSize = 11.sp,
                ),
            )
        }
        m.darkWindow?.let {
            Text(
                "Dark window: $it",
                style = TextStyle(
                    color = GlanceTheme.colors.onSurfaceVariant,
                    fontSize = 10.sp,
                ),
            )
        }
    }
}

@Composable
private fun WindowsHeader() {
    Text(
        "Best photo windows",
        style = TextStyle(
            color = GlanceTheme.colors.primary,
            fontWeight = FontWeight.Bold,
            fontSize = 11.sp,
        ),
    )
}

@Composable
private fun WindowRow(w: com.ehrocha.pulsar.astro.PhotoWindow) {
    val ratingMark = when (w.rating) { 3 -> "★★★"; 2 -> "★★"; else -> "★" }
    Text(
        "$ratingMark  ${w.startTime}–${w.endTime} · ${w.hours}h · ☁ ${w.avgCloudPct}%",
        style = TextStyle(
            color = GlanceTheme.colors.onSurface,
            fontSize = 11.sp,
        ),
    )
}

@Composable
private fun PlanetsHeader() {
    Text(
        "Planets tonight",
        style = TextStyle(
            color = GlanceTheme.colors.primary,
            fontWeight = FontWeight.Bold,
            fontSize = 11.sp,
        ),
    )
}

@Composable
private fun PlanetRow(p: com.ehrocha.pulsar.astro.PlanetInfo) {
    Text(
        "${p.emoji} ${p.name}  · max alt ${p.altitude.toInt()}°" +
            (p.rise?.let { "  ↑ $it" } ?: "") +
            (p.set?.let { "  ↓ $it" } ?: ""),
        style = TextStyle(
            color = GlanceTheme.colors.onSurface,
            fontSize = 11.sp,
        ),
    )
}

@Composable
private fun SectionSpacer() {
    Spacer(GlanceModifier.height(6.dp))
}

@Composable
private fun LocalContextOrNull(): Context {
    return androidx.glance.LocalContext.current
}

private fun formatTime(epochMs: Long): String {
    if (epochMs <= 0) return "—"
    val t = Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault())
    return DateTimeFormatter.ofPattern("HH:mm", Locale.getDefault()).format(t)
}
