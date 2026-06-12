/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Copyright (C) 2026 Pulsar Trigger contributors
 */

package com.ehrocha.pulsar.widget

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.ColorFilter
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.lazy.LazyColumn
import androidx.glance.appwidget.lazy.items
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.ehrocha.pulsar.AppConfig
import com.ehrocha.pulsar.MainActivity
import com.ehrocha.pulsar.R
import com.ehrocha.pulsar.astro.AstroDashboardManager
import com.ehrocha.pulsar.astro.DashboardState
import com.ehrocha.pulsar.astro.LocationInfo
import com.ehrocha.pulsar.astro.MoonInfo
import com.ehrocha.pulsar.astro.PhotoWindow
import com.ehrocha.pulsar.astro.WeatherInfo
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs

// Verdict palette — matches DashboardScreen.kt's VerdictRow exactly.
// SIGNAL: the widget follows the app's own schemes (Carbon when the
// system is dark, Chart Paper in light) instead of Material You dynamic
// color — it sits on the home screen next to the pulsar icon and should
// match it. Glance can't load bundled fonts, so type stays system.
private val PulsarWidgetColors = androidx.glance.material3.ColorProviders(
    light = com.ehrocha.pulsar.ui.theme.LightColorScheme,
    dark = com.ehrocha.pulsar.ui.theme.DarkColorScheme,
)

private val VERDICT_GOOD = Color(0xFF2E7D32)
private val VERDICT_BAD = Color(0xFFE65100)
private val WINDOW_EXCELLENT = Color(0xFF2E7D32)
private val WINDOW_GOOD = Color(0xFF558B2F)
private val WINDOW_FAIR = Color(0xFFF9A825)

// Glance's RemoteViews-backed Text path can silently drop fractional sp
// (e.g. 15.6.sp from `15 * 1.2`), so we round to integer sp before handing
// the value over. Same for the emoji-column widths in dp.
private fun scaledSp(base: Int, scale: Float) =
    (base * scale).toInt().coerceAtLeast(1).sp
private fun scaledDp(base: Int, scale: Float) =
    (base * scale).toInt().coerceAtLeast(1).dp

class DashboardWidget : GlanceAppWidget() {

    override val sizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val snapshot = DashboardSnapshotStore.load(context)
        val state: DashboardState? = snapshot?.let { snap ->
            val m = AstroDashboardManager(context)
            if (m.restoreState(snap.json)) m.state.value else null
        }
        val bgAlpha = DashboardSnapshotStore.backgroundAlpha(context)
        val textScale = DashboardSnapshotStore.textScale(context)
        provideContent {
            GlanceTheme(colors = PulsarWidgetColors) {
                if (state == null) {
                    EmptyState(bgAlpha, textScale)
                } else {
                    SummaryCard(state, snapshot.updatedAtMs, bgAlpha, textScale)
                }
            }
        }
    }
}

@Composable
private fun widgetBgWithAlpha(alpha: Float): ColorProvider {
    val ctx = androidx.glance.LocalContext.current
    val base = GlanceTheme.colors.widgetBackground.getColor(ctx)
    return ColorProvider(base.copy(alpha = alpha))
}

class DashboardWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = DashboardWidget()
}

private fun openAppIntent(context: Context): Intent =
    Intent(context, MainActivity::class.java).apply {
        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        putExtra(MainActivity.EXTRA_OPEN_DEST, MainActivity.DEST_DASHBOARD)
    }

private fun isStale(updatedAtMs: Long): Boolean =
    updatedAtMs > 0 && System.currentTimeMillis() - updatedAtMs > 12L * 3600_000L

@Composable
private fun EmptyState(bgAlpha: Float, scale: Float) {
    val ctx = androidx.glance.LocalContext.current
    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(widgetBgWithAlpha(bgAlpha))
            .padding(14.dp)
            .clickable(actionStartActivity(openAppIntent(ctx))),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            ctx.getString(R.string.widget_empty_state_title),
            style = TextStyle(
                color = GlanceTheme.colors.onSurface,
                fontWeight = FontWeight.Bold,
                fontSize = scaledSp(16, scale),
            ),
        )
        Spacer(GlanceModifier.height(4.dp))
        Text(
            ctx.getString(R.string.widget_empty_state),
            style = TextStyle(
                color = GlanceTheme.colors.onSurfaceVariant,
                fontSize = scaledSp(14, scale),
            ),
        )
    }
}

/**
 * Mirrors the Summary card on the in-app Dashboard tab:
 * city + lat/lon header, then verdict chips for Sun / Moon / Weather /
 * Milky Way / Bortle (green for good, orange for bad), rise/set times,
 * and the best photo windows of the night with rating chips.
 *
 * Lives in a [LazyColumn] so the widget content scrolls when the user
 * resizes it small enough that everything doesn't fit.
 */
@Composable
private fun SummaryCard(state: DashboardState, updatedAtMs: Long, bgAlpha: Float, scale: Float) {
    val ctx = androidx.glance.LocalContext.current
    LazyColumn(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(widgetBgWithAlpha(bgAlpha))
            .padding(14.dp)
            .clickable(actionStartActivity(openAppIntent(ctx))),
    ) {
        item { CardHeader(state, updatedAtMs, scale) }
        item { Spacer(GlanceModifier.height(10.dp)) }
        state.sun?.let {
            item { VerdictRow("☀️", ctx.getString(R.string.widget_verdict_sun), true, scale) }
        }
        state.moon?.let { moon -> item { MoonVerdict(moon, scale) } }
        state.weather?.let { w -> item { WeatherVerdict(w, scale) } }
        state.milkyWay?.let { mw ->
            item {
                VerdictRow(
                    "🌌",
                    ctx.getString(if (mw.visible) R.string.verdict_mw_visible
                                  else R.string.verdict_mw_not_visible),
                    mw.visible,
                    scale,
                )
            }
        }
        state.bortle?.let { b ->
            val bInt = b.bortleClass.toInt().coerceIn(1, 9)
            item { VerdictRow("💡", ctx.getString(R.string.verdict_bortle, bInt), bInt <= 4, scale) }
        }

        val riseSetRows = buildRiseSetRows(state)
        if (riseSetRows.isNotEmpty()) {
            item { Spacer(GlanceModifier.height(10.dp)) }
            item { SectionLabel(ctx.getString(R.string.label_rise_set), scale) }
            items(riseSetRows) { (emoji, times) -> RiseSetRow(emoji, times, scale) }
        }

        if (state.bestWindows.isNotEmpty()) {
            item { Spacer(GlanceModifier.height(10.dp)) }
            item { SectionLabel(ctx.getString(R.string.card_best_windows), scale) }
            items(state.bestWindows) { w -> WindowRow(w, scale) }
        }
    }
}

@Composable
private fun CardHeader(state: DashboardState, updatedAtMs: Long, scale: Float) {
    val ctx = androidx.glance.LocalContext.current
    val stale = isStale(updatedAtMs)
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
                    fontSize = scaledSp(19, scale),
                ),
                maxLines = 1,
            )
            state.location?.let { loc ->
                Text(
                    formatCoords(loc),
                    style = TextStyle(
                        color = GlanceTheme.colors.onSurfaceVariant,
                        fontSize = scaledSp(14, scale),
                    ),
                    maxLines = 1,
                )
            }
            val prefix = ctx.getString(
                if (stale) R.string.widget_stale_prefix else R.string.widget_updated_prefix
            )
            Text(
                "$prefix ${formatTime(updatedAtMs)}",
                style = TextStyle(
                    color = if (stale) GlanceTheme.colors.error
                    else GlanceTheme.colors.onSurfaceVariant,
                    fontSize = scaledSp(13, scale),
                    fontWeight = if (stale) FontWeight.Medium else FontWeight.Normal,
                ),
            )
        }
        RefreshButton()
    }
}

@Composable
private fun MoonVerdict(moon: MoonInfo, scale: Float) {
    val ctx = androidx.glance.LocalContext.current
    VerdictRow(
        moon.emoji,
        ctx.getString(if (moon.goodForAstro) R.string.verdict_moon_good
                      else R.string.verdict_moon_bright),
        moon.goodForAstro,
        scale,
    )
}

@Composable
private fun WeatherVerdict(weather: WeatherInfo, scale: Float) {
    val ctx = androidx.glance.LocalContext.current
    val hasRain = weather.precipitationMm > 0.1
    val good = weather.cloudCoverPct <= AppConfig.CLOUD_COVER_CLEAR_THRESHOLD && !hasRain
    val labelRes = when {
        hasRain -> R.string.verdict_rain
        weather.cloudCoverPct <= AppConfig.CLOUD_COVER_CLEAR_THRESHOLD -> R.string.verdict_clear
        weather.cloudCoverPct <= AppConfig.CLOUD_COVER_PARTLY_THRESHOLD -> R.string.verdict_partly
        else -> R.string.verdict_cloudy
    }
    VerdictRow(weatherEmoji(weather.weatherCode), ctx.getString(labelRes), good, scale)
}

/** One verdict chip — emoji + label inside a soft-tinted rounded surface.
 *  Green tint for "good" conditions, orange for "bad". Matches
 *  [com.ehrocha.pulsar.ui.screens.DashboardScreen]'s `VerdictRow`. */
@Composable
private fun VerdictRow(emoji: String, label: String, good: Boolean, scale: Float) {
    val accent = if (good) VERDICT_GOOD else VERDICT_BAD
    val bg = accent.copy(alpha = 0.14f)
    Row(
        modifier = GlanceModifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = GlanceModifier
                .defaultWeight()
                .background(bg)
                .cornerRadius(10.dp)
                .padding(horizontal = 10.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(emoji, style = TextStyle(fontSize = scaledSp(17, scale)))
            Spacer(GlanceModifier.width(6.dp))
            Text(
                label,
                style = TextStyle(
                    color = ColorProvider(accent),
                    fontWeight = FontWeight.Medium,
                    fontSize = scaledSp(15, scale),
                ),
            )
        }
    }
}

@Composable
private fun SectionLabel(text: String, scale: Float) {
    Text(
        text,
        style = TextStyle(
            color = GlanceTheme.colors.primary,
            fontWeight = FontWeight.Bold,
            fontSize = scaledSp(14, scale),
        ),
    )
}

@Composable
private fun RiseSetRow(emoji: String, times: String, scale: Float) {
    Row(
        modifier = GlanceModifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            emoji,
            style = TextStyle(fontSize = scaledSp(17, scale)),
            modifier = GlanceModifier.width(scaledDp(28, scale)),
        )
        Text(
            times,
            style = TextStyle(
                color = GlanceTheme.colors.onSurfaceVariant,
                fontSize = scaledSp(15, scale),
            ),
        )
    }
}

@Composable
private fun WindowRow(w: PhotoWindow, scale: Float) {
    val ctx = androidx.glance.LocalContext.current
    val (chipColor, chipLabelRes) = when (w.rating) {
        3 -> WINDOW_EXCELLENT to R.string.window_excellent
        2 -> WINDOW_GOOD to R.string.window_good
        else -> WINDOW_FAIR to R.string.window_fair
    }
    val chipLabel = ctx.getString(chipLabelRes)
    val chipMark = when (w.rating) { 3 -> "⭐"; 2 -> "👍"; else -> "👌" }
    Row(
        modifier = GlanceModifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            chipMark,
            style = TextStyle(fontSize = scaledSp(18, scale)),
            modifier = GlanceModifier.width(scaledDp(30, scale)),
        )
        Text(
            "${w.startTime} – ${w.endTime}",
            style = TextStyle(
                color = GlanceTheme.colors.onSurface,
                fontWeight = FontWeight.Bold,
                fontSize = scaledSp(15, scale),
            ),
            modifier = GlanceModifier.defaultWeight(),
        )
        Row(
            modifier = GlanceModifier
                .background(chipColor.copy(alpha = 0.17f))
                .cornerRadius(10.dp)
                .padding(horizontal = 8.dp, vertical = 4.dp),
        ) {
            Text(
                chipLabel,
                style = TextStyle(
                    color = ColorProvider(chipColor),
                    fontWeight = FontWeight.Bold,
                    fontSize = scaledSp(13, scale),
                ),
            )
        }
    }
}

@Composable
private fun RefreshButton() {
    val ctx = androidx.glance.LocalContext.current
    Image(
        provider = ImageProvider(android.R.drawable.ic_popup_sync),
        contentDescription = ctx.getString(R.string.widget_refresh_content_description),
        modifier = GlanceModifier
            .size(22.dp)
            .clickable(actionRunCallback<DashboardRefreshAction>()),
        colorFilter = ColorFilter.tint(GlanceTheme.colors.primary),
    )
}

// ── Helpers ────────────────────────────────────────────────────────────

private fun buildRiseSetRows(state: DashboardState): List<Pair<String, String>> = buildList {
    state.sun?.let { sun ->
        val t = listOfNotNull(
            sun.sunrise?.let { "↑${stripSeconds(it)}" },
            sun.sunset?.let { "↓${stripSeconds(it)}" },
        ).joinToString("  ")
        if (t.isNotEmpty()) add("☀️" to t)
    }
    state.moon?.let { moon ->
        val t = listOfNotNull(
            moon.rise?.let { "↑${stripSeconds(it)}" },
            moon.set?.let { "↓${stripSeconds(it)}" },
        ).joinToString("  ")
        if (t.isNotEmpty()) add(moon.emoji to t)
    }
    state.milkyWay?.let { mw ->
        val t = listOfNotNull(
            mw.coreRise?.let { "↑$it" },
            mw.coreSet?.let { "↓$it" },
        ).joinToString("  ")
        if (t.isNotEmpty()) add("🌌" to t)
    }
}

/** Strip seconds off an "HH:mm:ss" timestamp so the row fits in tight widget cells. */
private fun stripSeconds(s: String): String =
    if (s.count { it == ':' } >= 2) s.substringBeforeLast(':') else s

private fun formatCoords(loc: LocationInfo): String = String.format(
    Locale.US, "%.4f° %s, %.4f° %s",
    abs(loc.latitude), if (loc.latitude >= 0) "N" else "S",
    abs(loc.longitude), if (loc.longitude >= 0) "E" else "W",
)

private fun formatTime(epochMs: Long): String {
    if (epochMs <= 0) return "—"
    val t = Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault())
    return DateTimeFormatter.ofPattern("HH:mm", Locale.getDefault()).format(t)
}

/** Map Open-Meteo weather codes to a small set of emoji. Subset of the
 *  in-app mapping — covers the categories the verdict logic uses. */
private fun weatherEmoji(code: Int): String = when (code) {
    in 0..1 -> "☀️"
    in 2..3 -> "⛅"
    in 45..48 -> "🌫️"
    in 51..67 -> "🌦️"
    in 71..77 -> "❄️"
    in 80..82 -> "🌧️"
    in 95..99 -> "⛈️"
    else -> "☁️"
}
