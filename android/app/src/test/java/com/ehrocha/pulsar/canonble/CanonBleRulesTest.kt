/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Copyright (C) 2026 Pulsar Trigger contributors
 */

package com.ehrocha.pulsar.canonble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure decision logic for the Canon BLE transport (extracted from
 *  PulsarViewModel). Each of these was tuned against hardware during the
 *  direct-drive bulb saga; the tests pin the exact thresholds so a future
 *  refactor (H1 VM split) can't silently drift them. */
class CanonBleRulesTest {

    // --- clampCooldown -----------------------------------------------------

    @Test
    fun `cooldown clamps to the supported range`() {
        assertEquals(1_000L, CanonBleRules.clampCooldown(0L))
        assertEquals(1_000L, CanonBleRules.clampCooldown(-5_000L))
        assertEquals(10_000L, CanonBleRules.clampCooldown(60_000L))
    }

    @Test
    fun `cooldown passes an in-range value through`() {
        assertEquals(4_000L, CanonBleRules.clampCooldown(4_000L))
        assertEquals(1_000L, CanonBleRules.clampCooldown(1_000L))
        assertEquals(10_000L, CanonBleRules.clampCooldown(10_000L))
    }

    // --- sanitizeName ------------------------------------------------------

    @Test
    fun `name keeps printable ascii and trims`() {
        assertEquals("Pulsar-R", CanonBleRules.sanitizeName("  Pulsar-R  ", "fallback"))
    }

    @Test
    fun `name strips non-ascii and control chars`() {
        // Everything outside 0x20..0x7E is dropped (not transliterated): the
        // accent, the tab, and the emoji all vanish, leaving the ascii runs.
        assertEquals("Cmera", CanonBleRules.sanitizeName("Câmera", "fallback"))
        assertEquals("Camera", CanonBleRules.sanitizeName("Ca\tmera📷", "fallback"))
    }

    @Test
    fun `name caps at 20 chars`() {
        val long = "ABCDEFGHIJKLMNOPQRSTUVWXYZ" // 26 chars
        assertEquals(20, CanonBleRules.sanitizeName(long, "fallback").length)
        assertEquals("ABCDEFGHIJKLMNOPQRST", CanonBleRules.sanitizeName(long, "fallback"))
    }

    @Test
    fun `blank or all-stripped name falls back`() {
        assertEquals("Pulsar-S", CanonBleRules.sanitizeName("", "Pulsar-S"))
        assertEquals("Pulsar-S", CanonBleRules.sanitizeName("   ", "Pulsar-S"))
        assertEquals("Pulsar-S", CanonBleRules.sanitizeName("📷", "Pulsar-S"))
    }

    // --- namesDistinct -----------------------------------------------------

    @Test
    fun `names must differ case-insensitively`() {
        assertFalse(CanonBleRules.namesDistinct("Pulsar-R", "Pulsar-R"))
        assertFalse(CanonBleRules.namesDistinct("pulsar-r", "PULSAR-R"))
        assertTrue(CanonBleRules.namesDistinct("Pulsar-R", "Pulsar-S"))
    }

    // --- safeInterval ------------------------------------------------------

    @Test
    fun `interval below the floor is raised`() {
        assertEquals(4_000L, CanonBleRules.safeInterval(2_000L, 4_000L))
        assertEquals(4_000L, CanonBleRules.safeInterval(0L, 4_000L))
    }

    @Test
    fun `interval at or above the floor passes through`() {
        assertEquals(4_000L, CanonBleRules.safeInterval(4_000L, 4_000L))
        assertEquals(10_000L, CanonBleRules.safeInterval(10_000L, 4_000L))
    }

    // --- shouldRemindDial --------------------------------------------------

    @Test
    fun `no dial reminder on first observation`() {
        // prev == null → the user hasn't set a baseline yet.
        assertFalse(CanonBleRules.shouldRemindDial(prev = null, next = true, transportActive = true))
    }

    @Test
    fun `dial reminder only on an actual change`() {
        assertTrue(CanonBleRules.shouldRemindDial(prev = false, next = true, transportActive = true))
        assertTrue(CanonBleRules.shouldRemindDial(prev = true, next = false, transportActive = true))
        assertFalse(CanonBleRules.shouldRemindDial(prev = true, next = true, transportActive = true))
    }

    @Test
    fun `no dial reminder when Canon BLE is not the active transport`() {
        assertFalse(CanonBleRules.shouldRemindDial(prev = false, next = true, transportActive = false))
    }

    // --- manualTestShots ---------------------------------------------------

    @Test
    fun `manual test fires an even count on Canon BLE`() {
        val shots = CanonBleRules.manualTestShots(isCanonBle = true)
        assertEquals(2, shots)
        // The parity is the point: an even [00,01] count returns the toggle
        // shutter to CLOSED on either dial.
        assertEquals(0, shots % 2)
    }

    @Test
    fun `manual test fires a single shot on non-toggle transports`() {
        assertEquals(1, CanonBleRules.manualTestShots(isCanonBle = false))
    }
}
