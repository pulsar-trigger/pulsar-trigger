package com.ehrocha.pulsar.ble

import org.junit.Assert.*
import org.junit.Test

class DeviceInfoParseTest {

    /** Build a 20-byte DeviceInfoFrame with the 0xFF marker. */
    private fun infoFrame(
        chipModel: Int = 1,
        chipRevision: Int = 1,
        cpuFreqMhz: Int = 240,
        flashSizeKb: Long = 4096,
        freeHeapKb: Long = 200,
        psramKb: Int = 0,
        gpioCount: Int = 34,
        safeOutputCount: Int = 13,
        uptimeMinutes: Int = 120,
    ): ByteArray {
        val buf = ByteArray(20)
        buf[0] = 0xFF.toByte()  // marker
        buf[1] = chipModel.toByte()
        buf[2] = chipRevision.toByte()
        buf[3] = cpuFreqMhz.toByte()
        // flashSizeKb LE at offset 4
        buf[4] = (flashSizeKb and 0xFF).toByte()
        buf[5] = ((flashSizeKb shr 8) and 0xFF).toByte()
        buf[6] = ((flashSizeKb shr 16) and 0xFF).toByte()
        buf[7] = ((flashSizeKb shr 24) and 0xFF).toByte()
        // freeHeapKb LE at offset 8
        buf[8] = (freeHeapKb and 0xFF).toByte()
        buf[9] = ((freeHeapKb shr 8) and 0xFF).toByte()
        buf[10] = ((freeHeapKb shr 16) and 0xFF).toByte()
        buf[11] = ((freeHeapKb shr 24) and 0xFF).toByte()
        // psramKb LE at offset 12
        buf[12] = (psramKb and 0xFF).toByte()
        buf[13] = ((psramKb shr 8) and 0xFF).toByte()
        buf[14] = gpioCount.toByte()
        buf[15] = safeOutputCount.toByte()
        // uptimeMinutes LE at offset 16
        buf[16] = (uptimeMinutes and 0xFF).toByte()
        buf[17] = ((uptimeMinutes shr 8) and 0xFF).toByte()
        return buf
    }

    @Test
    fun `parse returns null for short array`() {
        assertNull(DeviceInfo.parse(ByteArray(17)))
    }

    @Test
    fun `parse returns null without 0xFF marker`() {
        val data = infoFrame()
        data[0] = 0x00  // not the marker
        assertNull(DeviceInfo.parse(data))
    }

    @Test
    fun `parse ESP32 chip model`() {
        val info = DeviceInfo.parse(infoFrame(chipModel = 1))!!
        assertEquals("ESP32", info.chipModel)
    }

    @Test
    fun `parse ESP32-S2 chip model`() {
        val info = DeviceInfo.parse(infoFrame(chipModel = 2))!!
        assertEquals("ESP32-S2", info.chipModel)
    }

    @Test
    fun `parse ESP32-S3 chip model`() {
        val info = DeviceInfo.parse(infoFrame(chipModel = 3))!!
        assertEquals("ESP32-S3", info.chipModel)
    }

    @Test
    fun `parse ESP32-C3 chip model`() {
        val info = DeviceInfo.parse(infoFrame(chipModel = 4))!!
        assertEquals("ESP32-C3", info.chipModel)
    }

    @Test
    fun `parse unknown chip model`() {
        val info = DeviceInfo.parse(infoFrame(chipModel = 99))!!
        assertEquals("Unknown", info.chipModel)
    }

    @Test
    fun `parse all numeric fields`() {
        val info = DeviceInfo.parse(infoFrame(
            chipRevision = 3,
            cpuFreqMhz = 240,
            flashSizeKb = 4096,
            freeHeapKb = 200,
            psramKb = 8192,
            gpioCount = 34,
            safeOutputCount = 13,
            uptimeMinutes = 1500,
        ))!!
        assertEquals(3, info.chipRevision)
        assertEquals(240, info.cpuFreqMhz)
        assertEquals(4096L, info.flashSizeKb)
        assertEquals(200L, info.freeHeapKb)
        assertEquals(8192, info.psramKb)
        assertEquals(34, info.gpioCount)
        assertEquals(13, info.safeOutputCount)
        assertEquals(1500, info.uptimeMinutes)
    }

    @Test
    fun `parse zero PSRAM`() {
        val info = DeviceInfo.parse(infoFrame(psramKb = 0))!!
        assertEquals(0, info.psramKb)
    }
}
