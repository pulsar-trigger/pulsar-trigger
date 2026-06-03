/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Copyright (C) 2026 Pulsar Trigger contributors
 */

package com.ehrocha.pulsar.model

import com.ehrocha.pulsar.transport.TransportKind

/**
 * The transport + device the user was most recently connected to. The Scan
 * landing screen surfaces this as a one-tap "Reconnect to …" CTA so the
 * common case (back from last night's astro session, same gear) skips the
 * transport-picker → setup-screen flow.
 *
 * Persisted as a single JSON-ish string in [SHARED_PREFS_NAME] under
 * [PREF_KEY]; updated on every successful `connectX` in `PulsarViewModel`.
 * Reconstructed on viewmodel init.
 *
 * The [identifier] format is transport-specific and the dispatcher in
 * `reconnectLast()` knows how to interpret each:
 *   - BLE_ESP    : MAC address ("AA:BB:CC:DD:EE:FF")
 *   - CCAPI      : "udn|accessUrl|ipAddress|port|friendlyName" — enough to
 *                  rebuild a [CanonCamera] without re-running SSDP discovery
 *   - PTP_USB    : "vendorId:productId" (decimal, e.g. "1193:13074")
 *   - CANON_BLE  : MAC address
 *
 * Whether the reconnect succeeds depends on whether the device is currently
 * reachable (advertising / on-network / cabled in) — the CTA just tries
 * and surfaces the appropriate error on failure.
 */
data class LastConnection(
    val kind: TransportKind,
    /** Display string for the CTA, e.g. "Pulsar-AB12", "EOS RP", "Canon EOS RP". */
    val label: String,
    /** Transport-specific reconnect key. See class doc for format. */
    val identifier: String,
) {
    /** Serialise to a single string for SharedPrefs. The fields are joined
     *  with a delimiter that can't appear in a MAC, UDN, or URL. */
    fun serialise(): String = "${kind.name}$label$identifier"

    /** Map a [LastConnection] to a [ManagedDevice] so callers can use
     *  [com.ehrocha.pulsar.viewmodel.PulsarViewModel.forgetDevice] for a
     *  full unpair (OS bond + creds + Pulsar hint). Returns null for
     *  transports without persistent device state (PTP USB). */
    fun toManagedDevice(): ManagedDevice? = when (kind) {
        TransportKind.BLE_ESP -> ManagedDevice(DeviceKind.PULSAR_BLE, identifier, label)
        TransportKind.CANON_BLE -> ManagedDevice(DeviceKind.CANON_BLE, identifier, label)
        TransportKind.CCAPI -> {
            // CCAPI identifier is "udn|accessUrl|ip|port|friendly" —
            // forgetDevice keys on UDN.
            val udn = identifier.substringBefore('|', "")
            if (udn.isNotEmpty()) ManagedDevice(DeviceKind.CANON_CCAPI, udn, label) else null
        }
        else -> null  // PTP USB / PTP-IP — no persistent state to forget.
    }

    companion object {
        const val SHARED_PREFS_NAME = "pulsar_last_connection"
        const val PREF_KEY = "last"

        /** Parse a previously-serialised entry, or null if the string is
         *  malformed / an old schema. */
        fun deserialise(raw: String?): LastConnection? {
            if (raw.isNullOrBlank()) return null
            val parts = raw.split('')
            if (parts.size != 3) return null
            val kind = runCatching { TransportKind.valueOf(parts[0]) }.getOrNull() ?: return null
            return LastConnection(kind, parts[1], parts[2])
        }
    }
}
