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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ehrocha.pulsar.R
import com.ehrocha.pulsar.ui.components.PulsarTopBar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.Calendar
import java.util.TimeZone
import kotlin.math.*

/** A prominent deep-sky object for astrophotography. */
private data class DsoTarget(
    val name: String,
    val catalog: String,
    val type: String,
    val raDeg: Double,   // right ascension in degrees (0-360)
    val decDeg: Double,  // declination in degrees (-90..+90)
    val magnitude: Double,
    val description: String,
)

/** Curated catalog of popular astrophotography targets. */
private val DSO_CATALOG = listOf(
    DsoTarget("Orion Nebula", "M42", "Emission Nebula", 83.82, -5.39, 4.0, "Bright emission nebula, excellent for beginners"),
    DsoTarget("Andromeda Galaxy", "M31", "Galaxy", 10.68, 41.27, 3.4, "Nearest large galaxy, great wide-field target"),
    DsoTarget("Lagoon Nebula", "M8", "Emission Nebula", 270.92, -24.38, 6.0, "Large summer nebula with star cluster"),
    DsoTarget("Eagle Nebula", "M16", "Emission Nebula", 274.70, -13.81, 6.0, "Home of the Pillars of Creation"),
    DsoTarget("Trifid Nebula", "M20", "Emission Nebula", 270.62, -23.03, 6.3, "Colorful emission/reflection/dark nebula"),
    DsoTarget("Ring Nebula", "M57", "Planetary Nebula", 283.40, 33.03, 8.8, "Classic planetary nebula in Lyra"),
    DsoTarget("Dumbbell Nebula", "M27", "Planetary Nebula", 299.90, 22.72, 7.5, "Large bright planetary nebula"),
    DsoTarget("Whirlpool Galaxy", "M51", "Galaxy", 202.47, 47.20, 8.4, "Face-on spiral galaxy with companion"),
    DsoTarget("Triangulum Galaxy", "M33", "Galaxy", 23.46, 30.66, 5.7, "Third-largest Local Group galaxy"),
    DsoTarget("Pleiades", "M45", "Open Cluster", 56.87, 24.12, 1.6, "Iconic star cluster with reflection nebulae"),
    DsoTarget("Hercules Cluster", "M13", "Globular Cluster", 250.42, 36.46, 5.8, "Brightest northern globular cluster"),
    DsoTarget("Omega Centauri", "NGC 5139", "Globular Cluster", 201.70, -47.48, 3.7, "Largest globular cluster visible from Earth"),
    DsoTarget("Carina Nebula", "NGC 3372", "Emission Nebula", 161.26, -59.87, 1.0, "Enormous southern emission nebula"),
    DsoTarget("North America Nebula", "NGC 7000", "Emission Nebula", 314.68, 44.33, 4.0, "Large nebula with distinctive shape"),
    DsoTarget("Rosette Nebula", "NGC 2237", "Emission Nebula", 98.23, 5.03, 9.0, "Large winter emission nebula"),
    DsoTarget("Heart Nebula", "IC 1805", "Emission Nebula", 38.18, 61.47, 6.5, "Heart-shaped emission nebula in Cassiopeia"),
    DsoTarget("Soul Nebula", "IC 1848", "Emission Nebula", 42.04, 60.44, 6.5, "Companion to the Heart Nebula"),
    DsoTarget("Flame Nebula", "NGC 2024", "Emission Nebula", 85.42, -1.85, 2.0, "Bright nebula near Alnitak in Orion"),
    DsoTarget("Horsehead Nebula", "B33", "Dark Nebula", 85.24, -2.46, 6.8, "Iconic dark nebula silhouetted in Orion"),
    DsoTarget("Crab Nebula", "M1", "Supernova Remnant", 83.63, 22.01, 8.4, "Remnant of the 1054 supernova"),
    DsoTarget("Eta Carinae Nebula", "NGC 3372", "Emission Nebula", 161.26, -59.87, 1.0, "Massive southern nebula complex"),
    DsoTarget("Tarantula Nebula", "NGC 2070", "Emission Nebula", 84.68, -69.10, 8.2, "Largest known nebula in the LMC"),
    DsoTarget("Veil Nebula", "NGC 6960", "Supernova Remnant", 312.77, 30.72, 7.0, "Elegant supernova remnant in Cygnus"),
    DsoTarget("Pinwheel Galaxy", "M101", "Galaxy", 210.80, 54.35, 7.9, "Grand-design face-on spiral galaxy"),
    DsoTarget("Sombrero Galaxy", "M104", "Galaxy", 190.00, -11.62, 8.0, "Edge-on galaxy with prominent dust lane"),
    DsoTarget("Bode's Galaxy", "M81", "Galaxy", 148.89, 69.07, 6.9, "Nearby spiral galaxy in Ursa Major"),
    DsoTarget("Cigar Galaxy", "M82", "Galaxy", 148.97, 69.68, 8.4, "Starburst galaxy near M81"),
)

@SuppressLint("MissingPermission")
@Composable
fun WhatsUpScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var latitude by remember { mutableDoubleStateOf(Double.NaN) }
    var longitude by remember { mutableDoubleStateOf(Double.NaN) }
    var locationReady by remember { mutableStateOf(false) }

    // Acquire location on entry
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
            title = stringResource(R.string.whats_up_title),
            onBack = onBack,
            helpText = stringResource(R.string.whats_up_help),
        )
        Column(modifier = Modifier.padding(horizontal = 16.dp)) {

        // ── Location status ─────────────────────────────────────────────
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
                    stringResource(R.string.alignment_location_info, "%.2f".format(latitude), "%.2f".format(longitude)),
                    style = MaterialTheme.typography.bodySmall,
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

        // ── Target list ─────────────────────────────────────────────────
        if (!locationReady) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }
        } else {
            val now = Calendar.getInstance()
            val lst = localSiderealTime(now, longitude)
            val targets = DSO_CATALOG
                .map { dso ->
                    val alt = computeAltitude(dso.raDeg, dso.decDeg, latitude, lst)
                    dso to alt
                }
                .filter { (_, alt) -> alt > 10.0 } // at least 10° above horizon
                .sortedByDescending { (_, alt) -> alt }

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    androidx.compose.ui.res.pluralStringResource(
                        R.plurals.whats_up_visible_count, targets.size, targets.size),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.height(4.dp))

                if (targets.isEmpty()) {
                    Text(
                        stringResource(R.string.whats_up_none_visible),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                targets.forEach { (dso, alt) ->
                    DsoCard(dso = dso, altitude = alt)
                }

                Spacer(Modifier.height(16.dp))
            }
        }
        } // inner Column (content)
    }
}

@Composable
private fun DsoCard(dso: DsoTarget, altitude: Double) {
    val qualityColor = when {
        altitude > 60 -> MaterialTheme.colorScheme.primary
        altitude > 30 -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val visIcon = if (altitude > 30) Icons.Default.Visibility else Icons.Default.VisibilityOff

    Surface(
        shape = RoundedCornerShape(12.dp),
        tonalElevation = 2.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Altitude indicator
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.width(48.dp),
            ) {
                Icon(
                    visIcon,
                    contentDescription = null,
                    tint = qualityColor,
                    modifier = Modifier.size(20.dp),
                )
                Text(
                    "%.0f°".format(altitude),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = qualityColor,
                )
            }

            Spacer(Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        dso.name,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(Modifier.width(8.dp))
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.primaryContainer,
                    ) {
                        Text(
                            dso.catalog,
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
                        )
                    }
                }
                Text(
                    dso.type,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    dso.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "Mag %.1f".format(dso.magnitude),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

// ── Astronomy calculations ──────────────────────────────────────────────────

/** Compute Local Sidereal Time in degrees for a given calendar time and longitude. */
private fun localSiderealTime(cal: Calendar, longitudeDeg: Double): Double {
    val utcCal = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
        timeInMillis = cal.timeInMillis
    }
    val year = utcCal.get(Calendar.YEAR)
    val month = utcCal.get(Calendar.MONTH) + 1
    val day = utcCal.get(Calendar.DAY_OF_MONTH)
    val hour = utcCal.get(Calendar.HOUR_OF_DAY) +
        utcCal.get(Calendar.MINUTE) / 60.0 +
        utcCal.get(Calendar.SECOND) / 3600.0

    // Julian Date
    val a = (14 - month) / 12
    val y = year + 4800 - a
    val m = month + 12 * a - 3
    val jd = day + (153 * m + 2) / 5 + 365 * y + y / 4 - y / 100 + y / 400 - 32045 +
        (hour - 12.0) / 24.0

    // Greenwich Mean Sidereal Time
    val t = (jd - 2451545.0) / 36525.0
    var gmst = 280.46061837 + 360.98564736629 * (jd - 2451545.0) +
        0.000387933 * t * t - t * t * t / 38710000.0
    gmst = gmst.mod(360.0)

    // Local Sidereal Time
    return (gmst + longitudeDeg).mod(360.0)
}

/** Compute altitude of an object above the horizon in degrees. */
private fun computeAltitude(raDeg: Double, decDeg: Double, latDeg: Double, lstDeg: Double): Double {
    val ha = Math.toRadians((lstDeg - raDeg).mod(360.0))
    val dec = Math.toRadians(decDeg)
    val lat = Math.toRadians(latDeg)

    val sinAlt = sin(lat) * sin(dec) + cos(lat) * cos(dec) * cos(ha)
    return Math.toDegrees(asin(sinAlt.coerceIn(-1.0, 1.0)))
}
