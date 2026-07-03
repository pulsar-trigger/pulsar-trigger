/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Copyright (C) 2026 Pulsar Trigger contributors
 */

package com.ehrocha.pulsar.canonble

/**
 * Pure, dependency-free decision logic for the Canon BLE transport, extracted
 * from `PulsarViewModel` so it can be unit-tested without an Android context.
 *
 * These are the small rules the direct-drive BLE saga converged on (hardware
 * confirmed on the EOS RP + R6, 2026-07-03): the post-frame cool-down clamp,
 * registration-name sanitization + distinctness, the interval floor, the
 * dial-change reminder edge, and the toggle-parity Camera Test shot count.
 * The ViewModel owns the state (prefs / StateFlows / active transport); this
 * object owns the *math*.
 */
object CanonBleRules {
    /** Cool-down bounds. Below the floor the camera eats presses / skips frames
     *  (both BR-E1 and smartphone mode); the ceiling caps the advanced-user knob. */
    const val COOLDOWN_MIN_MS = 1_000L
    const val COOLDOWN_MAX_MS = 10_000L

    /** Hardware-proven default floor: below ~4 s of quiet the EOS RP / R6 / R
     *  eat presses / skip frames on both protocols (swept 2026-07-01/03). */
    const val COOLDOWN_DEFAULT_MS = 4_000L

    /** Registration names go into the BR-E1 pair-write / smart identity write,
     *  which are byte-limited; also keep them printable in the camera's list. */
    const val NAME_MAX_LEN = 20

    /** Clamp a user-entered cool-down to the supported range. */
    fun clampCooldown(ms: Long): Long = ms.coerceIn(COOLDOWN_MIN_MS, COOLDOWN_MAX_MS)

    /** Sanitize a registration name to printable ASCII, trimmed and length-capped;
     *  a blank result falls back to the protocol default. */
    fun sanitizeName(s: String, fallback: String): String =
        s.filter { it.code in 0x20..0x7E }.trim().take(NAME_MAX_LEN).ifBlank { fallback }

    /** The two protocol names must differ (case-insensitively) or they're
     *  indistinguishable in the camera's paired-devices list. */
    fun namesDistinct(a: String, b: String): Boolean = !a.equals(b, ignoreCase = true)

    /** Raise a below-floor interval to the cool-down floor; pass others through.
     *  Caller decides whether the transport is actually Canon BLE. */
    fun safeInterval(intervalMs: Long, floorMs: Long): Long =
        if (intervalMs >= floorMs) intervalMs else floorMs

    /** Whether to fire the "move the mode dial" reminder: only on a real
     *  Bulb↔M change (never on the first observation), and only while the
     *  dial-dependent Canon BLE transport is active. */
    fun shouldRemindDial(prev: Boolean?, next: Boolean, transportActive: Boolean): Boolean =
        prev != null && prev != next && transportActive

    /** Camera Test manual/single-shot count: 2 on Canon BLE so an even `[00,01]`
     *  count returns the toggle shutter to CLOSED on either dial (a dial-mismatched
     *  manual phase can't leave the bulb phase desynced/open); 1 elsewhere. */
    fun manualTestShots(isCanonBle: Boolean): Int = if (isCanonBle) 2 else 1
}
