package com.ehrocha.pulsar.ble

import org.junit.Assert.*
import org.junit.Test

/** v2 StatusFrame round-trip tests. Builds frames with the same TLV layout
 *  the firmware emits and asserts the parser populates each field. */
class StatusFrameParseTest {

    /** Helper: build a v2 NOTIFY_STATUS frame with the supplied TLVs. */
    private fun statusFrame(
        state: DeviceState = DeviceState.IDLE,
        mode: Byte = Op.SET_INTERVALOMETER,
        shotsTaken: Int = 0,
        timeRemainingMs: Long = 0L,
        batteryPct: Int = 100,
        errorCode: Int = 0,
        fwVersion: Triple<Int, Int, Int>? = null,
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
        addU8(Tag.STATE, state.id.toInt() and 0xFF)
        addU8(Tag.MODE, mode.toInt() and 0xFF)
        addU16(Tag.SHOTS_TAKEN, shotsTaken)
        addU32(Tag.TIME_REMAIN_MS, timeRemainingMs)
        addU8(Tag.BATTERY_PCT, batteryPct)
        addU8(Tag.ERROR_CODE, errorCode)
        if (fwVersion != null) {
            tlvs += Tag.FW_VERSION; tlvs += 3
            tlvs += fwVersion.first.toByte()
            tlvs += fwVersion.second.toByte()
            tlvs += fwVersion.third.toByte()
        }
        val payloadLen = tlvs.size
        val frame = ByteArray(3 + payloadLen)
        frame[0] = NotifyOp.STATUS
        frame[1] = ProtoV2.VERSION
        frame[2] = payloadLen.toByte()
        tlvs.forEachIndexed { i, b -> frame[3 + i] = b }
        return frame
    }

    // ── Envelope validation ────────────────────────────────────────────────

    @Test fun `parse returns null on empty or too-short frame`() {
        assertNull(StatusFrame.parse(ByteArray(0)))
        assertNull(StatusFrame.parse(ByteArray(2)))
    }

    @Test fun `parse returns null on wrong opcode`() {
        // NOTIFY_DEVICE_INFO instead of NOTIFY_STATUS
        val frame = byteArrayOf(NotifyOp.DEVICE_INFO, ProtoV2.VERSION, 0)
        assertNull(StatusFrame.parse(frame))
    }

    @Test fun `parse returns null on version mismatch`() {
        val frame = byteArrayOf(NotifyOp.STATUS, 0x99.toByte(), 0)
        assertNull(StatusFrame.parse(frame))
    }

    @Test fun `parse returns null on truncated payload`() {
        // declared len 5 but frame only has 3 bytes after envelope
        val frame = byteArrayOf(NotifyOp.STATUS, ProtoV2.VERSION, 5, 0x01, 0x02, 0x03)
        assertNull(StatusFrame.parse(frame))
    }

    // ── Field round-trip ───────────────────────────────────────────────────

    @Test fun `IDLE frame with defaults`() {
        val sf = StatusFrame.parse(statusFrame())!!
        assertEquals(DeviceState.IDLE, sf.state)
        assertEquals(Op.SET_INTERVALOMETER, sf.mode)
        assertEquals(0, sf.shotsTaken)
        assertEquals(0L, sf.timeRemainingMs)
        assertEquals(100, sf.batteryPct)
        assertEquals(0, sf.errorCode)
        assertEquals("", sf.fwVersion)
    }

    @Test fun `RUNNING frame with shots and time`() {
        val sf = StatusFrame.parse(statusFrame(
            state = DeviceState.RUNNING,
            shotsTaken = 5, timeRemainingMs = 5_000L, batteryPct = 75,
        ))!!
        assertEquals(DeviceState.RUNNING, sf.state)
        assertEquals(5, sf.shotsTaken)
        assertEquals(5_000L, sf.timeRemainingMs)
        assertEquals(75, sf.batteryPct)
    }

    @Test fun `firmware version`() {
        val sf = StatusFrame.parse(statusFrame(fwVersion = Triple(0, 7, 1)))!!
        assertEquals("0.7.1", sf.fwVersion)
    }

    @Test fun `large shotsTaken u16`() {
        val sf = StatusFrame.parse(statusFrame(shotsTaken = 1000))!!
        assertEquals(1000, sf.shotsTaken)
    }

    @Test fun `large timeRemainingMs u32`() {
        val sf = StatusFrame.parse(statusFrame(timeRemainingMs = 1_000_000L))!!
        assertEquals(1_000_000L, sf.timeRemainingMs)
    }

    @Test fun `every DeviceState round-trips`() {
        for (state in DeviceState.entries) {
            val sf = StatusFrame.parse(statusFrame(state = state))!!
            assertEquals(state, sf.state)
        }
    }

    @Test fun `DeviceState fromByte unknown defaults to IDLE`() {
        assertEquals(DeviceState.IDLE, DeviceState.fromByte(0xFF.toByte()))
    }

    @Test fun `ERROR state preserves error code`() {
        val sf = StatusFrame.parse(statusFrame(state = DeviceState.ERROR, errorCode = 5))!!
        assertEquals(DeviceState.ERROR, sf.state)
        assertEquals(5, sf.errorCode)
    }
}
