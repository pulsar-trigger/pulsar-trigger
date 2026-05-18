/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Copyright (C) 2026 Pulsar Trigger contributors
 */

package com.ehrocha.pulsar.ble

import com.ehrocha.pulsar.AppConfig

/**
 * Builds BLE command packets in protocol v2 (TLV).
 * Frame layout: `[opcode][ver][payload_len][TLV bytes]`. See
 * `docs/ble-protocol.md`.
 */
object CommandBuilder {

    // ── Internal frame builder ─────────────────────────────────────────────
    private class Builder(opcode: Byte) {
        private val out = ArrayList<Byte>(32).apply {
            add(opcode)
            add(ProtoV2.VERSION)
            add(0)  // payload length, patched on toByteArray()
        }

        fun u8(tag: Byte, value: Int): Builder = apply {
            out.add(tag); out.add(1); out.add((value and 0xFF).toByte())
        }
        fun u16(tag: Byte, value: Int): Builder = apply {
            out.add(tag); out.add(2)
            out.add((value and 0xFF).toByte())
            out.add(((value shr 8) and 0xFF).toByte())
        }
        fun u32(tag: Byte, value: Long): Builder = apply {
            out.add(tag); out.add(4)
            out.add((value and 0xFF).toByte())
            out.add(((value shr 8) and 0xFF).toByte())
            out.add(((value shr 16) and 0xFF).toByte())
            out.add(((value shr 24) and 0xFF).toByte())
        }
        fun bytes(tag: Byte, value: ByteArray): Builder = apply {
            out.add(tag); out.add(value.size.toByte())
            value.forEach { out.add(it) }
        }
        fun toByteArray(): ByteArray {
            val payloadLen = out.size - 3
            out[2] = payloadLen.toByte()
            return ByteArray(out.size) { out[it] }
        }
    }

    private fun simple(opcode: Byte): ByteArray =
        Builder(opcode).toByteArray()

    private fun setInterval(opcode: Byte, intervalMs: Long, exposureMs: Long,
                            count: Int, delayMs: Long): ByteArray =
        Builder(opcode)
            .u32(Tag.INTERVAL_MS, intervalMs)
            .u32(Tag.EXPOSURE_MS, exposureMs)
            .u16(Tag.SHOT_COUNT, count)
            .u32(Tag.DELAY_MS, delayMs)
            .toByteArray()

    // ── Control commands ───────────────────────────────────────────────────
    fun start(): ByteArray = simple(Op.START)
    fun stop(): ByteArray = simple(Op.STOP)
    fun shutter(): ByteArray = simple(Op.SHUTTER)
    fun statusRequest(): ByteArray = simple(Op.STATUS_REQ)
    fun deviceInfoRequest(): ByteArray = simple(Op.DEVICE_INFO_REQ)

    // ── Mode setters ───────────────────────────────────────────────────────
    fun setIntervalometer(intervalMs: Long, exposureMs: Long, count: Int = 0, delayMs: Long = 0): ByteArray =
        setInterval(Op.SET_INTERVALOMETER, intervalMs, exposureMs, count, delayMs)

    fun setAstro(intervalMs: Long, exposureMs: Long, count: Int = 0, delayMs: Long = 0): ByteArray =
        setInterval(Op.SET_ASTRO, intervalMs, exposureMs, count, delayMs)

    fun setDarkFrame(intervalMs: Long, exposureMs: Long, count: Int = 0, delayMs: Long = 0): ByteArray =
        setInterval(Op.SET_DARK_FRAME, intervalMs, exposureMs, count, delayMs)

    fun setRamp(intervalMs: Long, exposureMs: Long, count: Int = 0, delayMs: Long = 0): ByteArray =
        setInterval(Op.SET_RAMP, intervalMs, exposureMs, count, delayMs)

    fun setPressHold(): ByteArray = simple(Op.SET_PRESS_HOLD)
    fun setPressLock(): ByteArray = simple(Op.SET_PRESS_LOCK)
    fun setTracker(): ByteArray = simple(Op.SET_TRACKER)

    // ── Configuration ──────────────────────────────────────────────────────
    fun setFocus(ms: Int): ByteArray =
        Builder(Op.SET_FOCUS).u16(Tag.FOCUS_MS, ms).toByteArray()

    fun setPins(shutterPin: Int, focusPin: Int): ByteArray =
        Builder(Op.SET_PINS)
            .u8(Tag.SHUTTER_PIN, shutterPin)
            .u8(Tag.FOCUS_PIN, focusPin)
            .toByteArray()

    fun setAutoOff(minutes: Int): ByteArray =
        Builder(Op.SET_AUTO_OFF).u16(Tag.AUTO_OFF_MIN, minutes).toByteArray()

    fun setName(suffix: String): ByteArray {
        val bytes = suffix.toByteArray(Charsets.UTF_8)
        val trimmed = bytes.copyOf(minOf(bytes.size, AppConfig.BLE_DEVICE_NAME_MAX))
        return Builder(Op.SET_NAME).bytes(Tag.NAME_UTF8, trimmed).toByteArray()
    }

    // ── OTA control commands (unchanged from v1 — separate characteristic) ─
    fun otaBegin(totalSize: Int): ByteArray {
        val payload = ByteArray(5)
        payload[0] = OtaCmd.BEGIN
        payload[1] = (totalSize and 0xFF).toByte()
        payload[2] = ((totalSize shr 8) and 0xFF).toByte()
        payload[3] = ((totalSize shr 16) and 0xFF).toByte()
        payload[4] = ((totalSize shr 24) and 0xFF).toByte()
        return payload
    }

    fun otaEnd(): ByteArray = byteArrayOf(OtaCmd.END)
    fun otaAbort(): ByteArray = byteArrayOf(OtaCmd.ABORT)
}
