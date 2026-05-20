/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Copyright (C) 2026 Pulsar Trigger contributors
 */

package com.ehrocha.pulsar.transport

/**
 * Snapshot of the camera's lens, transport-agnostic. CCAPI fills this from
 * `/devicestatus/lens`; PTP fills it from Canon's `LensName` device property
 * (`0xD157`). Older bodies may not report `focallength` natively — Pulsar
 * parses the focal length(s) out of the model name with
 * [parseFocalFromName] as a fallback.
 *
 * Consumed by the Astro wizard's focal-length auto-fill.
 */
data class LensInfo(
    val mounted: Boolean,
    val name: String,
    /** Single focal length parsed from the name (e.g. "RF16mm F2.8" → 16).
     *  Null for zoom lenses or unrecognised name shapes. */
    val focalMm: Int?,
    /** Zoom range parsed from the name (e.g. "RF24-105mm F4" → 24..105).
     *  Null for primes or unrecognised shapes. Current zoom position isn't
     *  reported by the older CCAPI revisions Pulsar targets, so the user
     *  has to type the actual value. */
    val zoomRangeMm: IntRange?,
) {
    val isPrime: Boolean get() = focalMm != null && zoomRangeMm == null
    val isZoom: Boolean get() = zoomRangeMm != null
}

/** Parse focal length(s) out of a Canon lens model name. Returns
 *  `(focalMm, zoomRangeMm)` — exactly one of the two is non-null on success,
 *  both null if no usable number pattern is found. Handles names like:
 *   - "RF16mm F2.8 STM" → (16, null)
 *   - "EF 50mm f/1.8 STM" → (50, null)
 *   - "RF24-105mm F4 L IS USM" → (null, 24..105)
 *   - "EF 70-200mm f/2.8L II USM" → (null, 70..200)
 */
internal fun parseFocalFromName(name: String): Pair<Int?, IntRange?> {
    // Match an `N` or `N-M` immediately followed by an optional space and
    // `mm`. The number must not be preceded by another digit so we don't
    // trip on aperture digits like "F2.8" or extender markers like "1.4x".
    val regex = Regex("""(?<!\d)(\d+)(?:-(\d+))?\s*mm""", RegexOption.IGNORE_CASE)
    val m = regex.find(name) ?: return null to null
    val low = m.groupValues[1].toIntOrNull() ?: return null to null
    val high = m.groupValues[2].toIntOrNull()
    return if (high != null && high > low) null to (low..high) else low to null
}
