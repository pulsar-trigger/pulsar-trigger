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
    val milkyWay: MilkyWayInfo? = null,
    val bestWindows: List<PhotoWindow> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
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
}

// ── Dashboard manager ────────────────────────────────────────────────────────

class AstroDashboardManager(private val context: Context) {

    companion object {
        private const val TAG = "AstroDash"
    }

    private val _state = MutableStateFlow(DashboardState())
    val state: StateFlow<DashboardState> = _state

    @SuppressLint("MissingPermission")
    suspend fun refresh(date: LocalDate = LocalDate.now()) {
        _state.value = _state.value.copy(loading = true, error = null, selectedDate = date)
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
            val isToday = date == LocalDate.now()

            // Moon — use selected date
            val moonAge = MoonPhase.moonAge(date)
            val illum = MoonPhase.illumination(moonAge)

            // Fetch weather + astronomy from Open-Meteo
            val weatherResult = fetchWeather(loc.latitude, loc.longitude, date, isToday)
            val astroResult = fetchAstronomy(loc.latitude, loc.longitude, date)

            // Moon rise/set computed locally
            val (moonRise, moonSet) = AstroCalculator.moonRiseSet(loc.latitude, loc.longitude, date)

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
                lat = loc.latitude,
                lon = loc.longitude,
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

            _state.value = DashboardState(
                location = locationInfo,
                weather = weatherResult,
                moon = moonInfo,
                sun = sunInfo,
                milkyWay = milkyWayInfo,
                bestWindows = bestWindows,
                loading = false,
                selectedDate = date,
            )
        } catch (e: Exception) {
            Log.e(TAG, "Dashboard refresh failed", e)
            _state.value = _state.value.copy(loading = false, error = e.message)
        }
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
