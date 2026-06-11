/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Copyright (C) 2026 Pulsar Trigger contributors
 */

package com.ehrocha.pulsar.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.draw.clip
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
import androidx.compose.material.icons.automirrored.filled.ListAlt
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.FlightTakeoff
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.BottomSheetScaffold
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberBottomSheetScaffoldState
import androidx.compose.material3.rememberStandardBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.ehrocha.pulsar.R
import com.ehrocha.pulsar.transport.aircraft.AircraftSighting
import com.ehrocha.pulsar.transport.aircraft.AircraftSize
import com.ehrocha.pulsar.transport.aircraft.aircraftSizeFor
import com.ehrocha.pulsar.ui.theme.LocalNightMode
import com.ehrocha.pulsar.ui.theme.ThemeMode
import com.ehrocha.pulsar.viewmodel.PulsarViewModel
import kotlinx.coroutines.launch
import org.maplibre.android.MapLibre
import org.maplibre.android.annotations.IconFactory
import org.maplibre.android.annotations.Icon
import org.maplibre.android.annotations.Marker
import org.maplibre.android.annotations.MarkerOptions
import org.maplibre.android.annotations.Polyline
import org.maplibre.android.annotations.PolylineOptions
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

// ─────────────────────────────────────────────────────────────────────────
// Aircraft Watch — list rows, settings panel, banners, dialogs, badges,
// and the spotting-log store. Split out of AircraftWatchScreen.kt (audit
// S1). Same package; the screen file composes these.
// ─────────────────────────────────────────────────────────────────────────
@Composable
internal fun SettingsPanel(
    lat: Double,
    lon: Double,
    radiusKm: Int,
    onRadiusChange: (Int) -> Unit,
    maxAltFt: Int,
    onMaxAltChange: (Int) -> Unit,
    intervalSec: Int,
    onIntervalChange: (Int) -> Unit,
    showSunMoon: Boolean,
    onShowSunMoonChange: (Boolean) -> Unit,
    mapHeadingLock: Boolean,
    onMapHeadingLockChange: (Boolean) -> Unit,
    mapHybrid: Boolean,
    onMapHybridChange: (Boolean) -> Unit,
    alertNotable: Boolean,
    onAlertNotableChange: (Boolean) -> Unit,
    keepScreenOn: Boolean,
    onKeepScreenOnChange: (Boolean) -> Unit,
    compassAccuracy: Int,
    onShowCalibrate: () -> Unit,
    watching: Boolean,
    lastUpdateMs: Long,
    providerName: String,
) {
    // No outer Surface — the BottomSheetScaffold provides one. Status text
    // is FIRST so it's the line visible in the peek (collapsed) state; the
    // sliders are below the fold and revealed when the user drags up.
    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
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
            statusText + "  ·  " + String.format(Locale.US, "%.4f°, %.4f°", lat, lon),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 6.dp),
        )
        // Sliders — only visible when the sheet is expanded. Stacked
        // vertically with full-row widths for thumb-friendly hit targets.
        SliderRow(
            label = stringResource(R.string.aircraft_watch_radius_label, radiusKm),
            value = radiusKm.toFloat(),
            range = 5f..200f,
            steps = 38,
            onChange = { onRadiusChange(it.roundToInt()) },
        )
        SliderRow(
            label = stringResource(R.string.aircraft_watch_max_alt_label, maxAltFt),
            value = maxAltFt.toFloat(),
            range = 1_000f..50_000f,
            steps = 48,
            // Snap to 1 000-ft increments — finer is noise at these
            // altitudes and the slider feels jumpier.
            onChange = { onMaxAltChange((it / 1000f).roundToInt() * 1000) },
        )
        Text(
            stringResource(R.string.aircraft_watch_alt_hints),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 4.dp, top = 0.dp, bottom = 4.dp),
        )
        // Interval slider: 0 is the "Live" sentinel (drag fully left), 5..60
        // are real seconds. Snap the unreachable 1..4 zone up to 5 — the
        // slider visually allows landing there but the value commits to 5.
        SliderRow(
            label = if (intervalSec == 0) stringResource(R.string.aircraft_watch_interval_live)
                    else stringResource(R.string.aircraft_watch_interval_label, intervalSec),
            value = intervalSec.toFloat(),
            range = 0f..60f,
            steps = 60,
            onChange = {
                val r = it.roundToInt()
                onIntervalChange(if (r in 1..4) 5 else r)
            },
        )
        // Toggles, below the sliders so the more-commonly-used controls
        // stay at the top of the sheet.
        ToggleRow(R.string.aircraft_show_sun_moon, showSunMoon, onShowSunMoonChange)
        ToggleRow(R.string.aircraft_map_heading_lock, mapHeadingLock, onMapHeadingLockChange)
        ToggleRow(R.string.aircraft_map_hybrid, mapHybrid, onMapHybridChange)
        ToggleRow(R.string.aircraft_alert_notable, alertNotable, onAlertNotableChange)
        ToggleRow(R.string.aircraft_keep_screen_on, keepScreenOn, onKeepScreenOnChange)
        // Compass status + manual calibrate. Always visible: the sensor
        // accuracy banner above the map only fires when Android self-
        // reports low confidence, but the compass can read consistently
        // wrong (near metal / electronics) while the sensor still says
        // "HIGH". Manual calibrate gives the user an escape hatch.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                stringResource(
                    R.string.aircraft_compass_status,
                    compassAccuracyLabel(compassAccuracy),
                ),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            androidx.compose.material3.TextButton(onClick = onShowCalibrate) {
                Text(stringResource(R.string.aircraft_compass_calibrate))
            }
        }
    }
}

@Composable
internal fun ToggleRow(
    @androidx.annotation.StringRes labelRes: Int,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            stringResource(labelRes),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
        androidx.compose.material3.Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
        )
    }
}

@Composable
internal fun SliderRow(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int,
    onChange: (Float) -> Unit,
) {
    Text(
        label,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Slider(
        value = value,
        onValueChange = onChange,
        valueRange = range,
        steps = steps,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
internal fun NoLocationCard() {
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

/** Yellow banner shown when the rotation-vector sensor reports low /
 *  unreliable accuracy. The figure-8 motion remagnetises Android's
 *  magnetometer model and is the standard fix recommended by Google. */
@Composable
internal fun CompassCalibrationBanner(onDismiss: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = com.ehrocha.pulsar.ui.theme.PulsarTheme.colors.cautionContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Default.Explore,
                contentDescription = null,
                tint = com.ehrocha.pulsar.ui.theme.PulsarTheme.colors.onCautionContainer,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(10.dp))
            Text(
                stringResource(R.string.aircraft_compass_calibration),
                style = MaterialTheme.typography.bodySmall,
                color = com.ehrocha.pulsar.ui.theme.PulsarTheme.colors.onCautionContainer,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onDismiss) {
                Icon(
                    Icons.Default.Close,
                    contentDescription = stringResource(R.string.dismiss),
                    tint = com.ehrocha.pulsar.ui.theme.PulsarTheme.colors.onCautionContainer,
                )
            }
        }
    }
}

/** Orange banner shown when the OS GPS fix is stale (age > 15 min) or
 *  imprecise (accuracy worse than 200 m). Lets the user trigger a fresh
 *  single-shot GPS read. */
@Composable
internal fun GpsAccuracyBanner(
    accuracyM: Float?,
    ageMs: Long?,
    refreshing: Boolean,
    onRefresh: () -> Unit,
    onDismiss: () -> Unit,
) {
    val detail = buildString {
        accuracyM?.let { append("±", it.toInt(), " m") }
        ageMs?.let {
            if (isNotEmpty()) append(" · ")
            val mins = (it / 60_000L).toInt()
            append(if (mins < 60) "${mins}m" else "${mins / 60}h${mins % 60}m", " old")
        }
    }.ifBlank { "—" }
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = com.ehrocha.pulsar.ui.theme.PulsarTheme.colors.cautionContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Default.FlightTakeoff,
                contentDescription = null,
                tint = com.ehrocha.pulsar.ui.theme.PulsarTheme.colors.onCautionContainer,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.aircraft_gps_warning),
                    style = MaterialTheme.typography.bodySmall,
                    color = com.ehrocha.pulsar.ui.theme.PulsarTheme.colors.onCautionContainer,
                )
                Text(
                    detail,
                    style = MaterialTheme.typography.labelSmall,
                    color = com.ehrocha.pulsar.ui.theme.PulsarTheme.colors.onCautionContainer,
                )
            }
            if (refreshing) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp,
                    color = com.ehrocha.pulsar.ui.theme.PulsarTheme.colors.onCautionContainer,
                )
            } else {
                androidx.compose.material3.TextButton(
                    onClick = onRefresh,
                ) {
                    Text(
                        stringResource(R.string.aircraft_gps_refresh),
                        color = com.ehrocha.pulsar.ui.theme.PulsarTheme.colors.onCautionContainer,
                    )
                }
            }
            IconButton(onClick = onDismiss) {
                Icon(
                    Icons.Default.Close,
                    contentDescription = stringResource(R.string.dismiss),
                    tint = com.ehrocha.pulsar.ui.theme.PulsarTheme.colors.onCautionContainer,
                )
            }
        }
    }
}

@Composable
internal fun ErrorBanner(error: String) {
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

// Proximity ramp lives in PulsarColors so it follows the theme mode.

/** Map distance / radius ratio onto the green / yellow / red palette.
 *  Thresholds at 1/3 and 2/3 so each band feels like a meaningful chunk
 *  of the user's chosen search radius. */
internal fun proximityColor(
    distanceKm: Double,
    radiusKm: Int,
    c: com.ehrocha.pulsar.ui.theme.PulsarColors,
): androidx.compose.ui.graphics.Color {
    val ratio = if (radiusKm <= 0) 1.0 else distanceKm / radiusKm.toDouble()
    return when {
        ratio < 1.0 / 3.0 -> c.proximityNear
        ratio < 2.0 / 3.0 -> c.proximityMid
        else -> c.proximityFar
    }
}

/** Marker opacity encoding altitude: low aircraft (≤3 000 ft — the
 *  shootable subjects: pattern, approach, helicopters, GA) are solid;
 *  high cruisers (≥38 000 ft) fade to 50 %. Quantised to 5 levels so the
 *  icon cache stays bounded (continuous opacity would create a new bitmap
 *  per foot). Unknown altitude → solid (don't hide it). */
internal fun altitudeOpacity(altFt: Double?): Float {
    val alt = altFt ?: return 1f
    val t = ((alt - 3000.0) / (38000.0 - 3000.0)).coerceIn(0.0, 1.0)
    val raw = 1f - t.toFloat() * 0.5f          // 1.0 (low) .. 0.5 (high)
    return (Math.round(raw / 0.125f) * 0.125f) // 1.0, 0.875, 0.75, 0.625, 0.5
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun AircraftRow(
    s: AircraftSighting,
    radiusKm: Int,
    userLat: Double,
    userLon: Double,
    sunDir: SunDir?,
    selected: Boolean,
    onSelectOnMap: () -> Unit,
) {
    var showDetails by remember { mutableStateOf(false) }
    val pulsarColors = com.ehrocha.pulsar.ui.theme.PulsarTheme.colors
    val accent = proximityColor(s.distanceKm, radiusKm, pulsarColors)
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val rowBadges = aircraftBadges(s)
    val lighting = lightingFor(s.bearingDeg, sunDir)
    val elevation = elevationDeg(s)
    val logScope = androidx.compose.runtime.rememberCoroutineScope()
    // Selected rows get a stronger background + a leading accent so the
    // tap-to-highlight feedback is obvious without a separate selection
    // chip. The colour matches the map's highlight ring.
    val bgAlpha = if (selected) 0.28f else 0.13f
    Surface(
        onClick = onSelectOnMap,
        shape = RoundedCornerShape(12.dp),
        // Tinted background lets the proximity band read at a glance without
        // shouting — the colour saturation stays around the existing
        // dashboard-card palette levels.
        color = accent.copy(alpha = bgAlpha),
        border = if (selected) androidx.compose.foundation.BorderStroke(
            2.dp,
            pulsarColors.selection,
        ) else null,
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
                // Subline: model + operator if known, otherwise fall back
                // to ICAO hex + country. The progressive enhancement means
                // the row reads usefully on the first poll and gets richer
                // as the metadata cache fills.
                val sub = listOfNotNull(
                    s.model,
                    s.operator?.takeIf { it != s.model },
                    s.registration,
                ).joinToString(" · ").ifEmpty {
                    "${s.icaoHex.uppercase()}${s.originCountry?.let { " · $it" } ?: ""}"
                }
                Text(
                    sub,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                // Lighting chip + rare-aircraft badges share one wrap row.
                // Lighting is the most photographically glanceable, so it
                // leads.
                Spacer(Modifier.height(3.dp))
                androidx.compose.foundation.layout.FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    LightingChip(lighting)
                    rowBadges.forEach { BadgeChip(it) }
                }
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
                    color = accent,
                )
                // Bearing = where to aim horizontally; elevation = how far
                // to tilt up. Together they're a complete "point here".
                Text(
                    "${bearingArrow(s.bearingDeg)} ${s.bearingDeg.roundToInt()}°",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (elevation != null) {
                    Text(
                        "∡ ${elevation.roundToInt()}°",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            // Info button — the secondary action, since the row body now
            // selects-on-map. Tap opens the full detail dialog.
            IconButton(
                onClick = { showDetails = true },
                modifier = Modifier.padding(start = 4.dp),
            ) {
                Icon(
                    Icons.Default.Info,
                    contentDescription = stringResource(R.string.aircraft_info_button),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
    if (showDetails) {
        AircraftDetailDialog(
            s = s,
            userLat = userLat,
            userLon = userLon,
            sunDir = sunDir,
            onDismiss = { showDetails = false },
            onLogSighting = {
                // Store I/O is suspend (Dispatchers.IO) — launch off the
                // click handler; toast once the write actually landed.
                logScope.launch {
                    SpottingLogStore.add(
                        ctx,
                        LoggedSighting(
                            icaoHex = s.icaoHex,
                            callsign = s.callsign,
                            model = s.model,
                            registration = s.registration,
                            operator = s.operator,
                            distanceKm = s.distanceKm,
                            whenMs = System.currentTimeMillis(),
                            userLat = userLat,
                            userLon = userLon,
                        ),
                    )
                    android.widget.Toast.makeText(
                        ctx,
                        ctx.getString(R.string.aircraft_log_added),
                        android.widget.Toast.LENGTH_SHORT,
                    ).show()
                }
            },
        )
    }
}

/** Full-info modal — shown on tap. Everything we know about the aircraft
 *  in one place; values fall back to "—" when the metadata cache hasn't
 *  resolved that field yet (rare after the first cycle). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun AircraftDetailDialog(
    s: AircraftSighting,
    userLat: Double,
    userLon: Double,
    sunDir: SunDir?,
    onDismiss: () -> Unit,
    onLogSighting: () -> Unit,
) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val badges = aircraftBadges(s)
    val lighting = lightingFor(s.bearingDeg, sunDir)
    val elevation = elevationDeg(s)
    val closest = closestApproach(s, userLat, userLon)
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            androidx.compose.material3.TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_dismiss))
            }
        },
        dismissButton = {
            androidx.compose.material3.TextButton(onClick = {
                onLogSighting()
                onDismiss()
            }) {
                Text(stringResource(R.string.aircraft_log_this))
            }
        },
        title = {
            // Callsign + lighting condition side by side — the lighting is
            // the at-a-glance "is it worth shooting right now" cue, so it
            // lives at the top next to the flight number.
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    s.callsign ?: s.icaoHex.uppercase(),
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                )
                LightingChip(lighting)
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                // Hero photo + credit. Tapping opens the source page in the
                // browser — planespotters.net AUP requires the link to be
                // surfaced, not just hidden in About.
                if (s.photoUrl != null) {
                    coil3.compose.AsyncImage(
                        model = s.photoUrl,
                        contentDescription = s.model ?: s.icaoHex,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(140.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .clickable {
                                // https-only: the URL comes from a remote API
                                // response — never hand an arbitrary scheme
                                // (intent://, tel:, …) to ACTION_VIEW.
                                s.photoSourceUrl
                                    ?.takeIf { it.startsWith("https://") }
                                    ?.let { url ->
                                        runCatching {
                                            ctx.startActivity(
                                                android.content.Intent(
                                                    android.content.Intent.ACTION_VIEW,
                                                    android.net.Uri.parse(url),
                                                ),
                                            )
                                        }
                                    }
                            },
                        contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                    )
                    Text(
                        stringResource(
                            R.string.aircraft_photo_credit,
                            s.photoCredit ?: "Planespotters.net",
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (badges.isNotEmpty()) {
                    androidx.compose.foundation.layout.FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        badges.forEach { BadgeChip(it) }
                    }
                }
                // ── Identity (condensed: multi-value lines) ──────────────
                DetailRow(
                    stringResource(R.string.aircraft_detail_model),
                    listOfNotNull(s.model, s.manufacturer?.takeIf { it != s.model })
                        .joinToString(" · ").ifBlank { null },
                )
                DetailRow(stringResource(R.string.aircraft_detail_operator), s.operator)
                DetailRow(
                    stringResource(R.string.aircraft_detail_registration),
                    listOfNotNull(s.registration, s.typeCode, s.builtYear?.toString())
                        .joinToString(" · ").ifBlank { null },
                )
                DetailRow(
                    stringResource(R.string.aircraft_detail_icao),
                    s.icaoHex.uppercase() + (s.originCountry?.let { " · $it" } ?: ""),
                )
                DetailRow(stringResource(R.string.aircraft_detail_squawk), s.squawk)
                Spacer(Modifier.height(4.dp))
                // ── Photographer's section: where / when / how to shoot ──
                // Aim = where to point (distance · compass bearing · tilt up).
                DetailRow(
                    stringResource(R.string.aircraft_detail_aim),
                    listOfNotNull(
                        String.format(Locale.US, "%.1f km", s.distanceKm),
                        "${s.bearingDeg.roundToInt()}° ${bearingArrow(s.bearingDeg)}",
                        elevation?.let { "∡ ${it.roundToInt()}°" },
                    ).joinToString(" · "),
                )
                // Flight = the kinematics (altitude · speed · heading · climb).
                DetailRow(
                    stringResource(R.string.aircraft_detail_flight),
                    listOfNotNull(
                        s.altitudeFt?.let { "${it.roundToInt()} ft" },
                        s.groundSpeedKt?.let { "${it.roundToInt()} kt" },
                        s.headingDeg?.let { "${it.roundToInt()}°" },
                        s.verticalRateFpm?.let {
                            val sign = if (it >= 0) "↑" else "↓"
                            "$sign${kotlin.math.abs(it).roundToInt()} fpm"
                        },
                    ).joinToString(" · ").ifBlank { null },
                )
                closest?.let { ca ->
                    DetailRow(
                        stringResource(R.string.aircraft_detail_closest),
                        if (ca.receding)
                            stringResource(R.string.aircraft_closest_receding,
                                String.format(Locale.US, "%.1f", ca.minDistanceKm))
                        else
                            stringResource(R.string.aircraft_closest_in,
                                ca.secondsUntil.roundToInt(),
                                String.format(Locale.US, "%.1f", ca.minDistanceKm)),
                    )
                }
                // Suggested lens — derived from slant range + wingspan class.
                // Hint, not a precision figure; the FF caveat helps users
                // with crop-sensor bodies translate (÷1.5 APS-C, ÷2 MFT).
                DetailRow(
                    stringResource(R.string.aircraft_detail_suggested_lens),
                    stringResource(R.string.aircraft_detail_suggested_lens_value,
                        recommendedFocalLengthMm(s)),
                )
            }
        },
    )
}

@Composable
internal fun LightingChip(lighting: LightingKind) {
    Surface(
        shape = RoundedCornerShape(50),
        color = com.ehrocha.pulsar.ui.theme.PulsarTheme.colors.let { it.of(lighting).copy(alpha = it.chipAlpha) },
    ) {
        Text(
            "☀ " + stringResource(lighting.labelRes),
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
            style = MaterialTheme.typography.labelSmall,
            color = com.ehrocha.pulsar.ui.theme.PulsarTheme.colors.of(lighting),
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
internal fun DetailRow(label: String, value: String?) {
    if (value.isNullOrBlank()) return
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(
            label,
            modifier = Modifier.width(120.dp),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
    }
}

internal fun formatFlightLine(s: AircraftSighting): String {
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
internal fun bearingArrow(deg: Double): String {
    val n = ((deg + 22.5) / 45.0).toInt().mod(8)
    return when (n) {
        0 -> "↑"; 1 -> "↗"; 2 -> "→"; 3 -> "↘"
        4 -> "↓"; 5 -> "↙"; 6 -> "←"; else -> "↖"
    }
}

internal fun formatTime(epochMs: Long): String {
    val t = Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault())
    return DateTimeFormatter.ofPattern("HH:mm:ss", Locale.getDefault()).format(t)
}

// ── Feature 3 — Rare / interesting badges ────────────────────────────────
// Pure-derivation from existing AircraftSighting fields. Cheap to compute,
// nothing to cache. Each badge has a short label + a tint colour; the row
// shows zero-or-more chips.

internal enum class AircraftBadgeKind(val labelRes: Int) {
    EMERGENCY(R.string.aircraft_badge_emergency),
    VINTAGE(R.string.aircraft_badge_vintage),
    MILITARY(R.string.aircraft_badge_military),
    HEAVY(R.string.aircraft_badge_heavy),
}

/** Resolve a badge to the active theme's role colour — RedLight renders
 *  these as luminance steps instead of hues. */
internal fun com.ehrocha.pulsar.ui.theme.PulsarColors.of(b: AircraftBadgeKind) = when (b) {
    AircraftBadgeKind.EMERGENCY -> badgeEmergency
    AircraftBadgeKind.VINTAGE -> badgeVintage
    AircraftBadgeKind.MILITARY -> badgeMilitary
    AircraftBadgeKind.HEAVY -> badgeHeavy
}

internal fun com.ehrocha.pulsar.ui.theme.PulsarColors.of(l: LightingKind) = when (l) {
    LightingKind.GOLDEN -> lightingGolden
    LightingKind.FRONT_LIT -> lightingFront
    LightingKind.SIDE_LIT -> lightingSide
    LightingKind.BACK_LIT -> lightingBack
    LightingKind.NIGHT -> lightingNight
}

internal fun aircraftBadges(s: AircraftSighting): List<AircraftBadgeKind> {
    val out = mutableListOf<AircraftBadgeKind>()
    // Emergency squawks per ICAO: 7500 hijack, 7600 radio failure, 7700 general
    if (s.squawk in setOf("7500", "7600", "7700")) out += AircraftBadgeKind.EMERGENCY
    if (s.builtYear != null && s.builtYear < 1980) out += AircraftBadgeKind.VINTAGE
    // Military: operator/owner string contains the obvious keywords; or the
    // callsign starts with a known military prefix.
    val opUpper = (s.operator ?: "").uppercase()
    val csUpper = (s.callsign ?: "").uppercase()
    val militaryWords = listOf("FORCE", "NAVY", "ARMY", "MARINE", "COAST GUARD", "MILITARY", "DEFENSE", "DEFENCE")
    val militaryCallsignPrefixes = listOf("RCH", "REACH", "RAF", "NATO", "GRIZZLY", "DUKE", "BLUE")
    if (militaryWords.any { it in opUpper } ||
        militaryCallsignPrefixes.any { csUpper.startsWith(it) }) {
        out += AircraftBadgeKind.MILITARY
    }
    if (com.ehrocha.pulsar.transport.aircraft.aircraftSizeFor(s.typeCode, s.model)
        == com.ehrocha.pulsar.transport.aircraft.AircraftSize.HEAVY) {
        out += AircraftBadgeKind.HEAVY
    }
    return out
}

@Composable
internal fun BadgeChip(badge: AircraftBadgeKind) {
    Surface(
        shape = RoundedCornerShape(50),
        color = com.ehrocha.pulsar.ui.theme.PulsarTheme.colors.let { it.of(badge).copy(alpha = it.chipAlpha) },
    ) {
        Text(
            stringResource(badge.labelRes),
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
            style = MaterialTheme.typography.labelSmall,
            color = com.ehrocha.pulsar.ui.theme.PulsarTheme.colors.of(badge),
            fontWeight = FontWeight.SemiBold,
        )
    }
}

// ── Feature 1 — Spotting log persistence ─────────────────────────────────
// Local JSON file under app filesDir. Records what the user tapped "Log
// this" on. No remote sync — Pulsar is offline-first.

internal data class LoggedSighting(
    val icaoHex: String,
    val callsign: String?,
    val model: String?,
    val registration: String?,
    val operator: String?,
    val distanceKm: Double,
    val whenMs: Long,
    val userLat: Double,
    val userLon: Double,
)

internal object SpottingLogStore {
    private const val FILE = "pulsar_spotting_log.json"
    private const val LIMIT = 500  // cap so the log can't grow unbounded

    suspend fun load(ctx: android.content.Context): List<LoggedSighting> =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { loadSync(ctx) }

    /** Synchronous core — only call from a background dispatcher. */
    private fun loadSync(ctx: android.content.Context): List<LoggedSighting> {
        val f = java.io.File(ctx.filesDir, FILE)
        if (!f.exists()) return emptyList()
        return runCatching {
            val arr = org.json.JSONArray(f.readText())
            (0 until arr.length()).map { i ->
                val j = arr.getJSONObject(i)
                LoggedSighting(
                    icaoHex = j.getString("icao"),
                    callsign = j.optString("callsign").takeIf { it.isNotEmpty() },
                    model = j.optString("model").takeIf { it.isNotEmpty() },
                    registration = j.optString("reg").takeIf { it.isNotEmpty() },
                    operator = j.optString("operator").takeIf { it.isNotEmpty() },
                    distanceKm = j.optDouble("distKm", 0.0),
                    whenMs = j.optLong("whenMs", 0L),
                    userLat = j.optDouble("userLat", 0.0),
                    userLon = j.optDouble("userLon", 0.0),
                )
            }
        }.getOrDefault(emptyList())
    }

    suspend fun add(ctx: android.content.Context, s: LoggedSighting) =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val current = loadSync(ctx).toMutableList()
        // De-dup on (icao, day) so quickly tapping log twice doesn't double-
        // record the same plane, but a different flight tomorrow does add.
        val dayMs = 24L * 3600_000L
        current.removeAll {
            it.icaoHex == s.icaoHex && kotlin.math.abs(it.whenMs - s.whenMs) < dayMs
        }
        current += s
        if (current.size > LIMIT) {
            val drop = current.size - LIMIT
            for (i in 0 until drop) current.removeAt(0)
        }
        save(ctx, current)
    }

    suspend fun delete(ctx: android.content.Context, whenMs: Long, icao: String) =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val current = loadSync(ctx).filterNot { it.whenMs == whenMs && it.icaoHex == icao }
        save(ctx, current)
    }

    suspend fun clear(ctx: android.content.Context) =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        save(ctx, emptyList())
    }

    private fun save(ctx: android.content.Context, list: List<LoggedSighting>) {
        val arr = org.json.JSONArray()
        list.forEach { e ->
            arr.put(org.json.JSONObject().apply {
                put("icao", e.icaoHex)
                e.callsign?.let { put("callsign", it) }
                e.model?.let { put("model", it) }
                e.registration?.let { put("reg", it) }
                e.operator?.let { put("operator", it) }
                put("distKm", e.distanceKm)
                put("whenMs", e.whenMs)
                put("userLat", e.userLat)
                put("userLon", e.userLon)
            })
        }
        java.io.File(ctx.filesDir, FILE).writeText(arr.toString())
    }
}
