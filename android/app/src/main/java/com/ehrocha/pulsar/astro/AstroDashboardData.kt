/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Copyright (C) 2026 Pulsar Trigger contributors
 */

package com.ehrocha.pulsar.astro

import android.annotation.SuppressLint
import android.content.Context
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import com.ehrocha.pulsar.AppConfig
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.*

// ── Data models ──────────────────────────────────────────────────────────────

data class DashboardState(
    val location: LocationInfo? = null,
    val weather: WeatherInfo? = null,
    val moon: MoonInfo? = null,
    val sun: SunInfo? = null,
    val bortle: BortleInfo? = null,
    val milkyWay: MilkyWayInfo? = null,
    val bestWindows: List<PhotoWindow> = emptyList(),
    val dewPoint: DewPointInfo? = null,
    val twilight: TwilightInfo? = null,
    val planets: List<PlanetInfo> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
    val weatherError: String? = null,
    val bortleError: String? = null,
    val selectedDate: LocalDate = LocalDate.now(),
)

data class LocationInfo(
    val latitude: Double,
    val longitude: Double,
    val altitude: Double? = null,
    val cityName: String? = null,
)

data class WeatherInfo(
    val temperatureC: Double,
    val humidity: Int,
    val cloudCoverPct: Int,
    val precipitationMm: Double,
    val windSpeedKmh: Double,
    val weatherCode: Int,
    val hourlyForecast: List<HourlyForecast>,
)

data class HourlyForecast(
    val time: String,
    val temperatureC: Double,
    val cloudCoverPct: Int,
    val precipitationMm: Double,
    val weatherCode: Int,
)

data class MoonInfo(
    val phaseName: String,
    val illuminationPct: Double,
    val ageInDays: Double,
    val emoji: String,
    val rise: String?,
    val set: String?,
    val goodForAstro: Boolean,
)

data class SunInfo(
    val sunrise: String?,
    val sunset: String?,
)

data class MilkyWayInfo(
    val visible: Boolean,
    val seasonBest: Boolean,
    val coreRise: String?,
    val coreSet: String?,
    val darkWindow: String?,
)

data class PhotoWindow(
    val startTime: String,
    val endTime: String,
    val hours: Int,
    val avgCloudPct: Int,
    val rating: Int,  // 3=Excellent, 2=Good, 1=Fair
)

data class BortleInfo(
    val bortleClass: Double,
    val mpsas: Double,
    val category: String,
    val milkyWayQuality: String,
)

data class DewPointInfo(
    val dewPointC: Double,
    val temperatureC: Double,
    val spreadC: Double,
    val risk: DewRisk,
)

enum class DewRisk { NONE, WARNING, CRITICAL }

data class TwilightInfo(
    val civilEnd: String?,      // time civil twilight ends (evening)
    val nauticalEnd: String?,   // time nautical twilight ends (evening)
    val astroEnd: String?,      // time astronomical twilight ends (evening)
    val astroStart: String?,    // time astronomical twilight starts (morning)
    val nauticalStart: String?, // time nautical twilight starts (morning)
    val civilStart: String?,    // time civil twilight starts (morning)
)

data class PlanetInfo(
    val name: String,
    val emoji: String,
    val altitude: Double,       // max altitude during night
    val rise: String?,
    val set: String?,
    val visible: Boolean,
)

// ── Moon phase calculation ───────────────────────────────────────────────────

object MoonPhase {

    /** Returns the moon age in days (0–29.53) using a simplified algorithm. */
    fun moonAge(date: LocalDate = LocalDate.now()): Double {
        // Reference new moon: January 6, 2000 18:14 UTC
        val refYear = 2000
        val refMonth = 1
        val refDay = 6.0 + 18.0 / 24.0 + 14.0 / 1440.0
        val synodicMonth = 29.53058770576

        val y = date.year
        val m = date.monthValue
        val d = date.dayOfMonth.toDouble()

        val jdNow = julianDay(y, m, d)
        val jdRef = julianDay(refYear, refMonth, refDay)

        val daysSinceRef = jdNow - jdRef
        return ((daysSinceRef % synodicMonth) + synodicMonth) % synodicMonth
    }

    fun illumination(age: Double): Double {
        return (1.0 - cos(2.0 * PI * age / 29.53058770576)) / 2.0 * 100.0
    }

    fun phaseName(age: Double): String = when {
        age < 1.85  -> "New Moon"
        age < 7.38  -> "Waxing Crescent"
        age < 9.23  -> "First Quarter"
        age < 14.77 -> "Waxing Gibbous"
        age < 16.61 -> "Full Moon"
        age < 22.15 -> "Waning Gibbous"
        age < 23.99 -> "Last Quarter"
        age < 27.68 -> "Waning Crescent"
        else        -> "New Moon"
    }

    fun emoji(age: Double): String = when {
        age < 1.85  -> "🌑"
        age < 7.38  -> "🌒"
        age < 9.23  -> "🌓"
        age < 14.77 -> "🌔"
        age < 16.61 -> "🌕"
        age < 22.15 -> "🌖"
        age < 23.99 -> "🌗"
        age < 27.68 -> "🌘"
        else        -> "🌑"
    }

    /** Good for astrophotography when illumination < 25% */
    fun goodForAstro(illumination: Double): Boolean = illumination < AppConfig.MOON_GOOD_ASTRO_THRESHOLD

    private fun julianDay(year: Int, month: Int, day: Double): Double {
        var y = year
        var m = month
        if (m <= 2) { y -= 1; m += 12 }
        val a = y / 100
        val b = 2 - a + a / 4
        return (365.25 * (y + 4716)).toInt() + (30.6001 * (m + 1)).toInt() + day + b - 1524.5
    }
}

// ── Astro calculator (Milky Way + photo windows) ─────────────────────────────

object AstroCalculator {
    private const val GC_RA_DEG = 266.417   // Galactic center RA (17h 45m 40s)
    private const val GC_DEC_DEG = -29.008  // Galactic center Dec (-29° 00' 28")

    /** Approximate moon RA & Dec for a given date + UTC hour (low-precision). */
    fun moonPosition(date: LocalDate, utcHours: Double = 12.0): Pair<Double, Double> {
        val d = daysSinceJ2000(date) + utcHours / 24.0
        // Orbital elements (simplified)
        val L0 = (218.316 + 13.176396 * d) % 360          // mean longitude
        val M  = Math.toRadians(((134.963 + 13.064993 * d) % 360 + 360) % 360)  // mean anomaly
        val F  = Math.toRadians(((93.272 + 13.229350 * d)  % 360 + 360) % 360)  // argument of latitude
        val eclLon = Math.toRadians(((L0 + 6.289 * sin(M)) % 360 + 360) % 360)
        val eclLat = Math.toRadians(5.128 * sin(F))
        val eps = Math.toRadians(23.439 - 0.0000004 * d)

        val ra = (Math.toDegrees(
            atan2(
                sin(eclLon) * cos(eps) - tan(eclLat) * sin(eps),
                cos(eclLon),
            )
        ) + 360) % 360
        val dec = Math.toDegrees(
            asin(sin(eclLat) * cos(eps) + cos(eclLat) * sin(eps) * sin(eclLon))
        )
        return ra to dec
    }

    /**
     * Compute approximate moon rise and set times (local hours) for a date.
     * Returns pair of (rise, set) formatted strings, each nullable.
     */
    fun moonRiseSet(lat: Double, lon: Double, date: LocalDate): Pair<String?, String?> {
        val tz = date.atStartOfDay(ZoneId.systemDefault()).offset.totalSeconds / 3600.0
        var rise: Double? = null
        var set: Double? = null
        var prevAlt: Double? = null

        // Scan 0..24h local time in small steps
        var t = 0.0
        while (t <= 24.5) {
            val utcH = t - tz
            val (moonRa, moonDec) = moonPosition(date, utcH)
            val siderealTime = lst(date, utcH, lon)
            val alt = altitude(lat, moonDec, siderealTime - moonRa)

            if (prevAlt != null) {
                if (prevAlt < 0 && alt >= 0 && rise == null) {
                    // Linear interpolation for rise
                    val frac = -prevAlt / (alt - prevAlt)
                    rise = (t - 0.25) + frac * 0.25
                }
                if (prevAlt >= 0 && alt < 0 && set == null) {
                    val frac = prevAlt / (prevAlt - alt)
                    set = (t - 0.25) + frac * 0.25
                }
            }
            prevAlt = alt
            t += 0.25 // 15-minute steps
        }

        return Pair(rise?.let { fmtHour(it) }, set?.let { fmtHour(it) })
    }

    /** Approximate sun RA & Dec for a given date (accuracy ~1°). */
    fun sunPosition(date: LocalDate): Pair<Double, Double> {
        val n = daysSinceJ2000(date)
        val L = (280.460 + 0.9856474 * n) % 360
        val g = Math.toRadians((357.528 + 0.9856003 * n) % 360)
        val eclLon = Math.toRadians(((L + 1.915 * sin(g) + 0.020 * sin(2 * g)) % 360 + 360) % 360)
        val eps = Math.toRadians(23.439 - 0.0000004 * n)
        val ra = (Math.toDegrees(atan2(cos(eps) * sin(eclLon), cos(eclLon))) + 360) % 360
        val dec = Math.toDegrees(asin(sin(eps) * sin(eclLon)))
        return ra to dec
    }

    /** Altitude of a celestial object above the horizon (degrees). */
    fun altitude(latDeg: Double, decDeg: Double, haDeg: Double): Double {
        val lat = Math.toRadians(latDeg)
        val dec = Math.toRadians(decDeg)
        val ha = Math.toRadians(haDeg)
        return Math.toDegrees(asin(sin(lat) * sin(dec) + cos(lat) * cos(dec) * cos(ha)))
    }

    /** Local Sidereal Time in degrees. */
    fun lst(date: LocalDate, utcHours: Double, lonDeg: Double): Double {
        val d = daysSinceJ2000(date) + utcHours / 24.0
        val gmst = (280.46061837 + 360.98564736629 * d) % 360
        return (gmst + lonDeg + 720) % 360
    }

    private fun daysSinceJ2000(date: LocalDate): Double {
        var y = date.year; var m = date.monthValue
        val d0 = date.dayOfMonth.toDouble()
        if (m <= 2) { y -= 1; m += 12 }
        val a = y / 100; val b = 2 - a + a / 4
        val jd = (365.25 * (y + 4716)).toInt() + (30.6001 * (m + 1)).toInt() + d0 + b - 1524.5
        return jd - 2451545.0
    }

    fun parseIsoHour(iso: String?): Double? {
        if (iso.isNullOrEmpty()) return null
        val time = iso.substringAfter("T", "")
        if (time.isEmpty()) return null
        val parts = time.split(":")
        val h = parts[0].toDoubleOrNull() ?: return null
        val min = parts.getOrNull(1)?.toDoubleOrNull() ?: 0.0
        return h + min / 60.0
    }

    fun fmtHour(h: Double): String {
        val n = ((h % 24) + 24) % 24
        return String.format(Locale.US, "%02d:%02d", n.toInt(), ((n - n.toInt()) * 60).toInt())
    }

    // ── Milky Way core visibility ────────────────────────────────

    fun milkyWayWindow(
        lat: Double, lon: Double, date: LocalDate,
        sunriseIso: String?, sunsetIso: String?,
    ): MilkyWayInfo {
        val cosH = -tan(Math.toRadians(lat)) * tan(Math.toRadians(GC_DEC_DEG))
        if (cosH > 1.0) return MilkyWayInfo(false, false, null, null, null)

        val tz = date.atStartOfDay(ZoneId.systemDefault()).offset.totalSeconds / 3600.0
        val sunsetLocal = parseIsoHour(sunsetIso) ?: 18.0
        val sunriseLocal = parseIsoHour(sunriseIso) ?: 6.0
        val sunriseNext = sunriseLocal + 24.0
        val (sunRa, sunDec) = sunPosition(date)

        var mwStart: Double? = null
        var mwEnd: Double? = null
        var gcRise: Double? = null
        var gcSet: Double? = null
        var prevGcUp = false

        var t = sunsetLocal - AppConfig.DARK_WINDOW_OFFSET_HOURS
        while (t <= sunriseNext + AppConfig.DARK_WINDOW_OFFSET_HOURS) {
            val utcH = t - tz
            val siderealTime = lst(date, utcH, lon)
            val sunAlt = altitude(lat, sunDec, siderealTime - sunRa)
            val gcAlt = altitude(lat, GC_DEC_DEG, siderealTime - GC_RA_DEG)

            val isDark = sunAlt < AppConfig.ASTRONOMICAL_TWILIGHT_DEG
            val gcUp = gcAlt > AppConfig.GC_ALTITUDE_THRESHOLD

            if (gcUp && !prevGcUp) gcRise = t
            if (!gcUp && prevGcUp) gcSet = t
            prevGcUp = gcUp

            if (isDark && gcUp) {
                if (mwStart == null) mwStart = t
                mwEnd = t
            }
            t += AppConfig.MW_SCAN_STEP_MINUTES / 60.0
        }

        val month = date.monthValue
        val seasonBest = if (lat >= 0) month in 3..10 else month in 1..4 || month in 9..12

        return MilkyWayInfo(
            visible = mwStart != null,
            seasonBest = seasonBest,
            coreRise = gcRise?.let { fmtHour(it) },
            coreSet = gcSet?.let { fmtHour(it) },
            darkWindow = if (mwStart != null && mwEnd != null)
                "${fmtHour(mwStart)} – ${fmtHour(mwEnd)}" else null,
        )
    }

    // ── Best photography windows ─────────────────────────────────

    fun bestPhotoWindows(
        hourly: List<HourlyForecast>,
        sunriseIso: String?, sunsetIso: String?,
    ): List<PhotoWindow> {
        val sunsetH = parseIsoHour(sunsetIso) ?: return emptyList()
        val sunriseH = parseIsoHour(sunriseIso) ?: return emptyList()
        val darkAfter = sunsetH + AppConfig.DARK_WINDOW_OFFSET_HOURS
        val darkBefore = sunriseH - AppConfig.DARK_WINDOW_OFFSET_HOURS

        val dark = hourly.filter { h ->
            val hh = parseIsoHour(h.time) ?: return@filter false
            (hh >= darkAfter || hh <= darkBefore) && h.precipitationMm <= AppConfig.PRECIPITATION_CLEAR_THRESHOLD
        }
        if (dark.isEmpty()) return emptyList()

        val groups = mutableListOf<MutableList<HourlyForecast>>()
        for (h in dark) {
            val hh = parseIsoHour(h.time) ?: continue
            val prev = groups.lastOrNull()?.lastOrNull()?.let { parseIsoHour(it.time) }
            if (prev != null) {
                val diff = hh - prev
                if (diff in 0.5..1.5 || diff + 24 in 0.5..1.5) {
                    groups.last().add(h); continue
                }
            }
            groups.add(mutableListOf(h))
        }

        return groups.map { w ->
            val avgCloud = w.map { it.cloudCoverPct }.average().toInt()
            val score = 100 - avgCloud
            PhotoWindow(
                startTime = w.first().time.substringAfter("T").take(5),
                endTime = w.last().time.substringAfter("T").take(5),
                hours = w.size,
                avgCloudPct = avgCloud,
                rating = when { score >= AppConfig.PHOTO_WINDOW_EXCELLENT -> 3; score >= AppConfig.PHOTO_WINDOW_GOOD -> 2; else -> 1 },
            )
        }.sortedByDescending { it.hours * (100 - it.avgCloudPct) }.take(AppConfig.MAX_PHOTO_WINDOWS)
    }

    // ── Dew point calculation ────────────────────────────────────

    /** Magnus formula approximation for dew point (°C). */
    fun dewPoint(temperatureC: Double, humidityPct: Int): DewPointInfo {
        val rh = humidityPct.coerceIn(1, 100).toDouble()
        val a = 17.27
        val b = 237.7
        val gamma = (a * temperatureC) / (b + temperatureC) + ln(rh / 100.0)
        val dp = (b * gamma) / (a - gamma)
        val spread = temperatureC - dp
        val risk = when {
            spread <= AppConfig.DEW_POINT_CRITICAL_SPREAD_C -> DewRisk.CRITICAL
            spread <= AppConfig.DEW_POINT_WARN_SPREAD_C -> DewRisk.WARNING
            else -> DewRisk.NONE
        }
        return DewPointInfo(dp, temperatureC, spread, risk)
    }

    // ── Twilight phase boundaries ────────────────────────────────

    fun twilightPhases(
        lat: Double, lon: Double, date: LocalDate,
        sunriseIso: String?, sunsetIso: String?,
    ): TwilightInfo {
        val tz = date.atStartOfDay(ZoneId.systemDefault()).offset.totalSeconds / 3600.0
        val (sunRa, sunDec) = sunPosition(date)
        val sunsetLocal = parseIsoHour(sunsetIso) ?: 18.0
        val sunriseLocal = parseIsoHour(sunriseIso) ?: 6.0

        // Scan evening: sunset → sunset+3h for civil/nautical/astro boundaries
        var civilEnd: Double? = null
        var nauticalEnd: Double? = null
        var astroEnd: Double? = null
        var t = sunsetLocal
        while (t <= sunsetLocal + 4.0) {
            val utcH = t - tz
            val siderealTime = lst(date, utcH, lon)
            val sunAlt = altitude(lat, sunDec, siderealTime - sunRa)
            if (civilEnd == null && sunAlt < AppConfig.CIVIL_TWILIGHT_DEG) civilEnd = t
            if (nauticalEnd == null && sunAlt < AppConfig.NAUTICAL_TWILIGHT_DEG) nauticalEnd = t
            if (astroEnd == null && sunAlt < AppConfig.ASTRONOMICAL_TWILIGHT_DEG) astroEnd = t
            t += 1.0 / 60.0  // 1-minute steps
        }

        // Scan morning: sunrise-3h → sunrise for astro/nautical/civil starts
        var astroStart: Double? = null
        var nauticalStart: Double? = null
        var civilStart: Double? = null
        t = sunriseLocal + 24.0 - 4.0  // handle wrap-around for next-day sunrise
        while (t <= sunriseLocal + 24.0) {
            val utcH = t - tz
            val siderealTime = lst(date, utcH, lon)
            val sunAlt = altitude(lat, sunDec, siderealTime - sunRa)
            if (astroStart == null && sunAlt > AppConfig.ASTRONOMICAL_TWILIGHT_DEG) astroStart = t
            if (nauticalStart == null && sunAlt > AppConfig.NAUTICAL_TWILIGHT_DEG) nauticalStart = t
            if (civilStart == null && sunAlt > AppConfig.CIVIL_TWILIGHT_DEG) civilStart = t
            t += 1.0 / 60.0
        }

        return TwilightInfo(
            civilEnd = civilEnd?.let { fmtHour(it) },
            nauticalEnd = nauticalEnd?.let { fmtHour(it) },
            astroEnd = astroEnd?.let { fmtHour(it) },
            astroStart = astroStart?.let { fmtHour(it) },
            nauticalStart = nauticalStart?.let { fmtHour(it) },
            civilStart = civilStart?.let { fmtHour(it) },
        )
    }

    // ── Planetary positions ──────────────────────────────────────

    private data class PlanetElements(
        val name: String, val emoji: String,
        val N: Double, val i: Double, val w: Double, val a: Double, val e: Double, val M: Double,
    )

    /** Simplified planetary orbital elements (mean J2000 + linear rate × d). */
    private fun planetElements(date: LocalDate): List<PlanetElements> {
        val d = daysSinceJ2000(date)
        return listOf(
            // Venus
            PlanetElements("Venus", "♀️",
                N = (76.6799 + 2.46590e-5 * d) % 360,
                i = 3.3946 + 2.75e-8 * d,
                w = (54.8910 + 1.38374e-5 * d) % 360,
                a = 0.72333,
                e = 0.006773 - 1.302e-9 * d,
                M = ((48.0052 + 1.6021302244 * d) % 360 + 360) % 360,
            ),
            // Mars
            PlanetElements("Mars", "♂️",
                N = (49.5574 + 2.11081e-5 * d) % 360,
                i = 1.8497 - 1.78e-8 * d,
                w = (286.5016 + 2.92961e-5 * d) % 360,
                a = 1.52368,
                e = 0.093405 + 2.516e-9 * d,
                M = ((18.6021 + 0.5240207766 * d) % 360 + 360) % 360,
            ),
            // Jupiter
            PlanetElements("Jupiter", "♃",
                N = (100.4542 + 2.76854e-5 * d) % 360,
                i = 1.3030 - 1.557e-7 * d,
                w = (273.8777 + 1.64505e-5 * d) % 360,
                a = 5.20256,
                e = 0.048498 + 4.469e-9 * d,
                M = ((19.8950 + 0.0830853001 * d) % 360 + 360) % 360,
            ),
            // Saturn
            PlanetElements("Saturn", "♄",
                N = (113.6634 + 2.38980e-5 * d) % 360,
                i = 2.4886 - 1.081e-7 * d,
                w = (339.3939 + 2.97661e-5 * d) % 360,
                a = 9.55475,
                e = 0.055546 - 9.499e-9 * d,
                M = ((316.9670 + 0.0334442282 * d) % 360 + 360) % 360,
            ),
        )
    }

    /** Compute ecliptic longitude of a planet from its Keplerian elements. */
    private fun planetEclipticLon(p: PlanetElements): Pair<Double, Double> {
        val mRad = Math.toRadians(p.M)
        // Kepler's equation: E ≈ M + e·sin(M) (first-order)
        val E = p.M + Math.toDegrees(p.e * sin(mRad))
        val eRad = Math.toRadians(E)
        // Distance and true anomaly
        val xv = p.a * (cos(eRad) - p.e)
        val yv = p.a * (sqrt(1 - p.e * p.e) * sin(eRad))
        val v = Math.toDegrees(atan2(yv, xv))
        val r = sqrt(xv * xv + yv * yv)
        // Heliocentric ecliptic coordinates
        val wRad = Math.toRadians(p.w)
        val nRad = Math.toRadians(p.N)
        val iRad = Math.toRadians(p.i)
        val vwRad = Math.toRadians(v + p.w)
        val xh = r * (cos(nRad) * cos(vwRad) - sin(nRad) * sin(vwRad) * cos(iRad))
        val yh = r * (sin(nRad) * cos(vwRad) + cos(nRad) * sin(vwRad) * cos(iRad))
        val zh = r * sin(vwRad) * sin(iRad)
        val lonEcl = (Math.toDegrees(atan2(yh, xh)) + 360) % 360
        val latEcl = Math.toDegrees(atan2(zh, sqrt(xh * xh + yh * yh)))
        return lonEcl to latEcl
    }

    fun visiblePlanets(lat: Double, lon: Double, date: LocalDate,
                       sunsetIso: String?, sunriseIso: String?): List<PlanetInfo> {
        val d = daysSinceJ2000(date)
        val tz = date.atStartOfDay(ZoneId.systemDefault()).offset.totalSeconds / 3600.0
        val sunsetLocal = parseIsoHour(sunsetIso) ?: 18.0
        val sunriseLocal = parseIsoHour(sunriseIso) ?: 6.0
        val sunriseNext = sunriseLocal + 24.0

        // Sun ecliptic longitude for geocentric conversion
        val (sunRa, sunDec) = sunPosition(date)
        val sunLon = {
            val n = d
            val L = (280.460 + 0.9856474 * n) % 360
            val g = Math.toRadians((357.528 + 0.9856003 * n) % 360)
            ((L + 1.915 * sin(g) + 0.020 * sin(2 * g)) % 360 + 360) % 360
        }()

        val eps = Math.toRadians(23.439 - 0.0000004 * d)
        val elements = planetElements(date)

        return elements.map { p ->
            val (helioLon, helioLat) = planetEclipticLon(p)
            // Approximate geocentric ecliptic longitude (simple: subtract sun)
            // This is a rough approximation suitable for visibility checks
            val geoLonRad = Math.toRadians(helioLon)
            val geoLatRad = Math.toRadians(helioLat)

            val ra = (Math.toDegrees(atan2(
                sin(geoLonRad) * cos(eps) - tan(geoLatRad) * sin(eps),
                cos(geoLonRad),
            )) + 360) % 360
            val dec = Math.toDegrees(asin(
                sin(geoLatRad) * cos(eps) + cos(geoLatRad) * sin(eps) * sin(geoLonRad)
            ))

            // Find max altitude and rise/set during night
            var maxAlt = -90.0
            var riseTime: Double? = null
            var setTime: Double? = null
            var prevAlt: Double? = null

            var t = sunsetLocal
            while (t <= sunriseNext) {
                val utcH = t - tz
                val siderealTime = lst(date, utcH, lon)
                val alt = altitude(lat, dec, siderealTime - ra)
                if (alt > maxAlt) maxAlt = alt

                if (prevAlt != null) {
                    if (prevAlt < AppConfig.PLANET_MIN_ALTITUDE_DEG && alt >= AppConfig.PLANET_MIN_ALTITUDE_DEG && riseTime == null)
                        riseTime = t
                    if (prevAlt >= AppConfig.PLANET_MIN_ALTITUDE_DEG && alt < AppConfig.PLANET_MIN_ALTITUDE_DEG && setTime == null)
                        setTime = t
                }
                prevAlt = alt
                t += 0.25 // 15-min steps
            }

            PlanetInfo(
                name = p.name,
                emoji = p.emoji,
                altitude = maxAlt,
                rise = riseTime?.let { fmtHour(it) },
                set = setTime?.let { fmtHour(it) },
                visible = maxAlt >= AppConfig.PLANET_MIN_ALTITUDE_DEG,
            )
        }.filter { it.visible }
    }
}

// ── Dashboard manager ────────────────────────────────────────────────────────

class AstroDashboardManager(private val context: Context) {

    companion object {
        private const val TAG = "AstroDash"
        private const val CACHE_PREFS = "dashboard_cache"
        private const val KEY_CACHE_JSON = "cached_state"
        private const val KEY_CACHE_TIME = "cached_at"
        private const val KEY_CACHE_DATE = "cached_date"
    }

    private val cachePrefs = context.getSharedPreferences(CACHE_PREFS, Context.MODE_PRIVATE)

    private val _state = MutableStateFlow(DashboardState())
    val state: StateFlow<DashboardState> = _state

    init {
        // Restore from cache on startup
        loadCache()?.let { _state.value = it }
    }

    private fun loadCache(): DashboardState? {
        val age = System.currentTimeMillis() - cachePrefs.getLong(KEY_CACHE_TIME, 0)
        if (age > AppConfig.DASHBOARD_CACHE_MAX_AGE_MS) return null
        val json = cachePrefs.getString(KEY_CACHE_JSON, null) ?: return null
        return try {
            val j = JSONObject(json)
            val loc = j.optJSONObject("loc")?.let {
                LocationInfo(it.getDouble("lat"), it.getDouble("lon"),
                    it.optDouble("alt").takeIf { a -> !a.isNaN() },
                    it.optString("city").takeIf { c -> c.isNotEmpty() })
            }
            // Return a lightweight cached state (location + date only, triggers full refresh)
            DashboardState(location = loc, selectedDate = LocalDate.parse(
                cachePrefs.getString(KEY_CACHE_DATE, LocalDate.now().toString())))
        } catch (_: Exception) { null }
    }

    private fun saveCache(state: DashboardState) {
        val loc = state.location ?: return
        val j = JSONObject().apply {
            put("loc", JSONObject().apply {
                put("lat", loc.latitude)
                put("lon", loc.longitude)
                loc.altitude?.let { put("alt", it) }
                loc.cityName?.let { put("city", it) }
            })
        }
        cachePrefs.edit()
            .putString(KEY_CACHE_JSON, j.toString())
            .putLong(KEY_CACHE_TIME, System.currentTimeMillis())
            .putString(KEY_CACHE_DATE, state.selectedDate.toString())
            .apply()
    }

    @SuppressLint("MissingPermission")
    suspend fun refresh(date: LocalDate = LocalDate.now()) {
        _state.value = _state.value.copy(loading = true, error = null, weatherError = null, bortleError = null, selectedDate = date)
        try {
            val loc = getLocation()
            if (loc == null) {
                _state.value = _state.value.copy(
                    loading = false,
                    error = "Could not determine location. Make sure GPS is enabled.",
                )
                return
            }

            val locationInfo = LocationInfo(
                loc.latitude, loc.longitude, loc.altitude,
                cityName = reverseGeocode(loc.latitude, loc.longitude),
            )
            refreshInternal(locationInfo, date)
        } catch (e: Exception) {
            Log.e(TAG, "Dashboard refresh failed", e)
            _state.value = _state.value.copy(loading = false, error = e.message)
        }
    }

    /** Refresh using explicit coordinates (for planner session detail). */
    suspend fun refreshForLocation(lat: Double, lon: Double, cityName: String?, date: LocalDate) {
        _state.value = _state.value.copy(loading = true, error = null, weatherError = null, bortleError = null, selectedDate = date)
        try {
            val locationInfo = LocationInfo(lat, lon, cityName = cityName)
            refreshInternal(locationInfo, date)
        } catch (e: Exception) {
            Log.e(TAG, "Dashboard refresh failed", e)
            _state.value = _state.value.copy(loading = false, error = e.message)
        }
    }

    private suspend fun refreshInternal(locationInfo: LocationInfo, date: LocalDate) {
        val lat = locationInfo.latitude
        val lon = locationInfo.longitude
        val isToday = date == LocalDate.now()

        // Moon — use selected date
        val moonAge = MoonPhase.moonAge(date)
        val illum = MoonPhase.illumination(moonAge)

        // Fetch weather + astronomy from Open-Meteo (per-card error)
        val weatherResult = fetchWeather(lat, lon, date, isToday)
        val weatherError = if (weatherResult == null) "Weather data unavailable" else null
        val astroResult = fetchAstronomy(lat, lon, date)

        // Fetch light pollution / Bortle scale (per-card error)
        val bortleInfo = fetchLightPollution(lat, lon)
        val bortleError = if (bortleInfo == null) "Light pollution data unavailable" else null

        // Moon rise/set computed locally
        val (moonRise, moonSet) = AstroCalculator.moonRiseSet(lat, lon, date)

        val moonInfo = MoonInfo(
            phaseName = MoonPhase.phaseName(moonAge),
            illuminationPct = illum,
            ageInDays = moonAge,
            emoji = MoonPhase.emoji(moonAge),
            rise = moonRise,
            set = moonSet,
            goodForAstro = MoonPhase.goodForAstro(illum),
        )

        val sunInfo = SunInfo(
            sunrise = astroResult?.optString("sunrise", "")?.takeIf { it.isNotEmpty() },
            sunset = astroResult?.optString("sunset", "")?.takeIf { it.isNotEmpty() },
        )

        val milkyWayInfo = AstroCalculator.milkyWayWindow(
            lat = lat,
            lon = lon,
            date = date,
            sunriseIso = sunInfo.sunrise,
            sunsetIso = sunInfo.sunset,
        )

        val bestWindows = weatherResult?.let {
            AstroCalculator.bestPhotoWindows(
                hourly = it.hourlyForecast,
                sunriseIso = sunInfo.sunrise,
                sunsetIso = sunInfo.sunset,
            )
        } ?: emptyList()

        // Dew point from weather data
        val dewPointInfo = weatherResult?.let {
            if (it.humidity > 0)
                AstroCalculator.dewPoint(it.temperatureC, it.humidity)
            else null
        }

        // Twilight phases
        val twilightInfo = AstroCalculator.twilightPhases(
            lat, lon, date,
            sunInfo.sunrise, sunInfo.sunset,
        )

        // Visible planets
        val planets = AstroCalculator.visiblePlanets(
            lat, lon, date,
            sunInfo.sunset, sunInfo.sunrise,
        )

        _state.value = DashboardState(
            location = locationInfo,
            weather = weatherResult,
            moon = moonInfo,
            sun = sunInfo,
            bortle = bortleInfo,
            milkyWay = milkyWayInfo,
            bestWindows = bestWindows,
            dewPoint = dewPointInfo,
            twilight = twilightInfo,
            planets = planets,
            loading = false,
            weatherError = weatherError,
            bortleError = bortleError,
            selectedDate = date,
        )
        saveCache(_state.value)
    }

    @SuppressLint("MissingPermission")
    private fun getLocation(): Location? {
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        // Try GPS first, then network
        return lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)
            ?: lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
    }

    @Suppress("DEPRECATION")
    private suspend fun reverseGeocode(lat: Double, lon: Double): String? =
        withContext(Dispatchers.IO) {
            try {
                val geocoder = Geocoder(context, Locale.getDefault())
                val addresses = geocoder.getFromLocation(lat, lon, 1)
                addresses?.firstOrNull()?.let { addr ->
                    listOfNotNull(addr.locality, addr.adminArea, addr.countryCode)
                        .joinToString(", ")
                        .takeIf { it.isNotEmpty() }
                }
            } catch (_: Exception) {
                null
            }
        }

    private suspend fun fetchWeather(
        lat: Double, lon: Double, date: LocalDate, isToday: Boolean,
    ): WeatherInfo? =
        withContext(Dispatchers.IO) {
            try {
                val dateStr = date.format(DateTimeFormatter.ISO_LOCAL_DATE)
                val urlStr = if (isToday) {
                    "https://api.open-meteo.com/v1/forecast" +
                        "?latitude=$lat&longitude=$lon" +
                        "&current=temperature_2m,relative_humidity_2m,cloud_cover,precipitation,wind_speed_10m,weather_code" +
                        "&hourly=temperature_2m,cloud_cover,precipitation,weather_code" +
                        "&forecast_hours=12" +
                        "&timezone=auto"
                } else {
                    "https://api.open-meteo.com/v1/forecast" +
                        "?latitude=$lat&longitude=$lon" +
                        "&hourly=temperature_2m,cloud_cover,precipitation,weather_code" +
                        "&start_date=$dateStr&end_date=$dateStr" +
                        "&timezone=auto"
                }
                val url = URL(urlStr)
                val conn = url.openConnection() as HttpURLConnection
                conn.connectTimeout = AppConfig.API_CONNECT_TIMEOUT_MS
                conn.readTimeout = AppConfig.API_READ_TIMEOUT_MS
                try {
                    val json = JSONObject(conn.inputStream.bufferedReader().readText())

                    val hourly = json.optJSONObject("hourly")
                    val hourlyList = mutableListOf<HourlyForecast>()
                    if (hourly != null) {
                        val times = hourly.getJSONArray("time")
                        val temps = hourly.getJSONArray("temperature_2m")
                        val clouds = hourly.getJSONArray("cloud_cover")
                        val precip = hourly.getJSONArray("precipitation")
                        val codes = hourly.getJSONArray("weather_code")
                        val count = if (isToday) minOf(times.length(), AppConfig.HOURLY_FORECAST_CAP) else times.length()
                        for (i in 0 until count) {
                            hourlyList.add(
                                HourlyForecast(
                                    time = times.getString(i),
                                    temperatureC = temps.getDouble(i),
                                    cloudCoverPct = clouds.getInt(i),
                                    precipitationMm = precip.getDouble(i),
                                    weatherCode = codes.getInt(i),
                                )
                            )
                        }
                    }

                    if (isToday) {
                        val current = json.getJSONObject("current")
                        WeatherInfo(
                            temperatureC = current.getDouble("temperature_2m"),
                            humidity = current.getInt("relative_humidity_2m"),
                            cloudCoverPct = current.getInt("cloud_cover"),
                            precipitationMm = current.getDouble("precipitation"),
                            windSpeedKmh = current.getDouble("wind_speed_10m"),
                            weatherCode = current.getInt("weather_code"),
                            hourlyForecast = hourlyList,
                        )
                    } else {
                        // For future dates, summarize from hourly data
                        val avgTemp = hourlyList.map { it.temperatureC }.average()
                        val avgCloud = hourlyList.map { it.cloudCoverPct }.average().toInt()
                        val totalPrecip = hourlyList.sumOf { it.precipitationMm }
                        val avgWind = 0.0 // not available in this query
                        val dominantCode = hourlyList.groupBy { it.weatherCode }
                            .maxByOrNull { it.value.size }?.key ?: 0
                        WeatherInfo(
                            temperatureC = avgTemp,
                            humidity = 0, // not available for forecast-only
                            cloudCoverPct = avgCloud,
                            precipitationMm = totalPrecip,
                            windSpeedKmh = avgWind,
                            weatherCode = dominantCode,
                            hourlyForecast = hourlyList,
                        )
                    }
                } finally {
                    conn.disconnect()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Weather fetch failed", e)
                null
            }
        }

    private suspend fun fetchAstronomy(lat: Double, lon: Double, date: LocalDate): JSONObject? =
        withContext(Dispatchers.IO) {
            try {
                val dateStr = date.format(DateTimeFormatter.ISO_LOCAL_DATE)
                val url = URL(
                    "https://api.open-meteo.com/v1/forecast" +
                        "?latitude=$lat&longitude=$lon" +
                        "&daily=sunrise,sunset" +
                        "&start_date=$dateStr&end_date=$dateStr" +
                        "&timezone=auto"
                )
                val conn = url.openConnection() as HttpURLConnection
                conn.connectTimeout = AppConfig.API_CONNECT_TIMEOUT_MS
                conn.readTimeout = AppConfig.API_READ_TIMEOUT_MS
                try {
                    val json = JSONObject(conn.inputStream.bufferedReader().readText())
                    val daily = json.getJSONObject("daily")
                    JSONObject().apply {
                        put("sunrise", daily.getJSONArray("sunrise").optString(0, ""))
                        put("sunset", daily.getJSONArray("sunset").optString(0, ""))
                    }
                } finally {
                    conn.disconnect()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Astronomy fetch failed", e)
                null
            }
        }

    private suspend fun fetchLightPollution(lat: Double, lon: Double): BortleInfo? =
        withContext(Dispatchers.IO) {
            try {
                val url = URL(
                    "https://lightpollutionmap.app/api/lightpollution" +
                        "?lat=$lat&lon=$lon"
                )
                val conn = url.openConnection() as HttpURLConnection
                conn.connectTimeout = AppConfig.API_CONNECT_TIMEOUT_MS
                conn.readTimeout = AppConfig.API_READ_TIMEOUT_MS
                conn.setRequestProperty("Origin", "https://lightpollutionmap.app")
                conn.setRequestProperty("Referer", "https://lightpollutionmap.app/")
                conn.setRequestProperty("User-Agent",
                    "Mozilla/5.0 (Linux; Android) AppleWebKit/537.36 Chrome/120.0.0.0 Mobile Safari/537.36")
                try {
                    val json = JSONObject(conn.inputStream.bufferedReader().readText())
                    val bortle = json.getDouble("bortleScale")
                    val brightness = json.optJSONObject("brightness")
                    val mpsas = brightness?.optDouble("mpsas", 0.0) ?: 0.0
                    val info = json.optJSONObject("lightPollutionInfo")
                    val category = info?.optString("category", "") ?: ""
                    val milkyWay = info?.optString("milkyWay", "") ?: ""
                    BortleInfo(
                        bortleClass = bortle,
                        mpsas = mpsas,
                        category = category.substringAfterLast("."),
                        milkyWayQuality = milkyWay.substringAfterLast("."),
                    )
                } finally {
                    conn.disconnect()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Light pollution fetch failed", e)
                null
            }
        }
}

// ── Weather code to description ──────────────────────────────────────────────

fun weatherDescription(code: Int): String = when (code) {
    0 -> "Clear sky"
    1 -> "Mainly clear"
    2 -> "Partly cloudy"
    3 -> "Overcast"
    45, 48 -> "Fog"
    51, 53, 55 -> "Drizzle"
    61, 63, 65 -> "Rain"
    66, 67 -> "Freezing rain"
    71, 73, 75 -> "Snowfall"
    77 -> "Snow grains"
    80, 81, 82 -> "Rain showers"
    85, 86 -> "Snow showers"
    95 -> "Thunderstorm"
    96, 99 -> "Thunderstorm w/ hail"
    else -> "Unknown"
}

fun weatherEmoji(code: Int): String = when (code) {
    0 -> "☀️"
    1 -> "🌤️"
    2 -> "⛅"
    3 -> "☁️"
    45, 48 -> "🌫️"
    51, 53, 55 -> "🌦️"
    61, 63, 65 -> "🌧️"
    66, 67 -> "🌧️"
    71, 73, 75 -> "🌨️"
    77 -> "🌨️"
    80, 81, 82 -> "🌧️"
    85, 86 -> "🌨️"
    95 -> "⛈️"
    96, 99 -> "⛈️"
    else -> "🌡️"
}
