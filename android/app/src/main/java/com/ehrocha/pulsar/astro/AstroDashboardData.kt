/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Copyright (C) 2026 Pulsar Trigger contributors
 */

package com.ehrocha.pulsar.astro

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationManager
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
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
    val bortle: BortleInfo? = null,
    val loading: Boolean = false,
    val error: String? = null,
    val selectedDate: LocalDate = LocalDate.now(),
)

data class LocationInfo(
    val latitude: Double,
    val longitude: Double,
    val altitude: Double? = null,
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

data class BortleInfo(
    val classNumber: Int,
    val className: String,
    val description: String,
    val color: Long,  // ARGB color
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
    fun goodForAstro(illumination: Double): Boolean = illumination < 25.0

    private fun julianDay(year: Int, month: Int, day: Double): Double {
        var y = year
        var m = month
        if (m <= 2) { y -= 1; m += 12 }
        val a = y / 100
        val b = 2 - a + a / 4
        return (365.25 * (y + 4716)).toInt() + (30.6001 * (m + 1)).toInt() + day + b - 1524.5
    }
}

// ── Bortle scale ─────────────────────────────────────────────────────────────

object BortleScale {

    data class BortleClass(
        val number: Int,
        val name: String,
        val description: String,
        val color: Long,
    )

    val classes = listOf(
        BortleClass(1, "Excellent Dark Sky", "Zodiacal light, gegenschein visible; Milky Way casts shadows", 0xFF000000),
        BortleClass(2, "Typical Dark Sky", "Airglow visible; Milky Way highly structured", 0xFF1A1A2E),
        BortleClass(3, "Rural Sky", "Some light pollution on horizon; Milky Way still appears complex", 0xFF16213E),
        BortleClass(4, "Rural/Suburban", "Light pollution domes visible; Milky Way visible but lacks detail", 0xFF0F3460),
        BortleClass(5, "Suburban Sky", "Milky Way very weak; only bright Messier objects visible", 0xFF533483),
        BortleClass(6, "Bright Suburban", "Milky Way only visible near zenith; sky glows whitish", 0xFF8B5E3C),
        BortleClass(7, "Suburban/Urban", "Milky Way invisible; sky has veil of light", 0xFFE94560),
        BortleClass(8, "City Sky", "Sky glows white or orange; only bright planets visible", 0xFFFF6600),
        BortleClass(9, "Inner City Sky", "Only Moon, planets, and brightest stars visible", 0xFFFFFFFF),
    )

    fun fromRadiance(radiance: Double): BortleClass {
        // Approximate mapping from VIIRS radiance (nW/cm²/sr) to Bortle class
        return when {
            radiance < 0.25  -> classes[0]   // Bortle 1
            radiance < 0.40  -> classes[1]   // Bortle 2
            radiance < 0.80  -> classes[2]   // Bortle 3
            radiance < 1.50  -> classes[3]   // Bortle 4
            radiance < 3.00  -> classes[4]   // Bortle 5
            radiance < 6.00  -> classes[5]   // Bortle 6
            radiance < 15.0  -> classes[6]   // Bortle 7
            radiance < 40.0  -> classes[7]   // Bortle 8
            else             -> classes[8]   // Bortle 9
        }
    }

    fun getClass(number: Int): BortleClass = classes.getOrElse(number - 1) { classes[4] }
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

            val locationInfo = LocationInfo(loc.latitude, loc.longitude, loc.altitude)
            val isToday = date == LocalDate.now()

            // Moon — use selected date
            val moonAge = MoonPhase.moonAge(date)
            val illum = MoonPhase.illumination(moonAge)

            // Fetch weather + astronomy from Open-Meteo
            val weatherResult = fetchWeather(loc.latitude, loc.longitude, date, isToday)
            val astroResult = fetchAstronomy(loc.latitude, loc.longitude, date)
            val bortleResult = fetchLightPollution(loc.latitude, loc.longitude)

            val moonInfo = MoonInfo(
                phaseName = MoonPhase.phaseName(moonAge),
                illuminationPct = illum,
                ageInDays = moonAge,
                emoji = MoonPhase.emoji(moonAge),
                rise = astroResult?.optString("moonrise", "")?.takeIf { it.isNotEmpty() },
                set = astroResult?.optString("moonset", "")?.takeIf { it.isNotEmpty() },
                goodForAstro = MoonPhase.goodForAstro(illum),
            )

            _state.value = DashboardState(
                location = locationInfo,
                weather = weatherResult,
                moon = moonInfo,
                bortle = bortleResult,
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
                conn.connectTimeout = 10_000
                conn.readTimeout = 10_000
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
                        val count = if (isToday) minOf(times.length(), 12) else times.length()
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
                        "&daily=sunrise,sunset,moonrise,moonset" +
                        "&start_date=$dateStr&end_date=$dateStr" +
                        "&timezone=auto"
                )
                val conn = url.openConnection() as HttpURLConnection
                conn.connectTimeout = 10_000
                conn.readTimeout = 10_000
                try {
                    val json = JSONObject(conn.inputStream.bufferedReader().readText())
                    val daily = json.getJSONObject("daily")
                    JSONObject().apply {
                        put("sunrise", daily.getJSONArray("sunrise").optString(0, ""))
                        put("sunset", daily.getJSONArray("sunset").optString(0, ""))
                        put("moonrise", daily.getJSONArray("moonrise").optString(0, ""))
                        put("moonset", daily.getJSONArray("moonset").optString(0, ""))
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
                // Use lightpollutionmap.info VIIRS data
                val url = URL(
                    "https://www.lightpollutionmap.info/QueryRaster/" +
                        "?ql=wa_2015&qt=point&qd=$lon,$lat"
                )
                val conn = url.openConnection() as HttpURLConnection
                conn.connectTimeout = 10_000
                conn.readTimeout = 10_000
                conn.setRequestProperty("User-Agent", "PulsarTrigger/1.0")
                try {
                    val response = conn.inputStream.bufferedReader().readText().trim()
                    val radiance = response.toDoubleOrNull()
                    if (radiance != null) {
                        val bortle = BortleScale.fromRadiance(radiance)
                        BortleInfo(
                            classNumber = bortle.number,
                            className = bortle.name,
                            description = bortle.description,
                            color = bortle.color,
                        )
                    } else {
                        null
                    }
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
