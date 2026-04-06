package com.ehrocha.pulsar.planner

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime

class PlannerDataTest {

    @Test
    fun `PlannerEvent holds fields`() {
        val event = PlannerEvent(
            "id1", "Test Site",
            LocalDate.of(2025, 6, 15), LocalDate.of(2025, 6, 17),
        )
        assertEquals("id1", event.id)
        assertEquals("Test Site", event.name)
        assertEquals(LocalDate.of(2025, 6, 15), event.startDate)
        assertEquals(LocalDate.of(2025, 6, 17), event.endDate)
    }

    @Test
    fun `PlannerSession holds fields`() {
        val session = PlannerSession(
            "s1", "e1", "Waterfall", 40.0, -74.0,
            LocalDate.of(2025, 6, 15),
            LocalTime.of(15, 0), LocalTime.of(16, 0),
        )
        assertEquals("s1", session.id)
        assertEquals("e1", session.eventId)
        assertEquals("Waterfall", session.name)
        assertEquals(40.0, session.latitude, 0.001)
        assertEquals(-74.0, session.longitude, 0.001)
        assertEquals(LocalDate.of(2025, 6, 15), session.date)
        assertEquals(LocalTime.of(15, 0), session.startTime)
        assertEquals(LocalTime.of(16, 0), session.endTime)
    }

    @Test
    fun `PlannerSession defaults`() {
        val session = PlannerSession("s1", "e1", "Shot", 0.0, 0.0, LocalDate.of(2025, 6, 15))
        assertNull(session.startTime)
        assertNull(session.endTime)
        assertEquals(0L, session.lastChecked)
        assertEquals(PlannerVerdict.UNKNOWN, session.verdict)
        assertEquals("", session.summary)
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
        assertTrue(state.events.isEmpty())
        assertTrue(state.sessions.isEmpty())
    }

    @Test
    fun `PlannerSession copy with verdict`() {
        val session = PlannerSession("s1", "e1", "Shot", 0.0, 0.0, LocalDate.of(2025, 6, 15))
        val updated = session.copy(verdict = PlannerVerdict.GOOD, summary = "Clear skies expected")
        assertEquals(PlannerVerdict.GOOD, updated.verdict)
        assertEquals("Clear skies expected", updated.summary)
        assertEquals(session.id, updated.id)
    }

    @Test
    fun `PlannerSession optional time defaults to null`() {
        val session = PlannerSession("s1", "e1", "Shot", 0.0, 0.0, LocalDate.of(2025, 6, 15))
        assertNull(session.startTime)
        assertNull(session.endTime)
    }
}
