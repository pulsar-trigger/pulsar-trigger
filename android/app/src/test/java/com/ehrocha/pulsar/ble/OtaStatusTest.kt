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
        assertEquals(OtaStatus.READY, OtaStatus.fromByte(0x10))
        assertEquals(OtaStatus.COMPLETE, OtaStatus.fromByte(0x11))
    }

    @Test
    fun `fromByte unknown defaults to OK`() {
        assertEquals(OtaStatus.OK, OtaStatus.fromByte(0xFF.toByte()))
    }
}
