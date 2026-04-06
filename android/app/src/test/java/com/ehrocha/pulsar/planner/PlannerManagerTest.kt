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
        every { prefs.edit() } returns editor
        every { editor.putString(any(), any()) } returns editor
        every { editor.apply() } just Runs

        manager = PlannerManager(context)
    }

    @Test
    fun `addLocation adds to state`() {
        val loc = manager.addLocation("Dark Site", 40.0, -74.0)
        assertEquals("Dark Site", loc.name)
        assertEquals(40.0, loc.latitude, 0.001)
        assertEquals(-74.0, loc.longitude, 0.001)
        assertEquals(1, manager.state.value.locations.size)
    }

    @Test
    fun `addLocation respects MAX_SAVED_LOCATIONS`() {
        // Add max locations
        repeat(AppConfig.MAX_SAVED_LOCATIONS) { i ->
            manager.addLocation("Site $i", i.toDouble(), i.toDouble())
        }
        assertEquals(AppConfig.MAX_SAVED_LOCATIONS, manager.state.value.locations.size)

        // Next add should not increase size
        manager.addLocation("Overflow", 99.0, 99.0)
        assertEquals(AppConfig.MAX_SAVED_LOCATIONS, manager.state.value.locations.size)
    }

    @Test
    fun `removeLocation removes from state`() {
        val loc = manager.addLocation("Site", 40.0, -74.0)
        assertEquals(1, manager.state.value.locations.size)
        manager.removeLocation(loc.id)
        assertEquals(0, manager.state.value.locations.size)
    }

    @Test
    fun `removeLocation cascades entries`() {
        val loc = manager.addLocation("Site", 40.0, -74.0)
        manager.addEntry(loc, LocalDate.of(2025, 6, 15))
        assertEquals(1, manager.state.value.entries.size)

        manager.removeLocation(loc.id)
        assertEquals(0, manager.state.value.entries.size)
    }

    @Test
    fun `addEntry adds to state`() {
        val loc = manager.addLocation("Site", 40.0, -74.0)
        val entry = manager.addEntry(loc, LocalDate.of(2025, 6, 15))
        assertEquals(1, manager.state.value.entries.size)
        assertEquals(LocalDate.of(2025, 6, 15), entry.date)
    }

    @Test
    fun `addEntry respects MAX_PLANNER_ENTRIES`() {
        val loc = manager.addLocation("Site", 40.0, -74.0)
        repeat(AppConfig.MAX_PLANNER_ENTRIES) { i ->
            manager.addEntry(loc, LocalDate.of(2025, 1, 1).plusDays(i.toLong()))
        }
        assertEquals(AppConfig.MAX_PLANNER_ENTRIES, manager.state.value.entries.size)

        // Next add should not increase size
        manager.addEntry(loc, LocalDate.of(2026, 1, 1))
        assertEquals(AppConfig.MAX_PLANNER_ENTRIES, manager.state.value.entries.size)
    }

    @Test
    fun `removeEntry removes from state`() {
        val loc = manager.addLocation("Site", 40.0, -74.0)
        val entry = manager.addEntry(loc, LocalDate.of(2025, 6, 15))
        assertEquals(1, manager.state.value.entries.size)
        manager.removeEntry(entry.id)
        assertEquals(0, manager.state.value.entries.size)
    }

    @Test
    fun `updateEntry replaces matching entry`() {
        val loc = manager.addLocation("Site", 40.0, -74.0)
        val entry = manager.addEntry(loc, LocalDate.of(2025, 6, 15))
        val updated = entry.copy(verdict = PlannerVerdict.EXCELLENT, summary = "Great night")
        manager.updateEntry(updated)

        val stored = manager.state.value.entries.first()
        assertEquals(PlannerVerdict.EXCELLENT, stored.verdict)
        assertEquals("Great night", stored.summary)
    }

    @Test
    fun `save is called after each mutation`() {
        manager.addLocation("Site", 40.0, -74.0)
        verify(atLeast = 1) { editor.putString(any(), any()) }
        verify(atLeast = 1) { editor.apply() }
    }
}
