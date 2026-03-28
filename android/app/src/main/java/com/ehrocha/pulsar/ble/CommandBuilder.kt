package com.ehrocha.pulsar.ble

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Builds BLE command packets (20-byte frames). */
object CommandBuilder {

    private fun frame(cmd: Byte, payload: ByteArray = ByteArray(0)): ByteArray {
        val buf = ByteArray(20)
        buf[0] = cmd
        payload.copyInto(buf, destinationOffset = 1, endIndex = minOf(payload.size, 19))
        return buf
    }

    fun start(): ByteArray = frame(Cmd.START)
    fun stop(): ByteArray = frame(Cmd.STOP)
    fun shutter(): ByteArray = frame(Cmd.SHUTTER)
    fun statusRequest(): ByteArray = frame(Cmd.STATUS_REQ)

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

    fun setSound(threshold: Int, exposureMs: Long): ByteArray {
        val payload = ByteBuffer.allocate(7).order(ByteOrder.LITTLE_ENDIAN)
            .put(TriggerMode.SOUND.id)
            .putShort(threshold.toShort())
            .putInt(exposureMs.toInt())
            .array()
        return frame(Cmd.SET_MODE, payload)
    }

    fun setLightning(sensitivity: Int, exposureMs: Long): ByteArray {
        val payload = ByteBuffer.allocate(6).order(ByteOrder.LITTLE_ENDIAN)
            .put(TriggerMode.LIGHTNING.id)
            .put(sensitivity.toByte())
            .putInt(exposureMs.toInt())
            .array()
        return frame(Cmd.SET_MODE, payload)
    }

    fun setLaser(exposureMs: Long): ByteArray {
        val payload = ByteBuffer.allocate(5).order(ByteOrder.LITTLE_ENDIAN)
            .put(TriggerMode.LASER.id)
            .putInt(exposureMs.toInt())
            .array()
        return frame(Cmd.SET_MODE, payload)
    }

    fun setHdr(exposuresMs: List<Long>): ByteArray {
        val count = minOf(exposuresMs.size, 5)
        val payload = ByteBuffer.allocate(2 + count * 4).order(ByteOrder.LITTLE_ENDIAN)
            .put(TriggerMode.HDR.id)
            .put(count.toByte())
        exposuresMs.take(count).forEach { payload.putInt(it.toInt()) }
        return frame(Cmd.SET_MODE, payload.array())
    }

    fun setPressHold(): ByteArray {
        return frame(Cmd.SET_MODE, byteArrayOf(TriggerMode.PRESS_HOLD.id))
    }

    fun setPressLock(): ByteArray {
        return frame(Cmd.SET_MODE, byteArrayOf(TriggerMode.PRESS_LOCK.id))
    }
}
