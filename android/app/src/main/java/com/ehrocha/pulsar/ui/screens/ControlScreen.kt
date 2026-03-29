package com.ehrocha.pulsar.ui.screens

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.ehrocha.pulsar.ble.DeviceState
import com.ehrocha.pulsar.ble.TriggerMode
import com.ehrocha.pulsar.ui.components.LiveStatusPanel
import com.ehrocha.pulsar.ui.components.ScrollPicker
import com.ehrocha.pulsar.ui.components.TimePicker
import com.ehrocha.pulsar.viewmodel.PulsarViewModel
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ControlScreen(vm: PulsarViewModel) {
    val connected by vm.connected.collectAsState()
    val status by vm.status.collectAsState()
    val mode by vm.currentMode.collectAsState()

    val modes = TriggerMode.entries.filter {
        it !in setOf(
            TriggerMode.SOUND, TriggerMode.LIGHTNING, TriggerMode.LASER,
            TriggerMode.HDR, TriggerMode.PRESS_LOCK,
        )
    }
    val pagerState = rememberPagerState(
        initialPage = modes.indexOf(mode),
        pageCount = { modes.size },
    )
    val scope = rememberCoroutineScope()

    // Sync pager → viewmodel
    LaunchedEffect(pagerState.currentPage) {
        val pageMode = modes[pagerState.currentPage]
        // Don't overwrite PRESS_LOCK when the pager is on the Manual (PRESS_HOLD) page
        if (pageMode == TriggerMode.PRESS_HOLD && mode == TriggerMode.PRESS_LOCK) return@LaunchedEffect
        if (pageMode != mode) vm.selectMode(pageMode)
    }
    // Sync viewmodel → pager
    LaunchedEffect(mode) {
        // Treat PRESS_LOCK as the PRESS_HOLD page (Manual tab)
        val effective = if (mode == TriggerMode.PRESS_LOCK) TriggerMode.PRESS_HOLD else mode
        val idx = modes.indexOf(effective)
        if (idx >= 0 && idx != pagerState.currentPage) pagerState.animateScrollToPage(idx)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        // ── Live Status Panel ────────────────────────────────────────
        LiveStatusPanel(
            connected = connected,
            status = status,
            currentMode = mode,
        )

        Spacer(Modifier.height(16.dp))

        val isRunning = status?.state == DeviceState.RUNNING || status?.state == DeviceState.WAITING

        // ── Tab row for modes ────────────────────────────────────────
        ScrollableTabRow(
            selectedTabIndex = pagerState.currentPage,
            edgePadding = 0.dp,
            divider = {},
            modifier = Modifier.alpha(if (isRunning) 0.5f else 1f),
        ) {
            modes.forEachIndexed { index, m ->
                val label = if (m == TriggerMode.PRESS_HOLD) "MANUAL" else m.name.replace('_', ' ')
                Tab(
                    selected = pagerState.currentPage == index,
                    onClick = { if (!isRunning) scope.launch { pagerState.animateScrollToPage(index) } },
                    enabled = !isRunning,
                    text = { Text(label, maxLines = 1) },
                )
            }
        }

        Spacer(Modifier.height(12.dp))

        // ── Pager with dedicated mode panels ─────────────────────────
        HorizontalPager(
            state = pagerState,
            userScrollEnabled = !isRunning,
            modifier = Modifier.weight(1f),
        ) { page ->
            Surface(
                shape = RoundedCornerShape(16.dp),
                tonalElevation = 1.dp,
                modifier = Modifier.fillMaxSize(),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(16.dp),
                ) {
                    when (modes[page]) {
                        TriggerMode.INTERVALOMETER -> IntervalometerPanel(vm, enabled = !isRunning)
                        TriggerMode.ASTRO -> AstroPanel(vm, enabled = !isRunning)
                        TriggerMode.SOUND -> SoundPanel(vm, enabled = !isRunning)
                        TriggerMode.LIGHTNING -> LightningPanel(vm, enabled = !isRunning)
                        TriggerMode.LASER -> LaserPanel(vm, enabled = !isRunning)
                        TriggerMode.HDR -> HdrPanel()
                        TriggerMode.PRESS_HOLD -> ManualPanel(vm, enabled = !isRunning)
                        else -> {}
                    }
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        // ── Keep notification updated while running ──────────────────
        LaunchedEffect(isRunning, status?.shotsTaken) {
            if (isRunning) vm.updateNotification()
        }

        // ── Action buttons (mode-aware) ──────────────────────────────
        when (mode) {
            TriggerMode.PRESS_HOLD, TriggerMode.PRESS_LOCK -> ManualActions(vm, connected, mode)
            else -> DefaultActions(vm, connected, isRunning)
        }
    }
}

@Composable
private fun DefaultActions(vm: PulsarViewModel, connected: Boolean, isRunning: Boolean) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Button(
            onClick = { vm.start() },
            enabled = connected && !isRunning,
            modifier = Modifier.weight(1f),
        ) { Text("START") }

        Button(
            onClick = { vm.stop() },
            enabled = connected && isRunning,
            modifier = Modifier.weight(1f),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.error,
            ),
        ) { Text("CANCEL") }
    }

    Spacer(Modifier.height(8.dp))

    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedButton(
            onClick = { vm.singleShot() },
            enabled = connected && !isRunning,
            modifier = Modifier.weight(1f),
        ) { Text("SINGLE SHOT") }

        TextButton(
            onClick = { vm.disconnect() },
            enabled = !isRunning,
            modifier = Modifier.weight(1f),
        ) { Text("Disconnect") }
    }
}

@Composable
private fun ManualActions(vm: PulsarViewModel, connected: Boolean, mode: TriggerMode) {
    val isHold = mode == TriggerMode.PRESS_HOLD
    var active by remember { mutableStateOf(false) }

    // Reset state when switching between Hold / Lock
    LaunchedEffect(mode) { active = false }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Surface(
            shape = CircleShape,
            color = if (active) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.primary,
            tonalElevation = if (active) 8.dp else 2.dp,
            modifier = Modifier
                .size(120.dp)
                .pointerInput(connected, isHold) {
                    if (!connected) return@pointerInput
                    if (isHold) {
                        detectTapGestures(
                            onPress = {
                                active = true
                                vm.shutterDown()
                                try { awaitRelease() } finally {
                                    active = false
                                    vm.shutterUp()
                                }
                            },
                        )
                    } else {
                        detectTapGestures(
                            onPress = {
                                if (!active) {
                                    active = true
                                    vm.shutterDown()
                                } else {
                                    active = false
                                    vm.shutterUp()
                                }
                            },
                        )
                    }
                },
        ) {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                Text(
                    text = if (isHold) {
                        if (active) "RELEASE" else "HOLD"
                    } else {
                        if (active) "STOP" else "START"
                    },
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onPrimary,
                    textAlign = TextAlign.Center,
                )
            }
        }

        Spacer(Modifier.height(8.dp))
        Text(
            text = if (isHold) {
                if (active) "Shutter open…" else "Press and hold to open shutter"
            } else {
                if (active) "Shutter open… tap to close" else "Tap to open shutter"
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(16.dp))

        TextButton(
            onClick = { vm.disconnect() },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Disconnect") }
    }
}

// ── Dedicated mode panels ────────────────────────────────────────────────────

@Composable
private fun IntervalometerPanel(vm: PulsarViewModel, enabled: Boolean = true) {
    val interval by vm.intervalMs.collectAsState()
    val exposure by vm.exposureMs.collectAsState()
    val count by vm.shotCount.collectAsState()
    val delayVal by vm.delayMs.collectAsState()

    Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Text("Intervalometer", style = MaterialTheme.typography.titleLarge)

        // ── Presets ──────────────────────────────────────────────────
        if (enabled) {
            PresetChips(
                onApply = { i, e, c, d ->
                    vm.intervalMs.value = i
                    vm.exposureMs.value = e
                    vm.shotCount.value = c
                    vm.delayMs.value = d
                },
            )
        }

        TimePicker(
            totalMs = interval,
            onChanged = { vm.intervalMs.value = it.coerceAtLeast(500) },
            label = "Interval (gap between shots)",
            enabled = enabled,
        )

        TimePicker(
            totalMs = exposure,
            onChanged = { vm.exposureMs.value = it.coerceAtLeast(50) },
            label = "Exposure",
            enabled = enabled,
        )

        TimePicker(
            totalMs = delayVal,
            onChanged = { vm.delayMs.value = it },
            label = "Start Delay",
            enabled = enabled,
        )

        // Shot count picker
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                "Shots (0 = ∞)",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            ScrollPicker(
                value = count,
                range = 0..999,
                onValueChange = { vm.shotCount.value = it },
                format = { "$it" },
                enabled = enabled,
            )
        }
    }
}

@Composable
private fun SoundPanel(vm: PulsarViewModel, enabled: Boolean = true) {
    val threshold by vm.soundThreshold.collectAsState()
    val exposure by vm.soundExposureMs.collectAsState()

    Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Text("Sound Trigger", style = MaterialTheme.typography.titleLarge)

        Text(
            "Triggers the shutter when a loud sound is detected.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                "Threshold",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            ScrollPicker(
                value = threshold,
                range = 100..4000,
                onValueChange = { vm.soundThreshold.value = it },
                format = { "$it" },
                enabled = enabled,
            )
        }

        TimePicker(
            totalMs = exposure,
            onChanged = { vm.soundExposureMs.value = it.coerceAtLeast(50) },
            label = "Exposure",
            enabled = enabled,
        )
    }
}

@Composable
private fun LightningPanel(vm: PulsarViewModel, enabled: Boolean = true) {
    val sensitivity by vm.lightningSensitivity.collectAsState()
    val exposure by vm.lightningExposureMs.collectAsState()

    Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Text("Lightning", style = MaterialTheme.typography.titleLarge)

        Text(
            "Detects sudden brightness changes to capture lightning.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                "Sensitivity (1–5)",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            ScrollPicker(
                value = sensitivity,
                range = 1..5,
                onValueChange = { vm.lightningSensitivity.value = it },
                format = { "$it" },
                enabled = enabled,
            )
        }

        TimePicker(
            totalMs = exposure,
            onChanged = { vm.lightningExposureMs.value = it.coerceAtLeast(50) },
            label = "Exposure",
            enabled = enabled,
        )
    }
}

@Composable
private fun LaserPanel(vm: PulsarViewModel, enabled: Boolean = true) {
    val exposure by vm.laserExposureMs.collectAsState()

    Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Text("Laser Trigger", style = MaterialTheme.typography.titleLarge)

        Text(
            "Fires the shutter when a laser beam is broken.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        TimePicker(
            totalMs = exposure,
            onChanged = { vm.laserExposureMs.value = it.coerceAtLeast(50) },
            label = "Exposure",
            enabled = enabled,
        )
    }
}

@Composable
private fun HdrPanel() {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("HDR Bracketing", style = MaterialTheme.typography.titleLarge)

        Text(
            "Automatic 5-bracket HDR sequence.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        val brackets = listOf("100 ms", "200 ms", "400 ms", "800 ms", "1600 ms")
        brackets.forEachIndexed { i, b ->
            Surface(
                shape = RoundedCornerShape(8.dp),
                tonalElevation = 4.dp,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(modifier = Modifier.padding(12.dp)) {
                    Text("Bracket ${i + 1}", modifier = Modifier.weight(1f))
                    Text(b, color = MaterialTheme.colorScheme.primary)
                }
            }
        }
    }
}

@Composable
private fun ManualPanel(vm: PulsarViewModel, enabled: Boolean = true) {
    val mode by vm.currentMode.collectAsState()
    val isLock = mode == TriggerMode.PRESS_LOCK

    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("Manual Shutter", style = MaterialTheme.typography.titleLarge)

        Text(
            "Control the shutter directly. Choose Hold (press and hold) or Lock (toggle on/off).",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        // ── Mode toggle ──────────────────────────────────────────
        Text(
            "Behaviour",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = !isLock,
                onClick = { if (enabled) vm.selectMode(TriggerMode.PRESS_HOLD) },
                enabled = enabled,
                label = { Text("Hold") },
            )
            FilterChip(
                selected = isLock,
                onClick = { if (enabled) vm.selectMode(TriggerMode.PRESS_LOCK) },
                enabled = enabled,
                label = { Text("Lock") },
            )
        }

        Surface(
            shape = RoundedCornerShape(12.dp),
            tonalElevation = 2.dp,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                text = if (isLock)
                    "Tap the button to open the shutter, tap again to close. Useful for very long exposures."
                else
                    "Press and hold the button to keep the shutter open. Release to close. Ideal for bulb mode.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(12.dp),
            )
        }
    }
}

// ── Astro Mode ───────────────────────────────────────────────────────────────

private data class SensorPreset(val label: String, val crop: Float)

private val SENSOR_PRESETS = listOf(
    SensorPreset("Full Frame", 1.0f),
    SensorPreset("APS-C (Canon)", 1.6f),
    SensorPreset("APS-C (Nikon/Sony)", 1.5f),
    SensorPreset("Micro 4/3", 2.0f),
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AstroPanel(vm: PulsarViewModel, enabled: Boolean = true) {
    val focalLength by vm.astroFocalLength.collectAsState()
    val cropFactor by vm.astroCropFactor.collectAsState()
    val ruleDivisor by vm.astroRuleDivisor.collectAsState()
    val shotCount by vm.astroShotCount.collectAsState()
    val delayVal by vm.astroDelayMs.collectAsState()
    val gapMs by vm.astroGapMs.collectAsState()

    // Rule calculation (500 or 400)
    val maxExposureS = ruleDivisor.toDouble() / (focalLength * cropFactor)
    val maxExposureMs = (maxExposureS * 1000).toLong().coerceAtLeast(100)
    val intervalMs = maxExposureMs + gapMs

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Astro Mode", style = MaterialTheme.typography.titleLarge)

        Text(
            "Calculates optimal exposure to avoid star trails. " +
            "The 500 Rule is more permissive; use the 400 Rule for sharper results.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        // ── Rule selector chips ──────────────────────────────────────
        Text(
            "Exposure Rule",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(500 to "500 Rule", 400 to "400 Rule").forEach { (divisor, label) ->
                FilterChip(
                    selected = ruleDivisor == divisor,
                    onClick = { if (enabled) vm.astroRuleDivisor.value = divisor },
                    enabled = enabled,
                    label = { Text(label, style = MaterialTheme.typography.labelMedium) },
                )
            }
        }

        // ── Sensor type chips ────────────────────────────────────────
        Text(
            "Sensor Type",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            SENSOR_PRESETS.forEach { preset ->
                FilterChip(
                    selected = cropFactor == preset.crop,
                    onClick = { if (enabled) vm.astroCropFactor.value = preset.crop },
                    enabled = enabled,
                    label = { Text(preset.label, style = MaterialTheme.typography.labelMedium) },
                )
            }
        }

        // ── Focal length ─────────────────────────────────────────────
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                "Focal Length (mm)",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            ScrollPicker(
                value = focalLength,
                range = 8..600,
                onValueChange = { vm.astroFocalLength.value = it },
                format = { "$it" },
                enabled = enabled,
            )
        }

        // ── Calculated exposure result ───────────────────────────────
        Surface(
            shape = RoundedCornerShape(12.dp),
            tonalElevation = 4.dp,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    "$ruleDivisor Rule Result",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.height(8.dp))
                Row(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Max Exposure", style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(
                            formatDuration(maxExposureMs),
                            style = MaterialTheme.typography.headlineSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Interval", style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(
                            formatDuration(intervalMs),
                            style = MaterialTheme.typography.headlineSmall,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    "$ruleDivisor ÷ ($focalLength mm × ${cropFactor}x) = ${"%.1f".format(maxExposureS)} s",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // ── Gap + Shot count (side by side) ─────────────────────────
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    "Gap (s)",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                ScrollPicker(
                    value = (gapMs / 1000).toInt(),
                    range = 1..10,
                    onValueChange = { vm.astroGapMs.value = it * 1000L },
                    format = { "${it}s" },
                    enabled = enabled,
                )
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    "Shots (0 = ∞)",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                ScrollPicker(
                    value = shotCount,
                    range = 0..999,
                    onValueChange = { vm.astroShotCount.value = it },
                    format = { "$it" },
                    enabled = enabled,
                )
            }
        }

        // ── Start delay ──────────────────────────────────────────────
        TimePicker(
            totalMs = delayVal,
            onChanged = { vm.astroDelayMs.value = it },
            label = "Start Delay",
            enabled = enabled,
        )
    }
}

private fun formatDuration(ms: Long): String {
    val totalS = ms / 1000.0
    return if (totalS >= 60) {
        val m = (totalS / 60).toInt()
        val s = (totalS % 60).toInt()
        "${m}m ${s}s"
    } else {
        "${"%.1f".format(totalS)}s"
    }
}

// ── Intervalometer preset data ───────────────────────────────────────────────

private data class Preset(
    val name: String,
    val intervalMs: Long,
    val exposureMs: Long,
    val shots: Int,
    val delayMs: Long,
)

private val PRESETS = listOf(
    Preset("Timelapse 1 s",   1_000L,    200L,   0, 5_000L),
    Preset("Timelapse 5 s",   5_000L,    500L,   0, 5_000L),
    Preset("Timelapse 30 s", 30_000L,  1_000L,   0, 5_000L),
    Preset("Star Trails",    30_000L, 25_000L,   0, 5_000L),
    Preset("Burst 10",        1_000L,    100L,  10,     0L),
    Preset("Burst 50",          500L,    100L,  50,     0L),
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PresetChips(
    onApply: (intervalMs: Long, exposureMs: Long, shots: Int, delayMs: Long) -> Unit,
) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        PRESETS.forEach { preset ->
            SuggestionChip(
                onClick = { onApply(preset.intervalMs, preset.exposureMs, preset.shots, preset.delayMs) },
                label = { Text(preset.name, style = MaterialTheme.typography.labelMedium) },
            )
        }
    }
}
