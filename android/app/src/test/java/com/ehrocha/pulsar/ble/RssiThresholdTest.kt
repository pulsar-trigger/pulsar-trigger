package com.ehrocha.pulsar.ble

import com.ehrocha.pulsar.AppConfig
import org.junit.Assert.*
import org.junit.Test

class RssiThresholdTest {

    private fun signalBars(rssi: Int): Int = when {
        rssi >= AppConfig.BLE_RSSI_GOOD -> 3
        rssi >= AppConfig.BLE_RSSI_WEAK -> 2
        else -> 1
    }

    @Test
    fun `strong signal above RSSI_GOOD`() {
        assertEquals(3, signalBars(-40))
        assertEquals(3, signalBars(-60))  // exactly at threshold
    }

    @Test
    fun `medium signal between thresholds`() {
        assertEquals(2, signalBars(-61))
        assertEquals(2, signalBars(-79))
        assertEquals(2, signalBars(-80))  // exactly at weak threshold
    }

    @Test
    fun `weak signal below RSSI_WEAK`() {
        assertEquals(1, signalBars(-81))
        assertEquals(1, signalBars(-100))
    }

    @Test
    fun `thresholds are ordered correctly`() {
        assertTrue("GOOD should be > WEAK", AppConfig.BLE_RSSI_GOOD > AppConfig.BLE_RSSI_WEAK)
    }

    @Test
    fun `poll interval is reasonable`() {
        assertTrue(
            "Poll interval should be between 500ms and 10s",
            AppConfig.BLE_RSSI_POLL_INTERVAL_MS in 500L..10_000L,
        )
    }
}
