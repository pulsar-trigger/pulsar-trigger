/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Copyright (C) 2026 Pulsar Trigger contributors
 */

package com.ehrocha.pulsar.transport.ccapi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Lens-name → focal-length parser exercised against the real lens-name
 *  shapes Canon bodies report via /devicestatus/lens. Older bodies (EOS RP)
 *  don't include `focallength` natively; the name is all we have. */
class LensNameParserTest {

    @Test
    fun `RF prime parses to single focal`() {
        val (prime, zoom) = parseFocalFromName("RF16mm F2.8 STM")
        assertEquals(16, prime)
        assertNull(zoom)
    }

    @Test
    fun `EF prime with space parses`() {
        val (prime, zoom) = parseFocalFromName("EF 50mm f/1.8 STM")
        assertEquals(50, prime)
        assertNull(zoom)
    }

    @Test
    fun `RF zoom parses to range`() {
        val (prime, zoom) = parseFocalFromName("RF 24-105mm F4 L IS USM")
        assertNull(prime)
        assertEquals(24..105, zoom)
    }

    @Test
    fun `EF telephoto zoom parses`() {
        val (prime, zoom) = parseFocalFromName("EF 70-200mm f/2.8L II USM")
        assertNull(prime)
        assertEquals(70..200, zoom)
    }

    @Test
    fun `aperture digits in name don't confuse the parser`() {
        // The "2.8" must not be picked up as a focal length; the negative
        // lookbehind on the regex guards against this.
        val (prime, zoom) = parseFocalFromName("RF50mm F1.2 L USM")
        assertEquals(50, prime)
        assertNull(zoom)
    }

    @Test
    fun `unrecognised name returns nulls`() {
        val (prime, zoom) = parseFocalFromName("Some Manual Lens")
        assertNull(prime)
        assertNull(zoom)
    }

    @Test
    fun `empty string returns nulls`() {
        val (prime, zoom) = parseFocalFromName("")
        assertNull(prime)
        assertNull(zoom)
    }

    @Test
    fun `RF wide zoom`() {
        val (prime, zoom) = parseFocalFromName("RF15-35mm F2.8 L IS USM")
        assertNull(prime)
        assertEquals(15..35, zoom)
    }

    @Test
    fun `super-tele prime`() {
        val (prime, zoom) = parseFocalFromName("RF 600mm F4 L IS USM")
        assertEquals(600, prime)
        assertNull(zoom)
    }
}
