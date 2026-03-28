package com.ehrocha.pulsar.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.ehrocha.pulsar.ble.DeviceState
import com.ehrocha.pulsar.ble.TriggerMode
import com.ehrocha.pulsar.viewmodel.PulsarViewModel

@Composable
fun ControlScreen(vm: PulsarViewModel) {
    val connected by vm.connected.collectAsState()
    val status by vm.status.collectAsState()
    val mode by vm.currentMode.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp)
    ) {
        // ── Header / Status ──────────────────────────────────────────
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Pulsar", style = MaterialTheme.typography.headlineLarge, modifier = Modifier.weight(1f))
            status?.let {
                Text("🔋 ${it.batteryPct}%", style = MaterialTheme.typography.bodyMedium)
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            if (connected) "Connected" else "Disconnected",
            color = if (connected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
        )

        status?.let { s ->
            Spacer(Modifier.height(8.dp))
            Text("State: ${s.state.name}  |  Shots: ${s.shotsTaken}")
        }

        Spacer(Modifier.height(24.dp))
        HorizontalDivider()
        Spacer(Modifier.height(16.dp))

        // ── Mode selector ────────────────────────────────────────────
        Text("Trigger Mode", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))

        val modes = TriggerMode.entries
        var expanded by remember { mutableStateOf(false) }

        ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
            OutlinedTextField(
                value = mode.name.replace('_', ' '),
                onValueChange = {},
                readOnly = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .menuAnchor(),
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            )
            ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                modes.forEach { m ->
                    DropdownMenuItem(
                        text = { Text(m.name.replace('_', ' ')) },
                        onClick = {
                            vm.selectMode(m)
                            expanded = false
                        }
                    )
                }
            }
        }

        Spacer(Modifier.height(24.dp))

        // ── Mode-specific params ─────────────────────────────────────
        when (mode) {
            TriggerMode.INTERVALOMETER -> IntervalometerParams(vm)
            TriggerMode.SOUND -> SoundParams(vm)
            TriggerMode.LIGHTNING -> LightningParams(vm)
            TriggerMode.LASER -> LaserParams(vm)
            TriggerMode.HDR -> Text("HDR: 5-bracket auto (100/200/400/800/1600 ms)")
            TriggerMode.PRESS_HOLD -> Text("Hold START to keep shutter open")
            TriggerMode.PRESS_LOCK -> Text("Tap START to open, STOP to close")
        }

        Spacer(Modifier.height(32.dp))

        // ── Action buttons ───────────────────────────────────────────
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            val isRunning = status?.state == DeviceState.RUNNING || status?.state == DeviceState.WAITING

            Button(
                onClick = { vm.start() },
                enabled = connected && !isRunning,
                modifier = Modifier.weight(1f),
            ) { Text("START") }

            OutlinedButton(
                onClick = { vm.stop() },
                enabled = connected && isRunning,
                modifier = Modifier.weight(1f),
            ) { Text("STOP") }
        }

        Spacer(Modifier.height(12.dp))

        OutlinedButton(
            onClick = { vm.singleShot() },
            enabled = connected,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("SINGLE SHOT") }

        Spacer(Modifier.height(12.dp))

        TextButton(
            onClick = { vm.disconnect() },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Disconnect") }
    }
}

// ── Parameter composables ────────────────────────────────────────────────────

@Composable
private fun IntervalometerParams(vm: PulsarViewModel) {
    val interval by vm.intervalMs.collectAsState()
    val exposure by vm.exposureMs.collectAsState()
    val count by vm.shotCount.collectAsState()
    val delayVal by vm.delayMs.collectAsState()

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ParamSlider("Interval", interval, 500L..60000L, "ms") { vm.intervalMs.value = it }
        ParamSlider("Exposure", exposure, 50L..30000L, "ms") { vm.exposureMs.value = it }
        ParamSlider("Shot count (0=∞)", count.toLong(), 0L..999L, "") { vm.shotCount.value = it.toInt() }
        ParamSlider("Start delay", delayVal, 0L..30000L, "ms") { vm.delayMs.value = it }
    }
}

@Composable
private fun SoundParams(vm: PulsarViewModel) {
    val threshold by vm.soundThreshold.collectAsState()
    val exposure by vm.soundExposureMs.collectAsState()

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ParamSlider("Threshold", threshold.toLong(), 100L..4000L, "") { vm.soundThreshold.value = it.toInt() }
        ParamSlider("Exposure", exposure, 50L..5000L, "ms") { vm.soundExposureMs.value = it }
    }
}

@Composable
private fun LightningParams(vm: PulsarViewModel) {
    val sensitivity by vm.lightningSensitivity.collectAsState()
    val exposure by vm.lightningExposureMs.collectAsState()

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ParamSlider("Sensitivity", sensitivity.toLong(), 1L..5L, "") { vm.lightningSensitivity.value = it.toInt() }
        ParamSlider("Exposure", exposure, 50L..5000L, "ms") { vm.lightningExposureMs.value = it }
    }
}

@Composable
private fun LaserParams(vm: PulsarViewModel) {
    val exposure by vm.laserExposureMs.collectAsState()

    ParamSlider("Exposure", exposure, 50L..5000L, "ms") { vm.laserExposureMs.value = it }
}

@Composable
private fun ParamSlider(label: String, value: Long, range: LongRange, unit: String, onChanged: (Long) -> Unit) {
    Column {
        Row {
            Text(label, modifier = Modifier.weight(1f))
            Text("$value $unit".trim())
        }
        Slider(
            value = value.toFloat(),
            onValueChange = { onChanged(it.toLong()) },
            valueRange = range.first.toFloat()..range.last.toFloat(),
        )
    }
}
