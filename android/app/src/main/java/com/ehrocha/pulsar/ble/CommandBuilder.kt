/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Copyright (C) 2026 Pulsar Trigger contributors
 */

package com.ehrocha.pulsar.ble

import java.nio.ByteBuffer
import java.nio.ByteOrder
import com.ehrocha.pulsar.AppConfig

/** Builds BLE command packets ([AppConfig.BLE_FRAME_SIZE]-byte frames). */
object CommandBuilder {

    private fun frame(cmd: Byte, payload: ByteArray = ByteArray(0)): ByteArray {
        val buf = ByteArray(AppConfig.BLE_FRAME_SIZE)
        buf[0] = cmd
        payload.copyInto(buf, destinationOffset = 1, endIndex = minOf(payload.size, AppConfig.BLE_PAYLOAD_MAX))
        return buf
    }

    fun start(): ByteArray = frame(Cmd.START)
    fun stop(): ByteArray = frame(Cmd.STOP)
    fun shutter(): ByteArray = frame(Cmd.SHUTTER)
    fun statusRequest(): ByteArray = frame(Cmd.STATUS_REQ)
    fun deviceInfoRequest(): ByteArray = frame(Cmd.DEVICE_INFO)

    fun setName(suffix: String): ByteArray {
        val bytes = suffix.toByteArray(Charsets.UTF_8)
        val trimmed = bytes.copyOf(minOf(bytes.size, AppConfig.BLE_DEVICE_NAME_MAX))
        return frame(Cmd.SET_NAME, trimmed)
    }

    fun setFocus(ms: Int): ByteArray {
        val payload = ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(ms.toShort()).array()
        return frame(Cmd.SET_FOCUS, payload)
    }

    fun setIntervalometer(
        intervalMs: Long,
        exposureMs: Long,
        count: Int = 0,
        delayMs: Long = 0,
    ): ByteArray {
        val payload = ByteBuffer.allocate(15).order(ByteOrder.LITTLE_ENDIAN)
            .put(TriggerMode.INTERVALOMETER.id)
            .putInt(intervalMs.toInt())
            .putInt(exposureMs.toInt())
            .putShort(count.toShort())
            .putInt(delayMs.toInt())
            .array()
        return frame(Cmd.SET_MODE, payload)
    }

    fun setPressHold(): ByteArray {
        return frame(Cmd.SET_MODE, byteArrayOf(TriggerMode.PRESS_HOLD.id))
    }

    fun setPressLock(): ByteArray {
        return frame(Cmd.SET_MODE, byteArrayOf(TriggerMode.PRESS_LOCK.id))
    }

    fun setTracker(): ByteArray {
        return frame(Cmd.SET_MODE, byteArrayOf(TriggerMode.TRACKER.id))
    }

    /** Astro mode reuses intervalometer on firmware side. */
    fun setAstro(
        intervalMs: Long,
        exposureMs: Long,
        count: Int = 0,
        delayMs: Long = 0,
    ): ByteArray = setIntervalometer(intervalMs, exposureMs, count, delayMs)

    fun setAutoOff(minutes: Int): ByteArray {
        val payload = ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN)
            .putShort(minutes.toShort()).array()
        return frame(Cmd.SET_AUTO_OFF, payload)
    }

    fun setPins(shutterPin: Int, focusPin: Int): ByteArray {
        val payload = byteArrayOf(shutterPin.toByte(), focusPin.toByte())
        return frame(Cmd.SET_PINS, payload)
    }

    // ── OTA control commands ─────────────────────────────────────────────

    fun otaBegin(totalSize: Int): ByteArray {
        val payload = ByteBuffer.allocate(5).order(ByteOrder.LITTLE_ENDIAN)
            .put(OtaCmd.BEGIN)
            .putInt(totalSize)
            .array()
        return payload  // sent to OTA_CONTROL characteristic, not normal CMD
    }

    fun otaEnd(): ByteArray = byteArrayOf(OtaCmd.END)

    fun otaAbort(): ByteArray = byteArrayOf(OtaCmd.ABORT)
}
