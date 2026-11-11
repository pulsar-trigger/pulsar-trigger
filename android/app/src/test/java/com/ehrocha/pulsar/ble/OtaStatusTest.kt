package com.ehrocha.pulsar.ble

import org.junit.Assert.*
import org.junit.Test

class OtaStatusTest {

    @Test
    fun `fromByte maps all known status codes`() {
        assertEquals(OtaStatus.OK, OtaStatus.fromByte(0x00))
        assertEquals(OtaStatus.ERR_BEGIN, OtaStatus.fromByte(0x01))
        assertEquals(OtaStatus.ERR_WRITE, OtaStatus.fromByte(0x02))
        assertEquals(OtaStatus.ERR_VALIDATE, OtaStatus.fromByte(0x03))
        assertEquals(OtaStatus.ERR_SIZE, OtaStatus.fromByte(0x04))
        assertEquals(OtaStatus.ERR_BOARD, OtaStatus.fromByte(0x05))
        assertEquals(OtaStatus.ERR_BUSY, OtaStatus.fromByte(0x06))
        assertEquals(OtaStatus.READY, OtaStatus.fromByte(0x10))
        assertEquals(OtaStatus.COMPLETE, OtaStatus.fromByte(0x11))
    }

    /** An unknown code used to read as OK — a future firmware error would
     *  have passed for success. */
    @Test
    fun `fromByte unknown is an error, never OK`() {
        for (b in listOf(0x07, 0x12, 0x7F, 0xFF)) {
            val s = OtaStatus.fromByte(b.toByte())
            assertEquals(OtaStatus.UNKNOWN, s)
            assertTrue(s.isError)
        }
    }

    @Test
    fun `only OK, READY and COMPLETE are not errors`() {
        assertFalse(OtaStatus.OK.isError)
        assertFalse(OtaStatus.READY.isError)
        assertFalse(OtaStatus.COMPLETE.isError)
        assertTrue(OtaStatus.ERR_BOARD.isError)
        assertTrue(OtaStatus.ERR_BUSY.isError)
        assertTrue(OtaStatus.ERR_WRITE.isError)
    }
}
