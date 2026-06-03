/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Copyright (C) 2026 Pulsar Trigger contributors
 */

package com.ehrocha.pulsar.model

/**
 * A device Pulsar has persistent state for — surfaced by the Manage
 * Devices screen so a user can forget the device's stored state in
 * one tap (OS bond + Canon BLE MAC hint + CCAPI credentials, as
 * applicable).
 *
 * [id] is the canonical key for the device's transport:
 *  - BLE kinds → MAC address.
 *  - CCAPI kind → UDN.
 *
 * USB PTP isn't represented here — its state is in-memory only and
 * dies with the process.
 */
data class ManagedDevice(
    val kind: DeviceKind,
    val id: String,
    val displayName: String,
)

enum class DeviceKind {
    /** Pulsar ESP32 module paired via standard BLE. */
    PULSAR_BLE,
    /** Canon camera paired via BLE (BR-E1 or smartphone-mode). */
    CANON_BLE,
    /** Canon camera with stored CCAPI credentials. */
    CANON_CCAPI,
}
