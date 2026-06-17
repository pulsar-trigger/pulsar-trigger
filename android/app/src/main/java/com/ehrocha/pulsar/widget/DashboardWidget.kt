/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Copyright (C) 2026 Pulsar Trigger contributors
 */

package com.ehrocha.pulsar.widget

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
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
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxHeight
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
import com.ehrocha.pulsar.astro.NightModel
import com.ehrocha.pulsar.astro.NightSample
import com.ehrocha.pulsar.astro.NightVerdict
import com.ehrocha.pulsar.astro.PhotoWindow
import com.ehrocha.pulsar.astro.WeatherInfo
import com.ehrocha.pulsar.astro.buildNightModel
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

// ── Sky Dial widget palette — the live night bar + readouts ──────────────────
// The dial's arc can't be drawn in Glance (no canvas), so it's reinterpreted
// as a horizontal bar of real coloured Boxes — live views, not a bitmap.
private val BAR_DIM = Color(0xFF3A3D44)      // low quality (near dusk/dawn)
private val BAR_BRIGHT = Color(0xFF4ADE80)   // high quality (deep dark)
private val BAR_BEST = Color(0xFFFF4FA3)     // best window (live magenta)
private val BAR_NOW = Color(0xFFE8EAED)      // the live "now" marker
private val BAR_GOOD = Color(0xFF36D399)     // readout / verdict: good
private val BAR_AMBER = Color(0xFFF9A825)    // readout / verdict: middling
private val BAR_BAD = Color(0xFFE65100)      // readout / verdict: poor
private val BAR_NEUTRAL = Color(0xFF9AA0A6)  // no data

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
        val model = state?.let { buildNightModel(it) }
        val updatedAt = snapshot?.updatedAtMs ?: 0L
        val bgAlpha = DashboardSnapshotStore.backgroundAlpha(context)
        val textScale = DashboardSnapshotStore.textScale(context)
        provideContent {
            GlanceTheme(colors = PulsarWidgetColors) {
                if (state == null || model == null) {
                    EmptyState(bgAlpha, textScale)
                } else {
                    TonightWidget(state, model, updatedAt, bgAlpha, textScale)
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
 * "Tonight" — the live night-bar reinterpretation of the in-app Sky Dial.
 * Glance has no canvas, so the dial's arc becomes a horizontal bar of real
 * coloured Boxes (live views, NOT a rendered image): dusk → dawn, each slice
 * tinted by that part of the night's shooting quality, the best window lit in
 * magenta, and a white "now" tick riding along it. Verdict above; dusk · best
 * window · dawn and the four tinted readouts below.
 */
@Composable
private fun TonightWidget(
    state: DashboardState,
    model: NightModel,
    updatedAtMs: Long,
    bgAlpha: Float,
    scale: Float,
) {
    val ctx = androidx.glance.LocalContext.current
    val verdictColor = when (model.verdict) {
        NightVerdict.EXCELLENT, NightVerdict.GOOD -> BAR_GOOD
        NightVerdict.FAIR -> BAR_AMBER
        NightVerdict.POOR -> BAR_BAD
    }
    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(widgetBgWithAlpha(bgAlpha))
            .padding(14.dp)
            .clickable(actionStartActivity(openAppIntent(ctx))),
    ) {
        // Header: city + verdict + refresh.
        Row(
            modifier = GlanceModifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                state.location?.cityName ?: "—",
                style = TextStyle(
                    color = GlanceTheme.colors.onSurface,
                    fontWeight = FontWeight.Bold,
                    fontSize = scaledSp(18, scale),
                ),
                maxLines = 1,
                modifier = GlanceModifier.defaultWeight(),
            )
            Text(
                ctx.getString(model.verdict.labelRes).uppercase(),
                style = TextStyle(
                    color = ColorProvider(verdictColor),
                    fontWeight = FontWeight.Bold,
                    fontSize = scaledSp(15, scale),
                ),
                maxLines = 1,
            )
            Spacer(GlanceModifier.width(6.dp))
            RefreshButton()
        }
        Spacer(GlanceModifier.height(9.dp))

        NightBar(model)
        Spacer(GlanceModifier.height(5.dp))

        // dusk · best window · dawn.
        Row(
            modifier = GlanceModifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                hm(state.sun?.sunset),
                style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = scaledSp(13, scale)),
            )
            Spacer(GlanceModifier.defaultWeight())
            if (model.windowLabel.isNotEmpty()) {
                Text(
                    "✦ ${model.windowLabel}",
                    style = TextStyle(
                        color = ColorProvider(BAR_BEST),
                        fontWeight = FontWeight.Bold,
                        fontSize = scaledSp(14, scale),
                    ),
                    maxLines = 1,
                )
            }
            Spacer(GlanceModifier.defaultWeight())
            Text(
                hm(state.sun?.sunrise),
                style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = scaledSp(13, scale)),
            )
        }
        Spacer(GlanceModifier.height(10.dp))

        // Four tinted readouts — Moon · Light · Sky · Targets. defaultWeight()
        // is resolved here (in RowScope) and handed to each Readout.
        Row(modifier = GlanceModifier.fillMaxWidth()) {
            Readout(GlanceModifier.defaultWeight(), ctx.getString(R.string.dash_moon), moonReadout(state), moonReadoutTint(state), scale)
            Readout(GlanceModifier.defaultWeight(), ctx.getString(R.string.dash_light), lightReadout(state), lightReadoutTint(state), scale)
            Readout(GlanceModifier.defaultWeight(), ctx.getString(R.string.dash_sky), skyReadout(state), skyReadoutTint(state), scale)
            Readout(GlanceModifier.defaultWeight(), ctx.getString(R.string.dash_targets), targetsReadout(state), targetsReadoutTint(state), scale)
        }

        if (isStale(updatedAtMs)) {
            Spacer(GlanceModifier.height(6.dp))
            Text(
                "${ctx.getString(R.string.widget_stale_prefix)} ${formatTime(updatedAtMs)}",
                style = TextStyle(color = GlanceTheme.colors.error, fontSize = scaledSp(11, scale)),
            )
        }
    }
}

/** The live night bar: [k] adjacent coloured Boxes, dusk → dawn. */
@Composable
private fun NightBar(model: NightModel) {
    val n = 24
    val seg = downsampleQuality(model.samples, n)
    val nowIdx = model.nowFraction?.let { (it * n).toInt().coerceIn(0, n - 1) }
    Row(
        modifier = GlanceModifier
            .fillMaxWidth()
            .height(16.dp)
            .cornerRadius(5.dp),
    ) {
        // A `for` loop (not forEach) keeps the RowScope receiver so
        // defaultWeight() resolves on each segment.
        for (i in seg.indices) {
            val q = seg[i]
            val f = i / n.toFloat()
            val inWindow = model.windowEndF > model.windowStartF &&
                f >= model.windowStartF && f < model.windowEndF
            val color = if (inWindow) BAR_BEST else lerp(BAR_DIM, BAR_BRIGHT, q.coerceIn(0f, 1f))
            Box(
                modifier = GlanceModifier
                    .defaultWeight()
                    .fillMaxHeight()
                    .background(color),
            ) {}
            if (nowIdx != null && i == nowIdx) {
                Box(
                    modifier = GlanceModifier
                        .width(3.dp)
                        .fillMaxHeight()
                        .background(BAR_NOW),
                ) {}
            }
        }
    }
}

/** One tinted readout chip, equal-weighted in the bottom row. The weight
 *  modifier is passed in from the (RowScope) call site. */
@Composable
private fun Readout(modifier: GlanceModifier, label: String, value: String, tint: Color, scale: Float) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            label.uppercase(),
            style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = scaledSp(11, scale)),
            maxLines = 1,
        )
        Text(
            value,
            style = TextStyle(color = ColorProvider(tint), fontWeight = FontWeight.Bold, fontSize = scaledSp(15, scale)),
            maxLines = 1,
        )
    }
}

// ── Readout values + tints (mirror the in-app dial complications) ────────────

private fun moonReadout(s: DashboardState) =
    s.moon?.let { "${it.emoji} ${it.illuminationPct.toInt()}%" } ?: "—"
private fun moonReadoutTint(s: DashboardState) = s.moon?.let {
    when { it.illuminationPct <= 25 -> BAR_GOOD; it.illuminationPct <= 55 -> BAR_AMBER; else -> BAR_BAD }
} ?: BAR_NEUTRAL

private fun lightReadout(s: DashboardState) = s.bortle?.let { "B${it.bortleClass.toInt()}" } ?: "—"
private fun lightReadoutTint(s: DashboardState) = s.bortle?.let {
    when (it.bortleClass.toInt()) { in 1..4 -> BAR_GOOD; in 5..6 -> BAR_AMBER; else -> BAR_BAD }
} ?: BAR_NEUTRAL

private fun skyReadout(s: DashboardState) = s.weather?.let { "${it.cloudCoverPct}%" } ?: "—"
private fun skyReadoutTint(s: DashboardState) = s.weather?.let {
    when { it.cloudCoverPct <= 25 -> BAR_GOOD; it.cloudCoverPct <= 60 -> BAR_AMBER; else -> BAR_BAD }
} ?: BAR_NEUTRAL

private fun targetsReadout(s: DashboardState) =
    (s.planets.size + s.bestWindows.size).let { if (it > 0) it.toString() else "—" }
private fun targetsReadoutTint(s: DashboardState) =
    if (s.planets.size + s.bestWindows.size > 0) BAR_GOOD else BAR_NEUTRAL

/** Downsample the per-minute quality samples to [k] bar segments by averaging. */
private fun downsampleQuality(samples: List<NightSample>, k: Int): List<Float> {
    if (samples.isEmpty()) return List(k) { 0f }
    return (0 until k).map { i ->
        val a = i * samples.size / k
        val b = (((i + 1) * samples.size / k).coerceAtLeast(a + 1)).coerceAtMost(samples.size)
        samples.subList(a, b).map { it.quality }.average().toFloat()
    }
}

/** "2026-06-17T19:27" or "19:27:00" → "19:27". */
private fun hm(iso: String?): String =
    iso?.let { it.substringAfter("T", it).take(5) } ?: "—"

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
