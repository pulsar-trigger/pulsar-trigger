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
import com.ehrocha.pulsar.MainActivity
import com.ehrocha.pulsar.R
import com.ehrocha.pulsar.astro.AstroDashboardManager
import com.ehrocha.pulsar.astro.DashboardState
import com.ehrocha.pulsar.astro.NightModel
import com.ehrocha.pulsar.astro.NightSample
import com.ehrocha.pulsar.astro.NightVerdict
import com.ehrocha.pulsar.astro.buildNightModel
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

// Verdict palette — matches DashboardScreen.kt's VerdictRow exactly.
// SIGNAL: the widget follows the app's own schemes (Carbon when the
// system is dark, Chart Paper in light) instead of Material You dynamic
// color — it sits on the home screen next to the pulsar icon and should
// match it. Glance can't load bundled fonts, so type stays system.
private val PulsarWidgetColors = androidx.glance.material3.ColorProviders(
    light = com.ehrocha.pulsar.ui.theme.LightColorScheme,
    dark = com.ehrocha.pulsar.ui.theme.DarkColorScheme,
)

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
private val MOON_BAND = Color(0xFFD9B45A)    // moon-up rail (gold), above the bar
private val MW_BAND = Color(0xFF53D7C9)       // Milky-Way-core rail (teal), below
private val BAND_TRACK = Color(0x1FFFFFFF)    // faint rail where the body is down

// Segment count shared by the quality bar and the moon/MW rails so they align.
private const val BAR_SEGMENTS = 24

// Glance's RemoteViews-backed Text path can silently drop fractional sp
// (e.g. 15.6.sp from `15 * 1.2`), so we round to integer sp before handing
// the value over.
private fun scaledSp(base: Int, scale: Float) =
    (base * scale).toInt().coerceAtLeast(1).sp

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
            .padding(horizontal = 12.dp, vertical = 10.dp)
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
        Spacer(GlanceModifier.height(6.dp))

        // Moon-up rail (above) · quality bar · Milky-Way-core rail (below) —
        // the dial's concentric bands, unrolled.
        BandLine(downsampleFlags(model.samples) { it.moonUp }, MOON_BAND)
        Spacer(GlanceModifier.height(2.dp))
        NightBar(model)
        Spacer(GlanceModifier.height(2.dp))
        BandLine(downsampleFlags(model.samples) { it.coreUp }, MW_BAND)
        Spacer(GlanceModifier.height(4.dp))

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
        Spacer(GlanceModifier.height(7.dp))

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
    val n = BAR_SEGMENTS
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

/** A thin rail above/below the night bar, lit in [color] where the body is up
 *  (moon / Milky-Way core), faint where it's down. */
@Composable
private fun BandLine(flags: List<Boolean>, color: Color) {
    Row(
        modifier = GlanceModifier
            .fillMaxWidth()
            .height(4.dp)
            .cornerRadius(2.dp),
    ) {
        // `for` (not forEach) keeps the RowScope receiver for defaultWeight().
        for (up in flags) {
            Box(
                modifier = GlanceModifier
                    .defaultWeight()
                    .fillMaxHeight()
                    .background(if (up) color else BAND_TRACK),
            ) {}
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

/** Downsample a per-sample boolean flag to [BAR_SEGMENTS] segments: a segment
 *  is "up" if the body is up anywhere within it. */
private fun downsampleFlags(samples: List<NightSample>, flag: (NightSample) -> Boolean): List<Boolean> {
    val k = BAR_SEGMENTS
    if (samples.isEmpty()) return List(k) { false }
    return (0 until k).map { i ->
        val a = i * samples.size / k
        val b = (((i + 1) * samples.size / k).coerceAtLeast(a + 1)).coerceAtMost(samples.size)
        samples.subList(a, b).any(flag)
    }
}

/** "2026-06-17T19:27" or "19:27:00" → "19:27". */
private fun hm(iso: String?): String =
    iso?.let { it.substringAfter("T", it).take(5) } ?: "—"

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

private fun formatTime(epochMs: Long): String {
    if (epochMs <= 0) return "—"
    val t = Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault())
    return DateTimeFormatter.ofPattern("HH:mm", Locale.getDefault()).format(t)
}
