package com.ehrocha.pulsar.astro

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class MoonPhaseTest {

    @Test
    fun `moonAge is within synodic month range`() {
        val age = MoonPhase.moonAge(LocalDate.of(2025, 6, 15))
        assertTrue("age=$age should be in [0, 29.53)", age >= 0.0 && age < 29.531)
    }

    @Test
    fun `illumination is between 0 and 100`() {
        for (age in listOf(0.0, 7.38, 14.77, 22.15, 29.0)) {
            val illum = MoonPhase.illumination(age)
            assertTrue("illumination=$illum for age=$age", illum in 0.0..100.0)
        }
    }

    @Test
    fun `new moon has low illumination`() {
        val illum = MoonPhase.illumination(0.0)
        assertTrue("new moon illumination=$illum", illum < 5.0)
    }

    @Test
    fun `full moon has high illumination`() {
        // age ~14.77 is full moon center
        val illum = MoonPhase.illumination(14.77)
        assertTrue("full moon illumination=$illum", illum > 95.0)
    }

    @Test
    fun `phaseName at boundaries`() {
        assertEquals("New Moon", MoonPhase.phaseName(0.5))
        assertEquals("Waxing Crescent", MoonPhase.phaseName(4.0))
        assertEquals("First Quarter", MoonPhase.phaseName(8.0))
        assertEquals("Waxing Gibbous", MoonPhase.phaseName(12.0))
        assertEquals("Full Moon", MoonPhase.phaseName(15.0))
        assertEquals("Waning Gibbous", MoonPhase.phaseName(19.0))
        assertEquals("Last Quarter", MoonPhase.phaseName(23.0))
        assertEquals("Waning Crescent", MoonPhase.phaseName(25.0))
        assertEquals("New Moon", MoonPhase.phaseName(28.0))
    }

    @Test
    fun `emoji at boundaries`() {
        assertEquals("🌑", MoonPhase.emoji(0.5))
        assertEquals("🌒", MoonPhase.emoji(4.0))
        assertEquals("🌓", MoonPhase.emoji(8.0))
        assertEquals("🌔", MoonPhase.emoji(12.0))
        assertEquals("🌕", MoonPhase.emoji(15.0))
        assertEquals("🌖", MoonPhase.emoji(19.0))
        assertEquals("🌗", MoonPhase.emoji(23.0))
        assertEquals("🌘", MoonPhase.emoji(25.0))
        assertEquals("🌑", MoonPhase.emoji(28.0))
    }

    @Test
    fun `goodForAstro threshold`() {
        assertTrue(MoonPhase.goodForAstro(0.0))
        assertTrue(MoonPhase.goodForAstro(24.9))
        assertFalse(MoonPhase.goodForAstro(25.0))
        assertFalse(MoonPhase.goodForAstro(50.0))
        assertFalse(MoonPhase.goodForAstro(100.0))
    }

    @Test
    fun `moonAge is deterministic for same date`() {
        val date = LocalDate.of(2025, 1, 1)
        val age1 = MoonPhase.moonAge(date)
        val age2 = MoonPhase.moonAge(date)
        assertEquals(age1, age2, 0.0)
    }

    @Test
    fun `moonAge increases over consecutive days`() {
        val base = LocalDate.of(2025, 6, 1)
        val age1 = MoonPhase.moonAge(base)
        val age2 = MoonPhase.moonAge(base.plusDays(1))
        // Age should increase by ~1 day (or wrap around)
        val diff = if (age2 > age1) age2 - age1 else age2 + 29.53 - age1
        assertTrue("day diff=$diff", diff in 0.8..1.2)
    }
}
