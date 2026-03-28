package com.ehrocha.pulsar.ble

import java.util.UUID

/** BLE UUIDs — must match firmware config.h */
object PulsarUuids {
    val SERVICE: UUID       = UUID.fromString("0000ff00-0000-1000-8000-00805f9b34fb")
    val CHAR_COMMAND: UUID  = UUID.fromString("0000ff01-0000-1000-8000-00805f9b34fb")
    val CHAR_STATUS: UUID   = UUID.fromString("0000ff02-0000-1000-8000-00805f9b34fb")
}

/** Command IDs */
object Cmd {
    const val SET_MODE: Byte   = 0x01
    const val START: Byte      = 0x02
    const val STOP: Byte       = 0x03
    const val SHUTTER: Byte    = 0x04
    const val STATUS_REQ: Byte = 0x05
    const val SET_FOCUS: Byte  = 0x06
}

/** Trigger modes */
enum class TriggerMode(val id: Byte) {
    INTERVALOMETER(0x01),
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

/** Parsed status frame from the firmware */
data class StatusFrame(
    val state: DeviceState,
    val mode: Byte,
    val shotsTaken: Int,
    val timeRemainingMs: Long,
    val batteryPct: Int,
    val errorCode: Int,
) {
    companion object {
        fun parse(data: ByteArray): StatusFrame? {
            if (data.size < 10) return null
            return StatusFrame(
                state = DeviceState.fromByte(data[0]),
                mode = data[1],
                shotsTaken = (data[2].toInt() and 0xFF) or ((data[3].toInt() and 0xFF) shl 8),
                timeRemainingMs = data.readU32LE(4),
                batteryPct = data[8].toInt() and 0xFF,
                errorCode = data[9].toInt() and 0xFF,
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
