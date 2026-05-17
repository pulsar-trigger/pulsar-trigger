/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Copyright (C) 2026 Pulsar Trigger contributors
 */

package com.ehrocha.pulsar.ble

import android.bluetooth.BluetoothDevice

/**
 * A device returned by the BLE scanner plus whatever board / chip info the
 * Pulsar firmware embeds in its scan-response manufacturer data. The IDs
 * are decoded ahead of connect time so the scan card can show the right
 * device image without needing to handshake first.
 *
 * Wire format (4–6 bytes after the 0xFFFF company ID prefix):
 *   byte 0 → board id   (1 = generic ESP32, 2 = M5StickC S3, 3 = M5Core2)
 *   byte 1 → chip model (1 = ESP32, 2 = S2, 3 = S3, 4 = C3)
 *   byte 2 → fw major   (optional)
 *   byte 3 → fw minor   (optional)
 *
 * Older firmware that doesn't include manufacturer data parses to
 * [BoardKind.UNKNOWN].
 */
data class ScannedDevice(
    val device: BluetoothDevice,
    val boardKind: BoardKind,
    val chipModel: Int,
    val fwMajor: Int,
    val fwMinor: Int,
)

enum class BoardKind(val id: Int) {
    UNKNOWN(0),
    GENERIC_ESP32(1),
    M5STICK_S3(2),
    M5CORE2(3),
    ;

    companion object {
        fun fromId(id: Int): BoardKind = entries.firstOrNull { it.id == id } ?: UNKNOWN
    }
}

/** 0xFFFF — unregistered / hobbyist Bluetooth SIG company identifier. We use
 *  it as the key in [android.bluetooth.le.ScanRecord.getManufacturerSpecificData]. */
const val PULSAR_MFG_COMPANY_ID: Int = 0xFFFF
