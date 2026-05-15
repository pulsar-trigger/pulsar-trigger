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
    val CHAR_PITCH: UUID    = UUID.fromString("0000ff03-0000-1000-8000-00805f9b34fb")

    // OTA service
    val OTA_SERVICE: UUID   = UUID.fromString("0000ff10-0000-1000-8000-00805f9b34fb")
    val OTA_CONTROL: UUID   = UUID.fromString("0000ff11-0000-1000-8000-00805f9b34fb")
    val OTA_DATA: UUID      = UUID.fromString("0000ff12-0000-1000-8000-00805f9b34fb")
}

/** Protocol v2: TLV payloads behind a 1-byte opcode + 1-byte version.
 *  See docs/ble-protocol-v2.md for the design rationale. */
object ProtoV2 {
    const val VERSION: Byte = 0x02
}

/** v2 opcodes (app → firmware).
 *  0x01–0x0F reserved for legacy v1 CMD bytes (discriminator). */
object Op {
    const val SET_INTERVALOMETER: Byte = 0x10
    const val SET_ASTRO: Byte          = 0x11
    const val SET_DARK_FRAME: Byte     = 0x12
    const val SET_RAMP: Byte           = 0x13
    const val SET_PRESS_HOLD: Byte     = 0x14
    const val SET_PRESS_LOCK: Byte     = 0x15
    const val SET_TRACKER: Byte        = 0x16

    const val SET_FOCUS: Byte          = 0x20
    const val SET_PINS: Byte           = 0x21
    const val SET_AUTO_OFF: Byte       = 0x22
    const val SET_NAME: Byte           = 0x23

    const val START: Byte              = 0x50
    const val STOP: Byte               = 0x51
    const val SHUTTER: Byte            = 0x52
    const val STATUS_REQ: Byte         = 0x53
    const val DEVICE_INFO_REQ: Byte    = 0x54
}

/** v2 notification opcodes (firmware → app).
 *  0x00–0x03 reserved for v1 STATE bytes, 0xFF for v1 DeviceInfo marker. */
object NotifyOp {
    const val STATUS: Byte      = 0x80.toByte()
    const val DEVICE_INFO: Byte = 0x81.toByte()
    const val ACK: Byte         = 0x82.toByte()
}

/** v2 TLV tag registry. See docs/ble-protocol-v2.md for the partitioning. */
object Tag {
    // Capture parameters (0x01–0x0F)
    const val INTERVAL_MS: Byte    = 0x01
    const val EXPOSURE_MS: Byte    = 0x02
    const val SHOT_COUNT: Byte     = 0x03
    const val DELAY_MS: Byte       = 0x04
    // Optical / Astro (0x10–0x1F) — reserved hooks; not used by app yet.
    const val FOCAL_LENGTH: Byte   = 0x10
    const val CROP_FACTOR: Byte    = 0x11
    const val RULE_DIVISOR: Byte   = 0x12
    // Ramp (0x20–0x2F)
    const val RAMP_START_MS: Byte  = 0x20
    const val RAMP_END_MS: Byte    = 0x21
    const val RAMP_STEPS: Byte     = 0x22
    // Hardware / device (0x30–0x4F)
    const val FOCUS_MS: Byte       = 0x30
    const val SHUTTER_PIN: Byte    = 0x31
    const val FOCUS_PIN: Byte      = 0x32
    const val AUTO_OFF_MIN: Byte   = 0x33
    const val NAME_UTF8: Byte      = 0x34
    // Status (0x50–0x6F)
    const val STATE: Byte          = 0x50
    const val MODE: Byte           = 0x51
    const val SHOTS_TAKEN: Byte    = 0x52
    const val TIME_REMAIN_MS: Byte = 0x53
    const val BATTERY_PCT: Byte    = 0x54
    const val ERROR_CODE: Byte     = 0x55
    const val FW_VERSION: Byte     = 0x56
    const val OPCODE: Byte         = 0x57
    // Device info (0x70–0x8F)
    const val CHIP_MODEL: Byte     = 0x70
    const val CHIP_REVISION: Byte  = 0x71
    const val CPU_FREQ_MHZ: Byte   = 0x72
    const val FLASH_SIZE_KB: Byte  = 0x73
    const val FREE_HEAP_KB: Byte   = 0x74
    const val PSRAM_KB: Byte       = 0x75
    const val GPIO_COUNT: Byte     = 0x76
    const val SAFE_OUT_COUNT: Byte = 0x77
    const val UPTIME_MIN: Byte     = 0x78
}

/** Trigger modes. The wire-side ID is the v2 SET_* opcode the app would emit
 *  to enter that mode; status frames carry this same byte as their MODE tag. */
enum class TriggerMode(val id: Byte) {
    INTERVALOMETER(Op.SET_INTERVALOMETER),
    ASTRO(Op.SET_ASTRO),
    DARK_FRAME(Op.SET_DARK_FRAME),
    RAMP(Op.SET_RAMP),
    PRESS_HOLD(Op.SET_PRESS_HOLD),
    PRESS_LOCK(Op.SET_PRESS_LOCK),
    TRACKER(Op.SET_TRACKER),
    CUSTOM_FLOW(0x7F.toByte()),  // app-orchestrated, never sent to firmware
    ;

    companion object {
        fun fromByte(b: Byte) = entries.firstOrNull { it.id == b }
    }
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

/** Walks a v2 notification frame's TLVs. The [parse] entry point validates
 *  the envelope (opcode, version, length); on success it returns a reader
 *  that yields successive `(tag, length, value)` triples. */
internal class TlvReader private constructor(
    val opcode: Byte,
    private val data: ByteArray,
    private val end: Int,
) {
    private var pos = 3

    /** Reads the next TLV; returns true and populates the receiver. */
    fun next(onTag: (tag: Byte, len: Int, valueStart: Int) -> Unit): Boolean {
        if (pos + 2 > end) return false
        val tag = data[pos]
        val tlen = data[pos + 1].toInt() and 0xFF
        if (pos + 2 + tlen > end) return false
        onTag(tag, tlen, pos + 2)
        pos += 2 + tlen
        return true
    }

    fun u8(offset: Int): Int = data[offset].toInt() and 0xFF
    fun u16(offset: Int): Int =
        (data[offset].toInt() and 0xFF) or
        ((data[offset + 1].toInt() and 0xFF) shl 8)
    fun u32(offset: Int): Long =
        (data[offset].toLong() and 0xFF) or
        ((data[offset + 1].toLong() and 0xFF) shl 8) or
        ((data[offset + 2].toLong() and 0xFF) shl 16) or
        ((data[offset + 3].toLong() and 0xFF) shl 24)

    /** Slice of the value bytes for tag types that carry raw bytes. */
    fun bytes(offset: Int, len: Int): ByteArray = data.copyOfRange(offset, offset + len)

    companion object {
        /** Parses the envelope; returns null on bad version or short frame. */
        fun parse(data: ByteArray): TlvReader? {
            if (data.size < 3) return null
            if (data[1] != ProtoV2.VERSION) return null
            val payloadLen = data[2].toInt() and 0xFF
            if (3 + payloadLen > data.size) return null
            return TlvReader(data[0], data, 3 + payloadLen)
        }
    }
}

/** Parsed status frame from the firmware (v2). */
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
            val r = TlvReader.parse(data) ?: return null
            if (r.opcode != NotifyOp.STATUS) return null

            var state = DeviceState.IDLE
            var mode: Byte = 0
            var shots = 0
            var timeMs = 0L
            var batt = 0
            var err = 0
            var fwVer = ""

            while (r.next { tag, len, off ->
                when (tag) {
                    Tag.STATE          -> if (len == 1) state = DeviceState.fromByte(r.u8(off).toByte())
                    Tag.MODE           -> if (len == 1) mode = r.u8(off).toByte()
                    Tag.SHOTS_TAKEN    -> if (len == 2) shots = r.u16(off)
                    Tag.TIME_REMAIN_MS -> if (len == 4) timeMs = r.u32(off)
                    Tag.BATTERY_PCT    -> if (len == 1) batt = r.u8(off)
                    Tag.ERROR_CODE     -> if (len == 1) err = r.u8(off)
                    Tag.FW_VERSION     -> if (len == 3) {
                        fwVer = "${r.u8(off)}.${r.u8(off + 1)}.${r.u8(off + 2)}"
                    }
                    // Unknown tags ignored per protocol rules.
                }
            }) { /* loop body in onTag */ }

            return StatusFrame(state, mode, shots, timeMs, batt, err, fwVer)
        }
    }
}

/** Device hardware info (v2 notify). */
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
        fun parse(data: ByteArray): DeviceInfo? {
            val r = TlvReader.parse(data) ?: return null
            if (r.opcode != NotifyOp.DEVICE_INFO) return null

            var chipModelByte = 0
            var chipRev = 0
            var cpuFreq = 0
            var flashKb = 0L
            var heapKb = 0L
            var psramKb = 0
            var gpioCount = 0
            var safeOut = 0
            var uptime = 0

            while (r.next { tag, len, off ->
                when (tag) {
                    Tag.CHIP_MODEL     -> if (len == 1) chipModelByte = r.u8(off)
                    Tag.CHIP_REVISION  -> if (len == 1) chipRev = r.u8(off)
                    Tag.CPU_FREQ_MHZ   -> if (len == 1) cpuFreq = r.u8(off)
                    Tag.FLASH_SIZE_KB  -> if (len == 4) flashKb = r.u32(off)
                    Tag.FREE_HEAP_KB   -> if (len == 4) heapKb = r.u32(off)
                    Tag.PSRAM_KB       -> if (len == 2) psramKb = r.u16(off)
                    Tag.GPIO_COUNT     -> if (len == 1) gpioCount = r.u8(off)
                    Tag.SAFE_OUT_COUNT -> if (len == 1) safeOut = r.u8(off)
                    Tag.UPTIME_MIN     -> if (len == 2) uptime = r.u16(off)
                }
            }) { }

            val model = when (chipModelByte) {
                1 -> "ESP32"
                2 -> "ESP32-S2"
                3 -> "ESP32-S3"
                4 -> "ESP32-C3"
                else -> "Unknown"
            }
            return DeviceInfo(
                chipModel = model,
                chipRevision = chipRev,
                cpuFreqMhz = cpuFreq,
                flashSizeKb = flashKb,
                freeHeapKb = heapKb,
                psramKb = psramKb,
                gpioCount = gpioCount,
                safeOutputCount = safeOut,
                uptimeMinutes = uptime,
            )
        }
    }
}

/** Parsed ACK frame (NOTIFY_ACK). Optional; senders may ignore receipt. */
data class AckFrame(val opcode: Byte, val errorCode: Int) {
    companion object {
        fun parse(data: ByteArray): AckFrame? {
            val r = TlvReader.parse(data) ?: return null
            if (r.opcode != NotifyOp.ACK) return null
            var op: Byte = 0
            var err = 0
            while (r.next { tag, len, off ->
                when (tag) {
                    Tag.OPCODE     -> if (len == 1) op = r.u8(off).toByte()
                    Tag.ERROR_CODE -> if (len == 1) err = r.u8(off)
                }
            }) { }
            return AckFrame(op, err)
        }
    }
}
