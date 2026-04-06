package com.ehrocha.pulsar.astro

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class BestPhotoWindowsTest {

    private fun forecast(time: String, cloudCoverPct: Int, precip: Double = 0.0, code: Int = 0) =
        HourlyForecast(time, 15.0, cloudCoverPct, precip, code)

    @Test
    fun `empty hourly returns empty windows`() {
        val result = AstroCalculator.bestPhotoWindows(emptyList(), "2025-06-15T06:00", "2025-06-15T20:00")
        assertTrue(result.isEmpty())
    }

    @Test
    fun `no sunset ISO returns empty`() {
        val hourly = listOf(forecast("2025-06-15T22:00", 10))
        val result = AstroCalculator.bestPhotoWindows(hourly, "2025-06-15T06:00", null)
        assertTrue(result.isEmpty())
    }

    @Test
    fun `clear night hours produce windows`() {
        // Sunset at 20:00, sunrise at 06:00
        // Dark after ~21:00 (20:00 + 1h offset)
        val hourly = (21..23).map { h ->
            forecast("2025-06-15T${String.format("%02d", h)}:00", cloudCoverPct = 10)
        }
        val result = AstroCalculator.bestPhotoWindows(hourly, "2025-06-16T06:00", "2025-06-15T20:00")
        assertTrue("should find windows", result.isNotEmpty())
    }

    @Test
    fun `rainy hours are excluded`() {
        val hourly = (22..23).map { h ->
            forecast("2025-06-15T${String.format("%02d", h)}:00", cloudCoverPct = 10, precip = 5.0)
        }
        val result = AstroCalculator.bestPhotoWindows(hourly, "2025-06-16T06:00", "2025-06-15T20:00")
        assertTrue("rainy hours should be excluded", result.isEmpty())
    }
}
