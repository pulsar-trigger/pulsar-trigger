/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Copyright (C) 2026 Pulsar Trigger contributors
 */

package com.ehrocha.pulsar.update

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Semver comparison used by both app and firmware OTA flows. Subtle bugs
 *  here either skip valid updates or claim "already up to date" forever. */
class VersionUtilsTest {

    @Test
    fun `patch bump is newer`() {
        assertTrue(isNewerVersion("0.231.0", "0.230.0"))
        assertTrue(isNewerVersion("0.230.1", "0.230.0"))
    }

    @Test
    fun `minor bump is newer`() {
        assertTrue(isNewerVersion("0.231.0", "0.230.5"))
    }

    @Test
    fun `major bump is newer`() {
        assertTrue(isNewerVersion("1.0.0", "0.999.9"))
    }

    @Test
    fun `equal versions are not newer`() {
        assertFalse(isNewerVersion("0.230.0", "0.230.0"))
    }

    @Test
    fun `older patch is not newer`() {
        assertFalse(isNewerVersion("0.230.0", "0.230.1"))
        assertFalse(isNewerVersion("0.229.99", "0.230.0"))
    }

    @Test
    fun `missing patch component treated as zero`() {
        // "0.230" should equal "0.230.0", neither newer than the other.
        assertFalse(isNewerVersion("0.230", "0.230.0"))
        assertFalse(isNewerVersion("0.230.0", "0.230"))
    }

    @Test
    fun `extra components are not newer when prefix equal`() {
        // "0.230.0.0" == "0.230.0" — trailing zeros mean nothing.
        assertFalse(isNewerVersion("0.230.0.0", "0.230.0"))
    }

    @Test
    fun `four-component versions still compare`() {
        assertTrue(isNewerVersion("0.230.0.1", "0.230.0.0"))
    }

    @Test
    fun `non-numeric components are skipped`() {
        // Robustness: garbage components default to 0 rather than throwing.
        assertFalse(isNewerVersion("0.abc.0", "0.0.0"))
    }
}
