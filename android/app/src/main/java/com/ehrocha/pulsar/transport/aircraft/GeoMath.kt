/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Copyright (C) 2026 Pulsar Trigger contributors
 */

package com.ehrocha.pulsar.transport.aircraft

import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Shared geodesy helpers for the aircraft tooling. One home for the maths
 * that used to be copy-pasted across `OpenSkyFeed` and the Aircraft Watch
 * screen — duplicated trig drifts, shared trig doesn't.
 *
 * All angles in degrees, distances in the unit named by the function.
 * Flat-earth approximations are used where noted; they're accurate to well
 * under 1 % at the ≤200 km ranges this feature works with.
 */
object GeoMath {

    /** Great-circle distance between two WGS-84 points, kilometres. */
    fun haversineKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371.0
        val dLat = (lat2 - lat1) * PI / 180.0
        val dLon = (lon2 - lon1) * PI / 180.0
        val a = sin(dLat / 2).let { it * it } +
            cos(lat1 * PI / 180.0) * cos(lat2 * PI / 180.0) *
            sin(dLon / 2).let { it * it }
        return 2 * r * atan2(sqrt(a), sqrt(1 - a))
    }

    /** Initial great-circle bearing from point 1 to point 2, degrees true,
     *  normalised to [0, 360). */
    fun bearingDeg(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val phi1 = lat1 * PI / 180.0
        val phi2 = lat2 * PI / 180.0
        val dLon = (lon2 - lon1) * PI / 180.0
        val y = sin(dLon) * cos(phi2)
        val x = cos(phi1) * sin(phi2) - sin(phi1) * cos(phi2) * cos(dLon)
        val deg = atan2(y, x) * 180.0 / PI
        return (deg + 360.0) % 360.0
    }

    /** Project a point [distanceM] metres along [bearing] (degrees true)
     *  from (lat, lon). Flat-earth approximation. Returns (lat, lon). */
    fun projectMeters(
        lat: Double, lon: Double, bearing: Double, distanceM: Double,
    ): Pair<Double, Double> {
        val brgRad = bearing * PI / 180.0
        val latRad = lat * PI / 180.0
        val dLat = (distanceM / 111_111.0) * cos(brgRad)
        val dLon = (distanceM / (111_111.0 * cos(latRad))) * sin(brgRad)
        return (lat + dLat) to (lon + dLon)
    }
}
