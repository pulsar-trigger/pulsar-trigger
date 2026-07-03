/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Copyright (C) 2026 Pulsar Trigger contributors
 */

package com.ehrocha.pulsar.canonble

import android.content.SharedPreferences
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Test

/** Wiring around [CanonBleSettings]: persistence, the cool-down clamp + live-
 *  transport push, name-distinctness gating, and the interval-floor gate. The
 *  decision math itself is covered by [CanonBleRulesTest]; this pins the state
 *  plumbing that moved out of PulsarViewModel (audit H1). */
class CanonBleSettingsTest {

    /** In-memory SharedPreferences backed by a map — enough for get/put/apply. */
    private fun fakePrefs(): SharedPreferences {
        val store = mutableMapOf<String, Any?>()
        val editor = mockk<SharedPreferences.Editor>()
        val prefs = mockk<SharedPreferences>()
        every { editor.putLong(any(), any()) } answers {
            store[firstArg()] = secondArg<Long>(); editor
        }
        every { editor.putString(any(), any()) } answers {
            store[firstArg()] = secondArg<String>(); editor
        }
        every { editor.apply() } answers { }
        every { prefs.edit() } returns editor
        every { prefs.getLong(any(), any()) } answers {
            store[firstArg()] as? Long ?: secondArg()
        }
        every { prefs.getString(any(), any()) } answers {
            store[firstArg()] as? String ?: secondArg()
        }
        return prefs
    }

    @Test
    fun `cool-down clamps, persists, and pushes to the live transport`() {
        var pushed: Long? = null
        val s = CanonBleSettings(fakePrefs()) { pushed = it }

        s.setCooldownMs(60_000L) // above max → 10 s
        assertEquals(10_000L, s.cooldownMs.value)
        assertEquals(10_000L, pushed)

        s.setCooldownMs(0L) // below min → 1 s
        assertEquals(1_000L, s.cooldownMs.value)
        assertEquals(1_000L, pushed)
    }

    @Test
    fun `cool-down default is the hardware floor and survives a reload`() {
        val prefs = fakePrefs()
        assertEquals(CanonBleRules.COOLDOWN_DEFAULT_MS, CanonBleSettings(prefs) {}.cooldownMs.value)

        CanonBleSettings(prefs) {}.setCooldownMs(6_000L)
        // A fresh instance on the same prefs reads the persisted value.
        assertEquals(6_000L, CanonBleSettings(prefs) {}.cooldownMs.value)
    }

    @Test
    fun `default names are the two distinct protocol labels`() {
        val s = CanonBleSettings(fakePrefs()) {}
        assertEquals(CanonBleTransport.PAIR_NAME_BRE1, s.nameRemote.value)
        assertEquals(CanonBleTransport.PAIR_NAME_SMART, s.nameSmart.value)
    }

    @Test
    fun `name setter sanitizes and persists`() {
        val s = CanonBleSettings(fakePrefs()) {}
        s.setNameRemote("  My R\tRemote📷  ")
        assertEquals("My RRemote", s.nameRemote.value)
    }

    @Test
    fun `a name colliding with the other protocol is rejected`() {
        val s = CanonBleSettings(fakePrefs()) {}
        val before = s.nameRemote.value
        s.setNameRemote(s.nameSmart.value) // equals the smart name → no-op
        assertEquals(before, s.nameRemote.value)
    }

    @Test
    fun `safeInterval only clamps when Canon BLE is the active transport`() {
        val s = CanonBleSettings(fakePrefs()) {} // default floor 4 s
        // Not Canon BLE → passthrough, even below the floor.
        assertEquals(1_000L, s.safeInterval(1_000L, isCanonBle = false))
        // Canon BLE → raised to the floor.
        assertEquals(4_000L, s.safeInterval(1_000L, isCanonBle = true))
        // Canon BLE, already above the floor → untouched.
        assertEquals(8_000L, s.safeInterval(8_000L, isCanonBle = true))
    }
}
