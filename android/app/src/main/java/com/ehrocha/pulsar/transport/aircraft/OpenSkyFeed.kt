/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Copyright (C) 2026 Pulsar Trigger contributors
 */

package com.ehrocha.pulsar.transport.aircraft

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * OpenSky Network — free anonymous tier. No API key required. Rate limit is
 * one request per ~10 s per IP; the caller paces via [minPollIntervalMs].
 *
 * OpenSky exposes a bounding-box endpoint, not radius — we compute the
 * tightest bounding box that covers the requested circle, fetch, then
 * filter + sort by haversine distance client-side. The bbox over-fetches
 * at high latitudes but that's free traffic the user already paid for in
 * a single request.
 *
 * Wire format: `/states/all` returns a list of fixed-position arrays;
 * column meanings are documented at
 * https://openskynetwork.github.io/opensky-api/rest.html#response.
 * Indices below match that table.
 */
class OpenSkyFeed : AircraftFeed {
    override val minPollIntervalMs: Long = 15_000L
    override val providerName: String = "OpenSky Network"

    override suspend fun nearby(
        centreLat: Double,
        centreLon: Double,
        radiusKm: Double,
    ): Result<List<AircraftSighting>> = withContext(Dispatchers.IO) {
        runCatching {
            val (latMin, latMax, lonMin, lonMax) =
                boundingBox(centreLat, centreLon, radiusKm)
            val url = URL(
                "https://opensky-network.org/api/states/all" +
                    "?lamin=$latMin&lamax=$latMax&lomin=$lonMin&lomax=$lonMax"
            )
            val conn = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 8_000
                readTimeout = 12_000
                requestMethod = "GET"
                setRequestProperty("Accept", "application/json")
                // OpenSky's anonymous tier rejects User-Agents that look
                // like default Java; identify ourselves politely.
                setRequestProperty("User-Agent", "Pulsar-Trigger/1 (open source)")
            }
            val code = conn.responseCode
            if (code !in 200..299) {
                val msg = "OpenSky HTTP $code"
                Log.w(TAG, msg)
                error(msg)
            }
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            val parsed = parseStates(body, centreLat, centreLon)
            parsed
                .filter { it.distanceKm <= radiusKm }
                .sortedBy { it.distanceKm }
        }
    }

    private fun parseStates(
        body: String,
        centreLat: Double,
        centreLon: Double,
    ): List<AircraftSighting> {
        val json = JSONObject(body)
        val states = json.optJSONArray("states") ?: return emptyList()
        val out = ArrayList<AircraftSighting>(states.length())
        for (i in 0 until states.length()) {
            val row = states.optJSONArray(i) ?: continue
            // Indices per OpenSky's response schema:
            //   0  icao24             6  longitude     12  sensors
            //   1  callsign           7  baro_altitude 13  geo_altitude
            //   2  origin_country     8  on_ground     14  squawk
            //   3  time_position      9  velocity      15  spi
            //   4  last_contact      10  true_track    16  position_source
            //   5  ...               11  vertical_rate
            val icao = row.optString(0).takeIf { it.isNotBlank() } ?: continue
            val lon = row.optDoubleOrNull(5) ?: continue
            val lat = row.optDoubleOrNull(6) ?: continue
            val baroAltM = row.optDoubleOrNull(7)
            val geoAltM = row.optDoubleOrNull(13)
            val altM = baroAltM ?: geoAltM  // prefer barometric per ICAO convention
            val velMs = row.optDoubleOrNull(9)
            val trueTrack = row.optDoubleOrNull(10)
            val vertRateMs = row.optDoubleOrNull(11)
            val onGround = row.optBoolean(8, false)
            val callsignRaw = row.optString(1)
            val callsign = callsignRaw.trim().takeIf { it.isNotEmpty() }
            val originCountry = row.optString(2).takeIf { it.isNotBlank() }
            val lastContact = row.optLongOrNull(4)
            out += AircraftSighting(
                icaoHex = icao.lowercase(),
                callsign = callsign,
                originCountry = originCountry,
                lat = lat,
                lon = lon,
                altitudeFt = altM?.let { it * 3.28084 },
                groundSpeedKt = velMs?.let { it * 1.94384 },
                headingDeg = trueTrack,
                verticalRateFpm = vertRateMs?.let { it * 196.8504 },
                onGround = onGround,
                distanceKm = haversineKm(centreLat, centreLon, lat, lon),
                bearingDeg = bearingDeg(centreLat, centreLon, lat, lon),
                lastContactUnixSec = lastContact,
            )
        }
        return out
    }

    /** OpenSky returns `null` as JSON null; [JSONArray.optDouble] turns that
     *  into NaN. Round-trip through a null check so callers see Kotlin null. */
    private fun org.json.JSONArray.optDoubleOrNull(i: Int): Double? {
        if (isNull(i)) return null
        val v = optDouble(i, Double.NaN)
        return if (v.isNaN()) null else v
    }

    private fun org.json.JSONArray.optLongOrNull(i: Int): Long? {
        if (isNull(i)) return null
        return optLong(i, Long.MIN_VALUE).takeIf { it != Long.MIN_VALUE }
    }

    companion object {
        private const val TAG = "OpenSkyFeed"

        /** Bounding box covering a circle of [radiusKm] around (lat, lon).
         *  Approximate — uses a flat-earth padding which is fine for the
         *  ~30 km ranges we care about; would skew at polar latitudes
         *  but no one's plane-spotting from Svalbard. */
        internal fun boundingBox(
            lat: Double, lon: Double, radiusKm: Double,
        ): BBox {
            val latDelta = radiusKm / 111.0  // ~111 km per degree of latitude
            val lonDelta = radiusKm / (111.0 * cos(lat * PI / 180.0).coerceAtLeast(0.01))
            return BBox(
                latMin = (lat - latDelta).coerceIn(-90.0, 90.0),
                latMax = (lat + latDelta).coerceIn(-90.0, 90.0),
                lonMin = ((lon - lonDelta + 540.0) % 360.0) - 180.0,
                lonMax = ((lon + lonDelta + 540.0) % 360.0) - 180.0,
            )
        }

        internal fun haversineKm(
            lat1: Double, lon1: Double, lat2: Double, lon2: Double,
        ): Double {
            val r = 6371.0
            val dLat = (lat2 - lat1) * PI / 180.0
            val dLon = (lon2 - lon1) * PI / 180.0
            val a = sin(dLat / 2).let { it * it } +
                cos(lat1 * PI / 180.0) * cos(lat2 * PI / 180.0) *
                sin(dLon / 2).let { it * it }
            return 2 * r * atan2(sqrt(a), sqrt(1 - a))
        }

        /** Initial bearing from point 1 to point 2 (great-circle), degrees,
         *  normalised to [0, 360). */
        internal fun bearingDeg(
            lat1: Double, lon1: Double, lat2: Double, lon2: Double,
        ): Double {
            val phi1 = lat1 * PI / 180.0
            val phi2 = lat2 * PI / 180.0
            val dLon = (lon2 - lon1) * PI / 180.0
            val y = sin(dLon) * cos(phi2)
            val x = cos(phi1) * sin(phi2) - sin(phi1) * cos(phi2) * cos(dLon)
            val deg = atan2(y, x) * 180.0 / PI
            return (deg + 360.0) % 360.0
        }
    }

    internal data class BBox(
        val latMin: Double,
        val latMax: Double,
        val lonMin: Double,
        val lonMax: Double,
    )
}
