package com.ehrocha.pulsar.ble

import com.ehrocha.pulsar.AppConfig
import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class CommandBuilderTest {

    @Test
    fun `all frames are BLE_FRAME_SIZE bytes`() {
        assertEquals(AppConfig.BLE_FRAME_SIZE, CommandBuilder.start().size)
        assertEquals(AppConfig.BLE_FRAME_SIZE, CommandBuilder.stop().size)
        assertEquals(AppConfig.BLE_FRAME_SIZE, CommandBuilder.shutter().size)
        assertEquals(AppConfig.BLE_FRAME_SIZE, CommandBuilder.statusRequest().size)
        assertEquals(AppConfig.BLE_FRAME_SIZE, CommandBuilder.deviceInfoRequest().size)
        assertEquals(AppConfig.BLE_FRAME_SIZE, CommandBuilder.setPressHold().size)
        assertEquals(AppConfig.BLE_FRAME_SIZE, CommandBuilder.setPressLock().size)
    }

    @Test
    fun `start frame has correct command byte`() {
        assertEquals(Cmd.START, CommandBuilder.start()[0])
    }

    @Test
    fun `stop frame has correct command byte`() {
        assertEquals(Cmd.STOP, CommandBuilder.stop()[0])
    }

    @Test
    fun `shutter frame has correct command byte`() {
        assertEquals(Cmd.SHUTTER, CommandBuilder.shutter()[0])
    }

    @Test
    fun `statusRequest has correct command byte`() {
        assertEquals(Cmd.STATUS_REQ, CommandBuilder.statusRequest()[0])
    }

    @Test
    fun `deviceInfoRequest has correct command byte`() {
        assertEquals(Cmd.DEVICE_INFO, CommandBuilder.deviceInfoRequest()[0])
    }

    @Test
    fun `setFocus encodes u16 LE`() {
        val frame = CommandBuilder.setFocus(500)
        assertEquals(Cmd.SET_FOCUS, frame[0])
        // 500 = 0x01F4, LE: F4, 01
        assertEquals(0xF4.toByte(), frame[1])
        assertEquals(0x01.toByte(), frame[2])
    }

    @Test
    fun `setIntervalometer encodes payload correctly`() {
        val frame = CommandBuilder.setIntervalometer(
            intervalMs = 5000L,
            exposureMs = 2000L,
            count = 100,
            delayMs = 1000L,
        )
        assertEquals(Cmd.SET_MODE, frame[0])
        assertEquals(TriggerMode.INTERVALOMETER.id, frame[1])

        val buf = ByteBuffer.wrap(frame, 2, 14).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(5000, buf.int)     // intervalMs
        assertEquals(2000, buf.int)     // exposureMs
        assertEquals(100.toShort(), buf.short) // count
        assertEquals(1000, buf.int)     // delayMs
    }

    @Test
    fun `setPressHold has correct mode byte`() {
        val frame = CommandBuilder.setPressHold()
        assertEquals(Cmd.SET_MODE, frame[0])
        assertEquals(TriggerMode.PRESS_HOLD.id, frame[1])
    }

    @Test
    fun `setPressLock has correct mode byte`() {
        val frame = CommandBuilder.setPressLock()
        assertEquals(Cmd.SET_MODE, frame[0])
        assertEquals(TriggerMode.PRESS_LOCK.id, frame[1])
    }

    @Test
    fun `setAstro sends distinct mode byte with same payload layout`() {
        val astro = CommandBuilder.setAstro(3000L, 1000L, 50, 500L)
        assertEquals(Cmd.SET_MODE, astro[0])
        assertEquals(TriggerMode.ASTRO.id, astro[1])
        val intv = CommandBuilder.setIntervalometer(3000L, 1000L, 50, 500L)
        assertEquals(intv.size, astro.size)
        assertArrayEquals(intv.copyOfRange(2, intv.size), astro.copyOfRange(2, astro.size))
    }

    @Test
    fun `setName within limit`() {
        val frame = CommandBuilder.setName("Test")
        assertEquals(Cmd.SET_NAME, frame[0])
        assertEquals('T'.code.toByte(), frame[1])
        assertEquals('e'.code.toByte(), frame[2])
        assertEquals('s'.code.toByte(), frame[3])
        assertEquals('t'.code.toByte(), frame[4])
    }

    @Test
    fun `setName truncates at BLE_DEVICE_NAME_MAX`() {
        val longName = "A".repeat(20)
        val frame = CommandBuilder.setName(longName)
        assertEquals(Cmd.SET_NAME, frame[0])
        // Should only have 12 'A' bytes in payload
        for (i in 1..AppConfig.BLE_DEVICE_NAME_MAX) {
            assertEquals('A'.code.toByte(), frame[i])
        }
        // Byte after the name should be 0 (padding)
        assertEquals(0.toByte(), frame[AppConfig.BLE_DEVICE_NAME_MAX + 1])
    }

    @Test
    fun `setPins encodes two pin bytes`() {
        val frame = CommandBuilder.setPins(25, 26)
        assertEquals(Cmd.SET_PINS, frame[0])
        assertEquals(25.toByte(), frame[1])
        assertEquals(26.toByte(), frame[2])
    }

    @Test
    fun `otaBegin encodes BEGIN + u32 LE size`() {
        val payload = CommandBuilder.otaBegin(0x00040000) // 256KB
        assertEquals(5, payload.size)
        assertEquals(OtaCmd.BEGIN, payload[0])
        val buf = ByteBuffer.wrap(payload, 1, 4).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(0x00040000, buf.int)
    }

    @Test
    fun `otaEnd is single byte`() {
        val payload = CommandBuilder.otaEnd()
        assertEquals(1, payload.size)
        assertEquals(OtaCmd.END, payload[0])
    }

    @Test
    fun `otaAbort is single byte`() {
        val payload = CommandBuilder.otaAbort()
        assertEquals(1, payload.size)
        assertEquals(OtaCmd.ABORT, payload[0])
    }

    @Test
    fun `frame padding is zero`() {
        val frame = CommandBuilder.start()
        // Bytes 1..19 should all be zero (no payload for START)
        for (i in 1 until AppConfig.BLE_FRAME_SIZE) {
            assertEquals("byte $i should be 0", 0.toByte(), frame[i])
        }
    }
}
