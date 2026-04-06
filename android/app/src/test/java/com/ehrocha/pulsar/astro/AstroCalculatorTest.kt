package com.ehrocha.pulsar.astro

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class AstroCalculatorTest {

    // ── Dew point ────────────────────────────────────────────────

    @Test
    fun `dewPoint at 20C 50pct humidity`() {
        val info = AstroCalculator.dewPoint(20.0, 50)
        // Magnus formula: dew point ~ 9.3°C
        assertTrue("dewPoint=${info.dewPointC}", info.dewPointC in 8.0..11.0)
        assertEquals(20.0, info.temperatureC, 0.001)
        assertEquals(DewRisk.NONE, info.risk)
    }

    @Test
    fun `dewPoint CRITICAL when spread is tiny`() {
        // At 10°C, 99% humidity → dew point very close to temp
        val info = AstroCalculator.dewPoint(10.0, 99)
        assertTrue("spread=${info.spreadC}", info.spreadC <= 2.0)
        assertEquals(DewRisk.CRITICAL, info.risk)
    }

    @Test
    fun `dewPoint WARNING when spread 2-4`() {
        // At 15°C, ~85% humidity → spread ~2-4°C
        val info = AstroCalculator.dewPoint(15.0, 85)
        assertTrue("spread=${info.spreadC}", info.spreadC > 2.0 && info.spreadC <= 4.0)
        assertEquals(DewRisk.WARNING, info.risk)
    }

    @Test
    fun `dewPoint NONE for dry conditions`() {
        val info = AstroCalculator.dewPoint(25.0, 30)
        assertEquals(DewRisk.NONE, info.risk)
        assertTrue("spread=${info.spreadC}", info.spreadC > 4.0)
    }

    @Test
    fun `dewPoint clamps humidity to valid range`() {
        // Should not throw even with 0 or 100% humidity
        val low = AstroCalculator.dewPoint(20.0, 0) // clamped to 1
        assertNotNull(low)
        val high = AstroCalculator.dewPoint(20.0, 100)
        assertEquals(DewRisk.CRITICAL, high.risk)
    }

    // ── fmtHour ──────────────────────────────────────────────────

    @Test
    fun `fmtHour midnight`() {
        assertEquals("00:00", AstroCalculator.fmtHour(0.0))
    }

    @Test
    fun `fmtHour noon`() {
        assertEquals("12:00", AstroCalculator.fmtHour(12.0))
    }

    @Test
    fun `fmtHour with minutes`() {
        assertEquals("14:30", AstroCalculator.fmtHour(14.5))
    }

    @Test
    fun `fmtHour wraps past 24`() {
        assertEquals("02:00", AstroCalculator.fmtHour(26.0))
    }

    @Test
    fun `fmtHour handles negative`() {
        assertEquals("23:00", AstroCalculator.fmtHour(-1.0))
    }

    // ── parseIsoHour ─────────────────────────────────────────────

    @Test
    fun `parseIsoHour extracts hour and minute`() {
        val h = AstroCalculator.parseIsoHour("2025-06-15T14:30")
        assertNotNull(h)
        assertEquals(14.5, h!!, 0.01)
    }

    @Test
    fun `parseIsoHour with full ISO string`() {
        val h = AstroCalculator.parseIsoHour("2025-06-15T06:00:00Z")
        assertNotNull(h)
        assertEquals(6.0, h!!, 0.01)
    }

    @Test
    fun `parseIsoHour returns null for null`() {
        assertNull(AstroCalculator.parseIsoHour(null))
    }

    @Test
    fun `parseIsoHour returns null for empty`() {
        assertNull(AstroCalculator.parseIsoHour(""))
    }

    @Test
    fun `parseIsoHour returns null for no T separator`() {
        assertNull(AstroCalculator.parseIsoHour("2025-06-15"))
    }

    // ── altitude ─────────────────────────────────────────────────

    @Test
    fun `altitude at meridian transit`() {
        // Object at declination=0 passing meridian at equator: should be ~90°
        val alt = AstroCalculator.altitude(0.0, 0.0, 0.0)
        assertEquals(90.0, alt, 1.0)
    }

    @Test
    fun `altitude below horizon`() {
        // Object 180° away in hour angle should be below horizon at equator
        val alt = AstroCalculator.altitude(0.0, 0.0, 180.0)
        assertTrue("alt=$alt should be < 0", alt < 0)
    }

    @Test
    fun `altitude at pole for circumpolar object`() {
        // At north pole (90°), object at dec=45° always above horizon
        val alt = AstroCalculator.altitude(90.0, 45.0, 0.0)
        assertEquals(45.0, alt, 1.0)
    }

    // ── lst ──────────────────────────────────────────────────────

    @Test
    fun `lst returns value in 0-360 range`() {
        val siderealTime = AstroCalculator.lst(LocalDate.of(2025, 6, 15), 12.0, 0.0)
        assertTrue("lst=$siderealTime", siderealTime in 0.0..360.0)
    }

    @Test
    fun `lst increases with longitude`() {
        val lst0 = AstroCalculator.lst(LocalDate.of(2025, 6, 15), 12.0, 0.0)
        val lst90 = AstroCalculator.lst(LocalDate.of(2025, 6, 15), 12.0, 90.0)
        val diff = ((lst90 - lst0) + 360) % 360
        assertEquals(90.0, diff, 1.0)
    }
}
