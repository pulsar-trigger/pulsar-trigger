/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Copyright (C) 2026 Pulsar Trigger contributors
 */

package com.ehrocha.pulsar.canonble

import java.util.UUID

/**
 * Lookup table — best-effort nicknames + write hints for the UUIDs the
 * GATT Explorer wizard is most likely to surface on a Canon body.
 *
 * Sourced from `docs/canon-ble-research.md` §1 (BR-E1 remote) and §7
 * (smartphone-mode) plus the standard SIG-assigned UUIDs (Device
 * Information 0x180A and its characteristics 0x2A24/0x2A26/0x2A27/0x2A28).
 *
 * `hint` is shown inline above the write field in the explorer's char-
 * actions panel so a tester without prior knowledge can still issue a
 * meaningful probe. Keep hints short — they're one-line affordances,
 * not documentation.
 */
data class KnownGattUuid(
    val nickname: String,
    val hint: String? = null,
)

object KnownGattUuids {
    /** Lookup by full UUID string (lowercase). */
    private val table: Map<String, KnownGattUuid> = mapOf(
        // ── Canon BR-E1 remote protocol (service 0x00050000) ────────────
        canonUuid("00050000") to KnownGattUuid(
            "Canon BR-E1 remote (service)",
            "Older Canon bodies use this protocol. See canon-ble.md.",
        ),
        canonUuid("00050002") to KnownGattUuid(
            "BR-E1 pair char",
            "Arm-write: [0x03, ASCII name] to register as the remote.",
        ),
        canonUuid("00050003") to KnownGattUuid(
            "BR-E1 control char",
            "Single-byte writes: 0x8C press, 0x0C release, 0x4C AF half-press.",
        ),

        // ── Canon smartphone-mode (service 0x00010000) ──────────────────
        canonUuid("00010000") to KnownGattUuid(
            "Canon smartphone-mode identify (service)",
            "Modern R-series / M-series / mid-range DSLRs.",
        ),
        canonUuid("00010006") to KnownGattUuid(
            "Smart identify char",
            "Identify handshake: write [01, name] then read the camera's 0x02 accept indication.",
        ),
        canonUuid("0001000a") to KnownGattUuid(
            "Smart identify finalize char",
            "Sequence: [03, UUID]→[04, name]→[05, 02]→[01]. See canon-ble-research.md §7.",
        ),

        // ── Smartphone-mode mode/shutter (service 0x00030000) ───────────
        canonUuid("00030000") to KnownGattUuid(
            "Canon smartphone-mode control (service)",
            "Presence of this service = body can fire over BLE. 2018 EOS R lacks it.",
        ),
        canonUuid("00030010") to KnownGattUuid(
            "Mode-shoot char",
            "Write [0x02] to enter shoot mode (required before shutter writes).",
        ),
        canonUuid("00030011") to KnownGattUuid(
            "Mode status char",
            "Read-only state byte. 0x01 = idle, 0x04 = shoot-mode active.",
        ),
        canonUuid("00030030") to KnownGattUuid(
            "Smart shutter char",
            "Press [00,01] / release [00,02] on both M and Bulb (v0.358+).",
        ),

        // ── Smartphone-mode geo (service 0x00040000) ────────────────────
        canonUuid("00040000") to KnownGattUuid(
            "Canon geo/time (service)",
            "Optional location + time-sync. Not required for shutter control.",
        ),
        canonUuid("00040002") to KnownGattUuid(
            "Geo packet char",
            "Packed lat/lon/elev + unix-ts header 0x04. See research §7.",
        ),

        // ── Standard SIG Device Information ─────────────────────────────
        sigUuid("180a") to KnownGattUuid(
            "Device Information (standard SIG)",
            "Best place to read manufacturer / model / firmware version.",
        ),
        sigUuid("2a24") to KnownGattUuid(
            "Model Number String",
            "Read-only. Often the body's model (e.g. \"EOS R6\").",
        ),
        sigUuid("2a25") to KnownGattUuid(
            "Serial Number String",
            "Read-only. The body's serial.",
        ),
        sigUuid("2a26") to KnownGattUuid(
            "Firmware Revision String",
            "Read-only. Useful when filing a report so we know the FW version.",
        ),
        sigUuid("2a27") to KnownGattUuid(
            "Hardware Revision String",
            "Read-only.",
        ),
        sigUuid("2a28") to KnownGattUuid(
            "Software Revision String",
            "Read-only.",
        ),
        sigUuid("2a29") to KnownGattUuid(
            "Manufacturer Name String",
            "Read-only. Should be \"Canon\" on supported bodies.",
        ),
    )

    fun lookup(uuid: UUID): KnownGattUuid? =
        table[uuid.toString().lowercase()]

    /** Canon's 128-bit base — UUIDs have the form
     *  `XXXXXXXX-0000-1000-0000-d8492fffa821`. */
    private fun canonUuid(short: String): String =
        "$short-0000-1000-0000-d8492fffa821"

    /** Standard SIG 16-bit-promoted 128-bit form. */
    private fun sigUuid(short: String): String =
        "0000$short-0000-1000-8000-00805f9b34fb"
}
