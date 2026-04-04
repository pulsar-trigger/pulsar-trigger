/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Copyright (C) 2026 Pulsar Trigger contributors
 */

package com.ehrocha.pulsar.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.ehrocha.pulsar.ble.DeviceState
import com.ehrocha.pulsar.ble.StatusFrame
import com.ehrocha.pulsar.ble.TriggerMode
import com.ehrocha.pulsar.ui.theme.DarkColorScheme

@Preview(showBackground = true, widthDp = 380, heightDp = 600, name = "Status Panel – All States")
@Composable
fun LiveStatusPanelPreview() {
    MaterialTheme(colorScheme = DarkColorScheme) {
        Column(modifier = Modifier.padding(16.dp)) {
            // Disconnected
            LiveStatusPanel(
                connected = false,
                status = null,
                currentMode = TriggerMode.INTERVALOMETER,
            )

            Spacer(Modifier.height(12.dp))

            // Connecting
            LiveStatusPanel(
                connected = true,
                status = null,
                currentMode = TriggerMode.INTERVALOMETER,
            )

            Spacer(Modifier.height(12.dp))

            // IDLE with device name
            LiveStatusPanel(
                connected = true,
                status = StatusFrame(
                    state = DeviceState.IDLE,
                    mode = 0x01,
                    shotsTaken = 0,
                    timeRemainingMs = 0,
                    batteryPct = 85,
                    errorCode = 0,
                ),
                currentMode = TriggerMode.INTERVALOMETER,
                deviceName = "Pulsar-Duza",
            )

            Spacer(Modifier.height(12.dp))

            // RUNNING
            LiveStatusPanel(
                connected = true,
                status = StatusFrame(
                    state = DeviceState.RUNNING,
                    mode = 0x01,
                    shotsTaken = 23,
                    timeRemainingMs = 185_000,
                    batteryPct = 62,
                    errorCode = 0,
                ),
                currentMode = TriggerMode.INTERVALOMETER,
                deviceName = "Pulsar-Duza",
            )

            Spacer(Modifier.height(12.dp))

            // WAITING
            LiveStatusPanel(
                connected = true,
                status = StatusFrame(
                    state = DeviceState.WAITING,
                    mode = 0x06,
                    shotsTaken = 5,
                    timeRemainingMs = 42_000,
                    batteryPct = 30,
                    errorCode = 0,
                ),
                currentMode = TriggerMode.ASTRO,
                deviceName = "Pulsar-Duza",
            )

            Spacer(Modifier.height(12.dp))

            // ERROR
            LiveStatusPanel(
                connected = true,
                status = StatusFrame(
                    state = DeviceState.ERROR,
                    mode = 0x01,
                    shotsTaken = 0,
                    timeRemainingMs = 0,
                    batteryPct = 10,
                    errorCode = 1,
                ),
                currentMode = TriggerMode.INTERVALOMETER,
                deviceName = "Pulsar",
            )
        }
    }
}
