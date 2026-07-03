/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Copyright (C) 2026 Pulsar Trigger contributors
 */

package com.ehrocha.pulsar.ui.screens

import android.annotation.SuppressLint
import android.location.LocationManager
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ehrocha.pulsar.R
import com.ehrocha.pulsar.astro.MoonPhase
import com.ehrocha.pulsar.ui.components.PulsarTopBar
import com.ehrocha.pulsar.ui.theme.Mono
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.MonthDay
import java.time.temporal.ChronoUnit
import kotlin.math.abs
import kotlin.math.sin

/** A major annual meteor shower. Radiant is the apparent origin point on the
 *  sky; ZHR is the zenithal hourly rate at peak under ideal dark skies. */
private data class MeteorShower(
    val name: String,
    val code: String,
    val peakMonth: Int,
    val peakDay: Int,
    val activeStart: MonthDay,
    val activeEnd: MonthDay,
    val zhr: Int,
    val radiantRaDeg: Double,
    val radiantDecDeg: Double,
    val parent: String,
)

/** Standard IMO figures for the showers worth planning around. Radiant
 *  positions are peak-night approximations (degrees). */
private val SHOWERS = listOf(
    MeteorShower("Quadrantids", "QUA", 1, 3, MonthDay.of(12, 28), MonthDay.of(1, 12), 110, 230.0, 49.0, "Asteroid 2003 EH1"),
    MeteorShower("Lyrids", "LYR", 4, 22, MonthDay.of(4, 16), MonthDay.of(4, 25), 18, 271.0, 34.0, "Comet Thatcher"),
    MeteorShower("Eta Aquariids", "ETA", 5, 6, MonthDay.of(4, 19), MonthDay.of(5, 28), 50, 338.0, -1.0, "Comet Halley"),
    MeteorShower("Delta Aquariids", "SDA", 7, 30, MonthDay.of(7, 12), MonthDay.of(8, 23), 25, 340.0, -16.0, "Comet 96P/Machholz"),
    MeteorShower("Perseids", "PER", 8, 12, MonthDay.of(7, 17), MonthDay.of(8, 24), 100, 48.0, 58.0, "Comet Swift–Tuttle"),
    MeteorShower("Southern Taurids", "STA", 10, 10, MonthDay.of(9, 23), MonthDay.of(11, 19), 5, 32.0, 9.0, "Comet Encke"),
    MeteorShower("Orionids", "ORI", 10, 21, MonthDay.of(10, 2), MonthDay.of(11, 7), 20, 95.0, 16.0, "Comet Halley"),
    MeteorShower("Northern Taurids", "NTA", 11, 12, MonthDay.of(10, 20), MonthDay.of(12, 10), 5, 58.0, 22.0, "Comet Encke"),
    MeteorShower("Leonids", "LEO", 11, 17, MonthDay.of(11, 6), MonthDay.of(11, 30), 15, 152.0, 22.0, "Comet Tempel–Tuttle"),
    MeteorShower("Geminids", "GEM", 12, 14, MonthDay.of(12, 4), MonthDay.of(12, 17), 150, 112.0, 33.0, "Asteroid 3200 Phaethon"),
    MeteorShower("Ursids", "URS", 12, 22, MonthDay.of(12, 17), MonthDay.of(12, 26), 10, 217.0, 76.0, "Comet 8P/Tuttle"),
)

private enum class Rating { EXCELLENT, GOOD, FAIR, POOR, HIDDEN }

/** Everything the card needs, derived from the shower + the observer. */
private data class ShowerView(
    val shower: MeteorShower,
    val peak: LocalDate,
    val daysToPeak: Long,
    val activeNow: Boolean,
    val radiantMaxAltDeg: Double,
    val moonIllumPct: Int,
    val moonEmoji: String,
    val rating: Rating,
    val reasonRes: Int,
)

@SuppressLint("MissingPermission")
@Composable
fun MeteorCalendarScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var latitude by remember { mutableDoubleStateOf(Double.NaN) }
    var longitude by remember { mutableDoubleStateOf(Double.NaN) }
    var locationReady by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        scope.launch(Dispatchers.IO) {
            try {
                val lm = context.getSystemService(LocationManager::class.java) ?: return@launch
                val loc = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                    ?: lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
                if (loc != null) {
                    latitude = loc.latitude
                    longitude = loc.longitude
                    locationReady = true
                }
            } catch (_: SecurityException) { }
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        PulsarTopBar(
            title = stringResource(R.string.meteor_title),
            onBack = onBack,
            helpText = stringResource(R.string.meteor_help),
        )
        Column(modifier = Modifier.padding(horizontal = 16.dp)) {

            // ── Location status ─────────────────────────────────────────
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(
                    Icons.Default.MyLocation,
                    contentDescription = null,
                    tint = if (locationReady) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(8.dp))
                if (locationReady) {
                    Text(
                        stringResource(
                            R.string.alignment_location_info,
                            "%.2f".format(latitude), "%.2f".format(longitude),
                        ),
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = Mono),
                    )
                } else {
                    Text(
                        stringResource(R.string.alignment_no_location),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Spacer(Modifier.height(12.dp))

            if (!locationReady) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) { CircularProgressIndicator() }
            } else {
                val today = LocalDate.now()
                val views = remember(latitude, today) {
                    SHOWERS.map { it.toView(latitude, today) }
                        .sortedBy { it.peak }
                }
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        androidx.compose.ui.res.pluralStringResource(
                            R.plurals.meteor_count, views.size, views.size),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.height(4.dp))
                    views.forEach { ShowerCard(it) }
                    Spacer(Modifier.height(16.dp))
                }
            }
        }
    }
}

@Composable
private fun ShowerCard(v: ShowerView) {
    val ratingColor = when (v.rating) {
        Rating.EXCELLENT -> MaterialTheme.colorScheme.primary
        Rating.GOOD -> MaterialTheme.colorScheme.tertiary
        Rating.FAIR -> MaterialTheme.colorScheme.secondary
        Rating.POOR, Rating.HIDDEN -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val ratingRes = when (v.rating) {
        Rating.EXCELLENT -> R.string.meteor_rate_excellent
        Rating.GOOD -> R.string.meteor_rate_good
        Rating.FAIR -> R.string.meteor_rate_fair
        Rating.POOR -> R.string.meteor_rate_poor
        Rating.HIDDEN -> R.string.meteor_rate_hidden
    }

    Surface(
        shape = RoundedCornerShape(12.dp),
        tonalElevation = 2.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.Top,
        ) {
            // Radiant max altitude — the hemisphere-visibility indicator.
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.width(52.dp),
            ) {
                if (v.radiantMaxAltDeg > 0) {
                    Text(
                        "%.0f°".format(v.radiantMaxAltDeg),
                        style = MaterialTheme.typography.titleMedium.copy(fontFamily = Mono),
                        fontWeight = FontWeight.Bold,
                        color = ratingColor,
                    )
                    Text(
                        stringResource(R.string.meteor_radiant_label),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Text(
                        "—",
                        style = MaterialTheme.typography.titleMedium.copy(fontFamily = Mono),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Spacer(Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        v.shower.name,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(Modifier.width(8.dp))
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = ratingColor.copy(alpha = 0.18f),
                    ) {
                        Text(
                            stringResource(ratingRes),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = ratingColor,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp),
                        )
                    }
                }

                // Timing line — active / tonight / countdown.
                val timing = when {
                    v.daysToPeak == 0L -> stringResource(R.string.meteor_peak_tonight)
                    v.daysToPeak < 0L -> androidx.compose.ui.res.pluralStringResource(
                        R.plurals.meteor_peaked_ago, (-v.daysToPeak).toInt(), -v.daysToPeak)
                    else -> androidx.compose.ui.res.pluralStringResource(
                        R.plurals.meteor_peak_in, v.daysToPeak.toInt(), v.daysToPeak)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        timing,
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = Mono),
                        color = if (v.activeNow) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurface,
                    )
                    if (v.activeNow) {
                        Spacer(Modifier.width(6.dp))
                        Text(
                            "• " + stringResource(R.string.meteor_active),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }

                // Rate + Moon figures.
                Text(
                    stringResource(R.string.meteor_rate_line, v.shower.zhr, v.moonEmoji, v.moonIllumPct),
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = Mono),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    stringResource(v.reasonRes),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    stringResource(R.string.meteor_parent, v.shower.parent),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

// ── Derivation ──────────────────────────────────────────────────────────────

private fun MeteorShower.toView(latDeg: Double, today: LocalDate): ShowerView {
    // Next peak: this year's, unless it's more than 30 days past (then roll
    // to next year) — keeps a just-peaked, still-active shower near the top.
    var peak = LocalDate.of(today.year, peakMonth, peakDay)
    if (peak.isBefore(today.minusDays(30))) peak = peak.plusYears(1)
    val days = ChronoUnit.DAYS.between(today, peak)

    // The radiant culminates at altitude (90 - |lat - dec|); at or below 0 it
    // never clears the horizon from this latitude.
    val maxAlt = 90.0 - abs(latDeg - radiantDecDeg)

    val moonAge = MoonPhase.moonAge(peak)
    val illum = MoonPhase.illumination(moonAge)

    // Honest estimate: peak rate scaled by how high the radiant climbs and how
    // much a bright Moon near peak washes out the faint streaks.
    val visFactor = sin(Math.toRadians(maxAlt.coerceAtLeast(0.0)))
    val moonFactor = illum / 100.0
    val effZhr = zhr * visFactor * (1.0 - 0.7 * moonFactor)

    val rating = when {
        maxAlt <= 0 -> Rating.HIDDEN
        effZhr >= 50 -> Rating.EXCELLENT
        effZhr >= 20 -> Rating.GOOD
        effZhr >= 8 -> Rating.FAIR
        else -> Rating.POOR
    }
    val reason = when {
        maxAlt <= 0 -> R.string.meteor_reason_horizon
        illum > 55 && maxAlt >= 15 -> R.string.meteor_reason_moon
        maxAlt < 15 -> R.string.meteor_reason_low
        else -> R.string.meteor_reason_good
    }

    return ShowerView(
        shower = this,
        peak = peak,
        daysToPeak = days,
        activeNow = isActiveOn(today),
        radiantMaxAltDeg = maxAlt,
        moonIllumPct = illum.toInt(),
        moonEmoji = MoonPhase.emoji(moonAge),
        rating = rating,
        reasonRes = reason,
    )
}

/** Active windows can wrap the year boundary (e.g. Quadrantids Dec→Jan). */
private fun MeteorShower.isActiveOn(today: LocalDate): Boolean {
    val md = MonthDay.from(today)
    return if (!activeStart.isAfter(activeEnd)) {
        md >= activeStart && md <= activeEnd
    } else {
        md >= activeStart || md <= activeEnd
    }
}
