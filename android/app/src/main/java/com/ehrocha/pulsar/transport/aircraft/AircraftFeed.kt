/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Copyright (C) 2026 Pulsar Trigger contributors
 */

package com.ehrocha.pulsar.transport.aircraft

/**
 * Provider-agnostic interface for "what aircraft are near this point right
 * now". Lets us swap the data source (OpenSky, ADSBexchange, adsb.fi, …)
 * without touching the viewmodel or UI.
 *
 * Mapping from each provider's wire format → [AircraftSighting] happens
 * inside the impl — unit conversions, missing-field handling, distance +
 * bearing math, the whole lot. Domain fields are nullable where reality is
 * variable: OpenSky often omits callsign, ADSBx adds registration, none of
 * them give type for every hex. The UI must cope.
 */
interface AircraftFeed {
    /** Implementation-recommended minimum polling interval. OpenSky's
     *  anonymous endpoint is rate-limited to one request per ~10 s; paid
     *  feeds and ADSBx allow faster. The UI uses this to pace its loop. */
    val minPollIntervalMs: Long

    /** Human-readable name shown in the diagnostics / about screens — e.g.
     *  "OpenSky Network", "ADS-B Exchange". */
    val providerName: String

    /** Fetch every aircraft currently within [radiusKm] of the centre point.
     *  Sorted by distance ascending. [Result.failure] for network / parse /
     *  rate-limit errors — the caller decides whether to surface or retry. */
    suspend fun nearby(
        centreLat: Double,
        centreLon: Double,
        radiusKm: Double,
    ): Result<List<AircraftSighting>>
}

/**
 * One aircraft, as seen by a feed, normalised to a single set of units +
 * field semantics so the UI never has to know which feed it came from.
 *
 * Field nullability tracks what's actually observable: OpenSky's "callsign"
 * column is often blank for transponders that don't broadcast one, altitude
 * is null for ground traffic without a barometric reading, etc.
 *
 * Units (when not null):
 *   - lat/lon: WGS-84 decimal degrees
 *   - altitudeFt: feet above MSL (barometric if available, else geometric)
 *   - groundSpeedKt: knots
 *   - headingDeg: true track, degrees, [0, 360)
 *   - verticalRateFpm: feet per minute, positive = climbing
 *   - distanceKm / bearingDeg: from the centre point of the query
 */
data class AircraftSighting(
    /** Unique 24-bit ICAO transponder address as 6 hex chars (lowercase). */
    val icaoHex: String,
    val callsign: String?,
    val originCountry: String?,
    val lat: Double,
    val lon: Double,
    val altitudeFt: Double?,
    val groundSpeedKt: Double?,
    val headingDeg: Double?,
    val verticalRateFpm: Double?,
    val onGround: Boolean,
    /** Distance from the query centre, kilometres. */
    val distanceKm: Double,
    /** Bearing from the query centre to the aircraft, degrees true. */
    val bearingDeg: Double,
    /** When this state vector was last refreshed by the feed
     *  (Unix epoch seconds), or null if unknown. Lets the UI grey-out
     *  stale traces. */
    val lastContactUnixSec: Long?,
    /** Transponder squawk code (4-digit octal as string), or null when the
     *  body of the transponder didn't send one this poll. Emergency codes:
     *  7500 hijack, 7600 radio failure, 7700 general distress. */
    val squawk: String? = null,
    // ── Per-aircraft metadata (icao24 is fixed for the lifetime of the
    // airframe; these only need fetching once and cache forever). Populated
    // when the feed has had time to enrich; null on the first sighting of
    // a new tail.
    /** Tail registration, e.g. "D-AIXM", "N12345". */
    val registration: String? = null,
    /** Human-readable aircraft model, e.g. "Boeing 737-800". */
    val model: String? = null,
    /** Manufacturer, e.g. "BOEING", "AIRBUS". */
    val manufacturer: String? = null,
    /** Operator / airline, e.g. "Lufthansa", "Delta Air Lines". */
    val operator: String? = null,
    /** ICAO type code, e.g. "B738", "A359". */
    val typeCode: String? = null,
    /** Year the airframe was built, when known. */
    val builtYear: Int? = null,
    /** Thumbnail-sized image URL of this airframe, from planespotters.net
     *  (~280px wide). Cache lives forever per icao24. */
    val photoUrl: String? = null,
    /** Photographer credit string, e.g. "John Doe". Always shown next to
     *  the photo per planespotters.net AUP. */
    val photoCredit: String? = null,
    /** Source page URL on planespotters.net — tap-through from the photo
     *  takes the user to the photographer's full-size image. */
    val photoSourceUrl: String? = null,
)
