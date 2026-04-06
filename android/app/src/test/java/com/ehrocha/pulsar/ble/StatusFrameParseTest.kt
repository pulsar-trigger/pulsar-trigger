package com.ehrocha.pulsar.ble

import org.junit.Assert.*
import org.junit.Test

class StatusFrameParseTest {

    private fun frame(vararg bytes: Int): ByteArray =
        ByteArray(bytes.size) { bytes[it].toByte() }

    @Test
    fun `parse returns null for short array`() {
        assertNull(StatusFrame.parse(ByteArray(9)))
        assertNull(StatusFrame.parse(ByteArray(0)))
    }

    @Test
    fun `parse IDLE frame with zero fields`() {
        // state=IDLE(0), mode=0x01, shotsTaken=0, timeRemaining=0, battery=100, error=0
        val data = frame(0x00, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x64, 0x00)
        val sf = StatusFrame.parse(data)!!
        assertEquals(DeviceState.IDLE, sf.state)
        assertEquals(0x01.toByte(), sf.mode)
        assertEquals(0, sf.shotsTaken)
        assertEquals(0L, sf.timeRemainingMs)
        assertEquals(100, sf.batteryPct)
        assertEquals(0, sf.errorCode)
        assertEquals("", sf.fwVersion) // only 10 bytes, no version
    }

    @Test
    fun `parse RUNNING frame with shots and time remaining`() {
        // state=RUNNING(1), mode=0x01,
        // shotsTaken = 0x0005 (LE: 05,00)
        // timeRemaining = 0x00001388 = 5000ms (LE: 88,13,00,00)
        // battery = 75, error = 0
        val data = frame(0x01, 0x01, 0x05, 0x00, 0x88, 0x13, 0x00, 0x00, 0x4B, 0x00)
        val sf = StatusFrame.parse(data)!!
        assertEquals(DeviceState.RUNNING, sf.state)
        assertEquals(5, sf.shotsTaken)
        assertEquals(5000L, sf.timeRemainingMs)
        assertEquals(75, sf.batteryPct)
    }

    @Test
    fun `parse frame with firmware version bytes`() {
        // 13 bytes: 10 base + 3 version (0.7.1)
        val data = frame(0x00, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x64, 0x00, 0x00, 0x07, 0x01)
        val sf = StatusFrame.parse(data)!!
        assertEquals("0.7.1", sf.fwVersion)
    }

    @Test
    fun `parse large shotsTaken u16 LE`() {
        // shotsTaken = 0x03E8 = 1000 (LE: E8, 03)
        val data = frame(0x01, 0x01, 0xE8, 0x03, 0x00, 0x00, 0x00, 0x00, 0x50, 0x00)
        val sf = StatusFrame.parse(data)!!
        assertEquals(1000, sf.shotsTaken)
    }

    @Test
    fun `parse large timeRemainingMs u32 LE`() {
        // timeRemaining = 0x000F4240 = 1,000,000ms (LE: 40,42,0F,00)
        val data = frame(0x00, 0x01, 0x00, 0x00, 0x40, 0x42, 0x0F, 0x00, 0x64, 0x00)
        val sf = StatusFrame.parse(data)!!
        assertEquals(1_000_000L, sf.timeRemainingMs)
    }

    @Test
    fun `parse all DeviceState values`() {
        for (state in DeviceState.entries) {
            val data = frame(state.id.toInt() and 0xFF, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00)
            assertEquals(state, StatusFrame.parse(data)!!.state)
        }
    }

    @Test
    fun `DeviceState fromByte unknown defaults to IDLE`() {
        assertEquals(DeviceState.IDLE, DeviceState.fromByte(0xFF.toByte()))
    }

    @Test
    fun `parse WAITING and ERROR states`() {
        val waiting = frame(0x02, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x64, 0x00)
        assertEquals(DeviceState.WAITING, StatusFrame.parse(waiting)!!.state)

        val error = frame(0x03, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x64, 0x05)
        val sf = StatusFrame.parse(error)!!
        assertEquals(DeviceState.ERROR, sf.state)
        assertEquals(5, sf.errorCode)
    }
}
