/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Copyright (C) 2026 Pulsar Trigger contributors
 */

package com.ehrocha.pulsar.ble

import java.util.UUID

/** BLE UUIDs — must match firmware config.h */
object PulsarUuids {
    val SERVICE: UUID       = UUID.fromString("0000ff00-0000-1000-8000-00805f9b34fb")
    val CHAR_COMMAND: UUID  = UUID.fromString("0000ff01-0000-1000-8000-00805f9b34fb")
    val CHAR_STATUS: UUID   = UUID.fromString("0000ff02-0000-1000-8000-00805f9b34fb")

    // OTA service
    val OTA_SERVICE: UUID   = UUID.fromString("0000ff10-0000-1000-8000-00805f9b34fb")
    val OTA_CONTROL: UUID   = UUID.fromString("0000ff11-0000-1000-8000-00805f9b34fb")
    val OTA_DATA: UUID      = UUID.fromString("0000ff12-0000-1000-8000-00805f9b34fb")
}

/** Command IDs */
object Cmd {
    const val SET_MODE: Byte   = 0x01
    const val START: Byte      = 0x02
    const val STOP: Byte       = 0x03
    const val SHUTTER: Byte    = 0x04
    const val STATUS_REQ: Byte = 0x05
    const val SET_FOCUS: Byte  = 0x06
    const val SET_NAME: Byte   = 0x08
    const val SET_PINS: Byte   = 0x09
}

/** Trigger modes */
enum class TriggerMode(val id: Byte) {
    INTERVALOMETER(0x01),
    ASTRO(0x01),          // uses intervalometer on firmware
    SOUND(0x02),
    LIGHTNING(0x03),
    LASER(0x04),
    HDR(0x05),
    PRESS_HOLD(0x06),
    PRESS_LOCK(0x07),
}

/** Device state reported in the status frame */
enum class DeviceState(val id: Byte) {
    IDLE(0x00),
    RUNNING(0x01),
    WAITING(0x02),
    ERROR(0x03);

    companion object {
        fun fromByte(b: Byte) = entries.firstOrNull { it.id == b } ?: IDLE
    }
}

/** OTA control commands sent to firmware */
object OtaCmd {
    const val BEGIN: Byte = 0x01
    const val END: Byte   = 0x02
    const val ABORT: Byte = 0x03
}

/** OTA status codes received from firmware */
enum class OtaStatus(val id: Byte) {
    OK(0x00),
    ERR_BEGIN(0x01),
    ERR_WRITE(0x02),
    ERR_VALIDATE(0x03),
    ERR_SIZE(0x04),
    READY(0x10),
    COMPLETE(0x11);

    companion object {
        fun fromByte(b: Byte) = entries.firstOrNull { it.id == b } ?: OK
    }
}

/** Parsed status frame from the firmware */
data class StatusFrame(
    val state: DeviceState,
    val mode: Byte,
    val shotsTaken: Int,
    val timeRemainingMs: Long,
    val batteryPct: Int,
    val errorCode: Int,
    val fwVersion: String = "",
) {
    companion object {
        fun parse(data: ByteArray): StatusFrame? {
            if (data.size < 10) return null
            val fwVer = if (data.size >= 13) {
                "${data[10].toInt() and 0xFF}.${data[11].toInt() and 0xFF}.${data[12].toInt() and 0xFF}"
            } else ""
            return StatusFrame(
                state = DeviceState.fromByte(data[0]),
                mode = data[1],
                shotsTaken = (data[2].toInt() and 0xFF) or ((data[3].toInt() and 0xFF) shl 8),
                timeRemainingMs = data.readU32LE(4),
                batteryPct = data[8].toInt() and 0xFF,
                errorCode = data[9].toInt() and 0xFF,
                fwVersion = fwVer,
            )
        }
    }
}

/** Helper to read a little-endian u32 from a byte array */
private fun ByteArray.readU32LE(offset: Int): Long {
    return (this[offset].toLong() and 0xFF) or
            ((this[offset + 1].toLong() and 0xFF) shl 8) or
            ((this[offset + 2].toLong() and 0xFF) shl 16) or
            ((this[offset + 3].toLong() and 0xFF) shl 24)
}
