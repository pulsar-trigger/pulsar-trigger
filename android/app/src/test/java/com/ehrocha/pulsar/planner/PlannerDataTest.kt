package com.ehrocha.pulsar.planner

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class PlannerDataTest {

    @Test
    fun `SavedLocation holds fields`() {
        val loc = SavedLocation("id1", "Test Site", 40.0, -74.0)
        assertEquals("id1", loc.id)
        assertEquals("Test Site", loc.name)
        assertEquals(40.0, loc.latitude, 0.001)
        assertEquals(-74.0, loc.longitude, 0.001)
    }

    @Test
    fun `PlannerEntry defaults`() {
        val loc = SavedLocation("id1", "Site", 0.0, 0.0)
        val entry = PlannerEntry("e1", loc, LocalDate.of(2025, 6, 15))
        assertEquals(0L, entry.lastChecked)
        assertEquals(PlannerVerdict.UNKNOWN, entry.verdict)
        assertEquals("", entry.summary)
    }

    @Test
    fun `PlannerVerdict has all expected values`() {
        val verdicts = PlannerVerdict.entries.map { it.name }
        assertTrue(verdicts.contains("UNKNOWN"))
        assertTrue(verdicts.contains("EXCELLENT"))
        assertTrue(verdicts.contains("GOOD"))
        assertTrue(verdicts.contains("FAIR"))
        assertTrue(verdicts.contains("POOR"))
        assertEquals(5, verdicts.size)
    }

    @Test
    fun `PlannerState default is empty`() {
        val state = PlannerState()
        assertTrue(state.locations.isEmpty())
        assertTrue(state.entries.isEmpty())
    }

    @Test
    fun `PlannerEntry copy with verdict`() {
        val loc = SavedLocation("id1", "Site", 0.0, 0.0)
        val entry = PlannerEntry("e1", loc, LocalDate.of(2025, 6, 15))
        val updated = entry.copy(verdict = PlannerVerdict.GOOD, summary = "Clear skies expected")
        assertEquals(PlannerVerdict.GOOD, updated.verdict)
        assertEquals("Clear skies expected", updated.summary)
        assertEquals(entry.id, updated.id)
    }
}
