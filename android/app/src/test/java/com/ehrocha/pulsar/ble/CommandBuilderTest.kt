package com.ehrocha.pulsar.ble

import com.ehrocha.pulsar.AppConfig
import org.junit.Assert.*
import org.junit.Test

/**
 * Round-trip and byte-layout tests for protocol v2 frames.
 * Every encoded frame must parse back to the same TLV soup.
 */
class CommandBuilderTest {

    private fun assertEnvelope(frame: ByteArray, expectedOp: Byte) {
        assertTrue("frame too short", frame.size >= 3)
        assertEquals("opcode", expectedOp, frame[0])
        assertEquals("version", ProtoV2.VERSION, frame[1])
        val declared = frame[2].toInt() and 0xFF
        assertEquals("payload length matches buffer", 3 + declared, frame.size)
    }

    /** Decodes the TLVs in a frame into a tag→bytes map. Last-write-wins for
     *  duplicate tags (the v2 protocol allows duplicates but we don't emit them). */
    private fun readTlvs(frame: ByteArray): Map<Byte, ByteArray> {
        assertTrue(frame.size >= 3)
        val out = mutableMapOf<Byte, ByteArray>()
        var p = 3
        while (p < frame.size) {
            val tag = frame[p]; val len = frame[p + 1].toInt() and 0xFF
            out[tag] = frame.copyOfRange(p + 2, p + 2 + len)
            p += 2 + len
        }
        return out
    }

    private fun u16le(b: ByteArray): Int =
        (b[0].toInt() and 0xFF) or ((b[1].toInt() and 0xFF) shl 8)
    private fun u32le(b: ByteArray): Long =
        (b[0].toLong() and 0xFF) or
        ((b[1].toLong() and 0xFF) shl 8) or
        ((b[2].toLong() and 0xFF) shl 16) or
        ((b[3].toLong() and 0xFF) shl 24)

    // ── Envelope shape ─────────────────────────────────────────────────────

    @Test fun `simple frames carry zero-length payload`() {
        val frame = CommandBuilder.start()
        assertEnvelope(frame, Op.START)
        assertEquals(3, frame.size)
    }

    @Test fun `every emitted frame has version 0x02`() {
        for (frame in listOf(
            CommandBuilder.start(), CommandBuilder.stop(), CommandBuilder.shutter(),
            CommandBuilder.statusRequest(), CommandBuilder.deviceInfoRequest(),
            CommandBuilder.setPressHold(), CommandBuilder.setPressLock(),
            CommandBuilder.setIntervalometer(5000L, 200L, 30, 0L),
            CommandBuilder.setFocus(500),
            CommandBuilder.setPins(25, 26),
            CommandBuilder.setAutoOff(5),
            CommandBuilder.setName("Test"),
        )) {
            assertEquals("version on ${frame[0]}", ProtoV2.VERSION, frame[1])
        }
    }

    // ── Opcode mapping ─────────────────────────────────────────────────────

    @Test fun `control opcodes`() {
        assertEnvelope(CommandBuilder.start(), Op.START)
        assertEnvelope(CommandBuilder.stop(), Op.STOP)
        assertEnvelope(CommandBuilder.shutter(), Op.SHUTTER)
        assertEnvelope(CommandBuilder.statusRequest(), Op.STATUS_REQ)
        assertEnvelope(CommandBuilder.deviceInfoRequest(), Op.DEVICE_INFO_REQ)
    }

    @Test fun `mode setters carry distinct opcodes`() {
        assertEnvelope(CommandBuilder.setIntervalometer(1L, 1L, 1, 0L), Op.SET_INTERVALOMETER)
        assertEnvelope(CommandBuilder.setAstro(1L, 1L, 1, 0L), Op.SET_ASTRO)
        assertEnvelope(CommandBuilder.setDarkFrame(1L, 1L, 1, 0L), Op.SET_DARK_FRAME)
        assertEnvelope(CommandBuilder.setRamp(1L, 1L, 1, 0L), Op.SET_RAMP)
        assertEnvelope(CommandBuilder.setPressHold(), Op.SET_PRESS_HOLD)
        assertEnvelope(CommandBuilder.setPressLock(), Op.SET_PRESS_LOCK)
        assertEnvelope(CommandBuilder.setTracker(), Op.SET_TRACKER)
    }

    // ── TLV round-trip ─────────────────────────────────────────────────────

    @Test fun `setIntervalometer round-trips through TLVs`() {
        val frame = CommandBuilder.setIntervalometer(
            intervalMs = 5000L, exposureMs = 2000L, count = 100, delayMs = 1000L,
        )
        val tlvs = readTlvs(frame)
        assertEquals(5000L, u32le(tlvs[Tag.INTERVAL_MS]!!))
        assertEquals(2000L, u32le(tlvs[Tag.EXPOSURE_MS]!!))
        assertEquals(100, u16le(tlvs[Tag.SHOT_COUNT]!!))
        assertEquals(1000L, u32le(tlvs[Tag.DELAY_MS]!!))
    }

    @Test fun `setAstro carries the same TLVs as setIntervalometer`() {
        val intv = CommandBuilder.setIntervalometer(3000L, 1000L, 50, 500L)
        val astro = CommandBuilder.setAstro(3000L, 1000L, 50, 500L)
        // Same payload (TLVs); distinct opcode only.
        assertEquals(Op.SET_INTERVALOMETER, intv[0])
        assertEquals(Op.SET_ASTRO, astro[0])
        assertArrayEquals(
            intv.copyOfRange(2, intv.size),  // length byte onward
            astro.copyOfRange(2, astro.size),
        )
    }

    @Test fun `setFocus encodes FOCUS_MS as u16 LE`() {
        val frame = CommandBuilder.setFocus(500)
        assertEnvelope(frame, Op.SET_FOCUS)
        val tlvs = readTlvs(frame)
        assertEquals(500, u16le(tlvs[Tag.FOCUS_MS]!!))
    }

    @Test fun `setPins encodes two u8 TLVs`() {
        val frame = CommandBuilder.setPins(25, 26)
        assertEnvelope(frame, Op.SET_PINS)
        val tlvs = readTlvs(frame)
        assertEquals(25.toByte(), tlvs[Tag.SHUTTER_PIN]!!.single())
        assertEquals(26.toByte(), tlvs[Tag.FOCUS_PIN]!!.single())
    }

    @Test fun `setAutoOff encodes minutes as u16 LE`() {
        val frame = CommandBuilder.setAutoOff(15)
        assertEnvelope(frame, Op.SET_AUTO_OFF)
        val tlvs = readTlvs(frame)
        assertEquals(15, u16le(tlvs[Tag.AUTO_OFF_MIN]!!))
    }

    @Test fun `setName carries the UTF-8 suffix`() {
        val frame = CommandBuilder.setName("Test")
        assertEnvelope(frame, Op.SET_NAME)
        val tlvs = readTlvs(frame)
        assertArrayEquals("Test".toByteArray(Charsets.UTF_8), tlvs[Tag.NAME_UTF8])
    }

    @Test fun `setName truncates at BLE_DEVICE_NAME_MAX`() {
        val frame = CommandBuilder.setName("A".repeat(20))
        val tlvs = readTlvs(frame)
        val name = tlvs[Tag.NAME_UTF8]!!
        assertEquals(AppConfig.BLE_DEVICE_NAME_MAX, name.size)
        name.forEach { assertEquals('A'.code.toByte(), it) }
    }

    // ── OTA control commands (unchanged from v1 — separate characteristic) ─

    @Test fun `otaBegin encodes BEGIN + u32 LE size`() {
        val payload = CommandBuilder.otaBegin(0x00040000) // 256KB
        assertEquals(5, payload.size)
        assertEquals(OtaCmd.BEGIN, payload[0])
        assertEquals(0x00040000L, u32le(payload.copyOfRange(1, 5)))
    }

    @Test fun `otaEnd is single byte`() {
        assertEquals(1, CommandBuilder.otaEnd().size)
        assertEquals(OtaCmd.END, CommandBuilder.otaEnd()[0])
    }

    @Test fun `otaAbort is single byte`() {
        assertEquals(1, CommandBuilder.otaAbort().size)
        assertEquals(OtaCmd.ABORT, CommandBuilder.otaAbort()[0])
    }
}
