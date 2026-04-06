package com.ehrocha.pulsar.astro

import org.junit.Assert.*
import org.junit.Test

class WeatherCodeTest {

    @Test
    fun `weatherDescription known codes`() {
        assertEquals("Clear sky", weatherDescription(0))
        assertEquals("Mainly clear", weatherDescription(1))
        assertEquals("Partly cloudy", weatherDescription(2))
        assertEquals("Overcast", weatherDescription(3))
        assertEquals("Fog", weatherDescription(45))
        assertEquals("Fog", weatherDescription(48))
        assertEquals("Drizzle", weatherDescription(51))
        assertEquals("Rain", weatherDescription(63))
        assertEquals("Freezing rain", weatherDescription(66))
        assertEquals("Snowfall", weatherDescription(71))
        assertEquals("Snow grains", weatherDescription(77))
        assertEquals("Rain showers", weatherDescription(80))
        assertEquals("Snow showers", weatherDescription(85))
        assertEquals("Thunderstorm", weatherDescription(95))
        assertEquals("Thunderstorm w/ hail", weatherDescription(96))
    }

    @Test
    fun `weatherDescription unknown code`() {
        assertEquals("Unknown", weatherDescription(999))
    }

    @Test
    fun `weatherEmoji known codes`() {
        assertEquals("☀️", weatherEmoji(0))
        assertEquals("🌤️", weatherEmoji(1))
        assertEquals("⛅", weatherEmoji(2))
        assertEquals("☁️", weatherEmoji(3))
        assertEquals("🌫️", weatherEmoji(45))
        assertEquals("🌧️", weatherEmoji(61))
        assertEquals("🌨️", weatherEmoji(71))
        assertEquals("⛈️", weatherEmoji(95))
    }

    @Test
    fun `weatherEmoji unknown code`() {
        assertEquals("🌡️", weatherEmoji(999))
    }

    @Test
    fun `grouped codes map to same description`() {
        // All drizzle codes
        assertEquals(weatherDescription(51), weatherDescription(53))
        assertEquals(weatherDescription(53), weatherDescription(55))
        // All rain codes
        assertEquals(weatherDescription(61), weatherDescription(63))
        assertEquals(weatherDescription(63), weatherDescription(65))
    }
}
