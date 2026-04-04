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
    const val DEVICE_INFO: Byte = 0x0A
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
    CUSTOM_FLOW(0x7F.toByte()),  // app-orchestrated, never sent to firmware
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

private fun ByteArray.readU16LE(offset: Int): Int {
    return (this[offset].toInt() and 0xFF) or
            ((this[offset + 1].toInt() and 0xFF) shl 8)
}

/** Device hardware info from CMD_DEVICE_INFO response (marker byte 0xFF) */
data class DeviceInfo(
    val chipModel: String,
    val chipRevision: Int,
    val cpuFreqMhz: Int,
    val flashSizeKb: Long,
    val freeHeapKb: Long,
    val psramKb: Int,
    val gpioCount: Int,
    val safeOutputCount: Int,
    val uptimeMinutes: Int,
) {
    companion object {
        /** Try to parse a DeviceInfoFrame (20 bytes, marker 0xFF). Returns null if not a device info frame. */
        fun parse(data: ByteArray): DeviceInfo? {
            if (data.size < 18) return null
            if ((data[0].toInt() and 0xFF) != 0xFF) return null
            val model = when (data[1].toInt() and 0xFF) {
                1 -> "ESP32"
                2 -> "ESP32-S2"
                3 -> "ESP32-S3"
                4 -> "ESP32-C3"
                else -> "Unknown"
            }
            return DeviceInfo(
                chipModel = model,
                chipRevision = data[2].toInt() and 0xFF,
                cpuFreqMhz = data[3].toInt() and 0xFF,
                flashSizeKb = data.readU32LE(4),
                freeHeapKb = data.readU32LE(8),
                psramKb = data.readU16LE(12),
                gpioCount = data[14].toInt() and 0xFF,
                safeOutputCount = data[15].toInt() and 0xFF,
                uptimeMinutes = data.readU16LE(16),
            )
        }
    }
}
