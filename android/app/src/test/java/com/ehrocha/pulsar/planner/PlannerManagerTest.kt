package com.ehrocha.pulsar.planner

import android.content.Context
import android.content.SharedPreferences
import com.ehrocha.pulsar.AppConfig
import io.mockk.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.time.LocalDate

class PlannerManagerTest {

    private lateinit var context: Context
    private lateinit var prefs: SharedPreferences
    private lateinit var editor: SharedPreferences.Editor
    private lateinit var manager: PlannerManager

    @Before
    fun setup() {
        context = mockk(relaxed = true)
        prefs = mockk(relaxed = true)
        editor = mockk(relaxed = true)

        every { context.getSharedPreferences(any(), any()) } returns prefs
        every { prefs.getString(any(), any()) } returns null
        every { prefs.getLong(any(), any()) } returns 24L
        every { prefs.edit() } returns editor
        every { editor.putString(any(), any()) } returns editor
        every { editor.putLong(any(), any()) } returns editor
        every { editor.apply() } just Runs

        manager = PlannerManager(context)
    }

    @Test
    fun `addEvent adds to state`() {
        val event = manager.addEvent("Dark Site", 40.0, -74.0,
            LocalDate.of(2025, 6, 15), LocalDate.of(2025, 6, 17))
        assertEquals("Dark Site", event.name)
        assertEquals(40.0, event.latitude, 0.001)
        assertEquals(-74.0, event.longitude, 0.001)
        assertEquals(1, manager.state.value.events.size)
    }

    @Test
    fun `addEvent creates sessions for each day`() {
        manager.addEvent("Site", 40.0, -74.0,
            LocalDate.of(2025, 6, 15), LocalDate.of(2025, 6, 17))
        // 3 days: 15, 16, 17
        assertEquals(3, manager.state.value.sessions.size)
    }

    @Test
    fun `addEvent respects MAX_SAVED_LOCATIONS`() {
        repeat(AppConfig.MAX_SAVED_LOCATIONS) { i ->
            manager.addEvent("Site $i", i.toDouble(), i.toDouble(),
                LocalDate.of(2025, 6, 15), LocalDate.of(2025, 6, 15))
        }
        assertEquals(AppConfig.MAX_SAVED_LOCATIONS, manager.state.value.events.size)

        // Next add should not increase size
        manager.addEvent("Overflow", 99.0, 99.0,
            LocalDate.of(2025, 6, 15), LocalDate.of(2025, 6, 15))
        assertEquals(AppConfig.MAX_SAVED_LOCATIONS, manager.state.value.events.size)
    }

    @Test
    fun `removeEvent removes from state`() {
        val event = manager.addEvent("Site", 40.0, -74.0,
            LocalDate.of(2025, 6, 15), LocalDate.of(2025, 6, 15))
        assertEquals(1, manager.state.value.events.size)
        manager.removeEvent(event.id)
        assertEquals(0, manager.state.value.events.size)
    }

    @Test
    fun `removeEvent cascades sessions`() {
        val event = manager.addEvent("Site", 40.0, -74.0,
            LocalDate.of(2025, 6, 15), LocalDate.of(2025, 6, 17))
        assertEquals(3, manager.state.value.sessions.size)
        manager.removeEvent(event.id)
        assertEquals(0, manager.state.value.sessions.size)
    }

    @Test
    fun `removeSession removes from state`() {
        manager.addEvent("Site", 40.0, -74.0,
            LocalDate.of(2025, 6, 15), LocalDate.of(2025, 6, 15))
        assertEquals(1, manager.state.value.sessions.size)
        val session = manager.state.value.sessions.first()
        manager.removeSession(session.id)
        assertEquals(0, manager.state.value.sessions.size)
    }

    @Test
    fun `updateSession replaces matching session`() {
        manager.addEvent("Site", 40.0, -74.0,
            LocalDate.of(2025, 6, 15), LocalDate.of(2025, 6, 15))
        val session = manager.state.value.sessions.first()
        val updated = session.copy(verdict = PlannerVerdict.EXCELLENT, summary = "Great night")
        manager.updateSession(updated)

        val stored = manager.state.value.sessions.first()
        assertEquals(PlannerVerdict.EXCELLENT, stored.verdict)
        assertEquals("Great night", stored.summary)
    }

    @Test
    fun `sessionsForEvent returns correct sessions`() {
        val event = manager.addEvent("Site", 40.0, -74.0,
            LocalDate.of(2025, 6, 15), LocalDate.of(2025, 6, 17))
        val sessions = manager.sessionsForEvent(event.id)
        assertEquals(3, sessions.size)
        assertTrue(sessions.all { it.eventId == event.id })
    }

    @Test
    fun `eventById returns correct event`() {
        val event = manager.addEvent("Site", 40.0, -74.0,
            LocalDate.of(2025, 6, 15), LocalDate.of(2025, 6, 15))
        assertEquals(event, manager.eventById(event.id))
        assertNull(manager.eventById("nonexistent"))
    }

    @Test
    fun `exportEvent returns JSON`() {
        val event = manager.addEvent("Site", 40.0, -74.0,
            LocalDate.of(2025, 6, 15), LocalDate.of(2025, 6, 15))
        val json = manager.exportEvent(event.id)
        assertNotNull(json)
        assertTrue(json!!.contains("Site"))
        assertTrue(json.contains("2025-06-15"))
    }

    @Test
    fun `importEvent creates event from JSON`() {
        val event = manager.addEvent("Original", 40.0, -74.0,
            LocalDate.of(2025, 6, 15), LocalDate.of(2025, 6, 15))
        val json = manager.exportEvent(event.id)!!

        val imported = manager.importEvent(json)
        assertNotNull(imported)
        assertEquals("Original", imported!!.name)
        assertEquals(2, manager.state.value.events.size)
    }

    @Test
    fun `save is called after each mutation`() {
        manager.addEvent("Site", 40.0, -74.0,
            LocalDate.of(2025, 6, 15), LocalDate.of(2025, 6, 15))
        verify(atLeast = 1) { editor.putString(any(), any()) }
        verify(atLeast = 1) { editor.apply() }
    }
}
