package com.ehrocha.pulsar.ble

import org.junit.Assert.*
import org.junit.Test

/** v2 DeviceInfo round-trip tests. */
class DeviceInfoParseTest {

    /** Build a v2 NOTIFY_DEVICE_INFO frame with the supplied TLVs. */
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
        val tlvs = ArrayList<Byte>()
        fun addU8(tag: Byte, v: Int) { tlvs += tag; tlvs += 1; tlvs += (v and 0xFF).toByte() }
        fun addU16(tag: Byte, v: Int) {
            tlvs += tag; tlvs += 2
            tlvs += (v and 0xFF).toByte(); tlvs += ((v shr 8) and 0xFF).toByte()
        }
        fun addU32(tag: Byte, v: Long) {
            tlvs += tag; tlvs += 4
            tlvs += (v and 0xFF).toByte()
            tlvs += ((v shr 8) and 0xFF).toByte()
            tlvs += ((v shr 16) and 0xFF).toByte()
            tlvs += ((v shr 24) and 0xFF).toByte()
        }
        addU8(Tag.CHIP_MODEL, chipModel)
        addU8(Tag.CHIP_REVISION, chipRevision)
        addU8(Tag.CPU_FREQ_MHZ, cpuFreqMhz)
        addU32(Tag.FLASH_SIZE_KB, flashSizeKb)
        addU32(Tag.FREE_HEAP_KB, freeHeapKb)
        addU16(Tag.PSRAM_KB, psramKb)
        addU8(Tag.GPIO_COUNT, gpioCount)
        addU8(Tag.SAFE_OUT_COUNT, safeOutputCount)
        addU16(Tag.UPTIME_MIN, uptimeMinutes)

        val frame = ByteArray(3 + tlvs.size)
        frame[0] = NotifyOp.DEVICE_INFO
        frame[1] = ProtoV2.VERSION
        frame[2] = tlvs.size.toByte()
        tlvs.forEachIndexed { i, b -> frame[3 + i] = b }
        return frame
    }

    @Test fun `parse returns null on short frame`() {
        assertNull(DeviceInfo.parse(ByteArray(2)))
    }

    @Test fun `parse returns null on wrong opcode`() {
        val frame = byteArrayOf(NotifyOp.STATUS, ProtoV2.VERSION, 0)
        assertNull(DeviceInfo.parse(frame))
    }

    @Test fun `parse returns null on version mismatch`() {
        val frame = byteArrayOf(NotifyOp.DEVICE_INFO, 0x99.toByte(), 0)
        assertNull(DeviceInfo.parse(frame))
    }

    @Test fun `parse ESP32 chip model`() {
        assertEquals("ESP32", DeviceInfo.parse(infoFrame(chipModel = 1))!!.chipModel)
    }

    @Test fun `parse ESP32-S2 chip model`() {
        assertEquals("ESP32-S2", DeviceInfo.parse(infoFrame(chipModel = 2))!!.chipModel)
    }

    @Test fun `parse ESP32-S3 chip model`() {
        assertEquals("ESP32-S3", DeviceInfo.parse(infoFrame(chipModel = 3))!!.chipModel)
    }

    @Test fun `parse ESP32-C3 chip model`() {
        assertEquals("ESP32-C3", DeviceInfo.parse(infoFrame(chipModel = 4))!!.chipModel)
    }

    @Test fun `parse unknown chip model`() {
        assertEquals("Unknown", DeviceInfo.parse(infoFrame(chipModel = 99))!!.chipModel)
    }

    @Test fun `parse all numeric fields`() {
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

    @Test fun `parse zero PSRAM`() {
        val info = DeviceInfo.parse(infoFrame(psramKb = 0))!!
        assertEquals(0, info.psramKb)
    }
}
