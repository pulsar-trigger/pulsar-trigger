package com.ehrocha.pulsar.astro

import org.junit.Assert.*
import org.junit.Test

class DashboardStateTest {

    @Test
    fun `default lastUpdated is null`() {
        val state = DashboardState()
        assertNull(state.lastUpdated)
    }

    @Test
    fun `lastUpdated stores timestamp`() {
        val now = System.currentTimeMillis()
        val state = DashboardState(lastUpdated = now)
        assertEquals(now, state.lastUpdated)
    }

    @Test
    fun `copy preserves lastUpdated`() {
        val now = System.currentTimeMillis()
        val state = DashboardState(lastUpdated = now)
        val copied = state.copy(loading = true)
        assertEquals(now, copied.lastUpdated)
    }

    @Test
    fun `copy can clear lastUpdated`() {
        val state = DashboardState(lastUpdated = 12345L)
        val cleared = state.copy(lastUpdated = null)
        assertNull(cleared.lastUpdated)
    }
}
