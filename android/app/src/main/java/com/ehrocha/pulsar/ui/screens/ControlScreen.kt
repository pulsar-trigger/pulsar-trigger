/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Copyright (C) 2026 Pulsar Trigger contributors
 */

package com.ehrocha.pulsar.ui.screens

import android.annotation.SuppressLint
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.text.font.FontWeight
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

    LaunchedEffect(pagerState.currentPage) {
        val pageMode = modes[pagerState.currentPage]
        if (pageMode == TriggerMode.PRESS_HOLD && mode == TriggerMode.PRESS_LOCK) return@LaunchedEffect
        if (pageMode != mode) vm.selectMode(pageMode)
    }
    LaunchedEffect(mode) {
        val effective = if (mode == TriggerMode.PRESS_LOCK) TriggerMode.PRESS_HOLD else mode
        val idx = modes.indexOf(effective)
        if (idx >= 0 && idx != pagerState.currentPage) pagerState.animateScrollToPage(idx)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        LiveStatusPanel(
            connected = connected,
            status = status,
            currentMode = mode,
        )

        Spacer(Modifier.height(16.dp))

        val isRunning = status?.state == DeviceState.RUNNING || status?.state == DeviceState.WAITING

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
                        TriggerMode.HDR -> HdrPanel(enabled = !isRunning)
                        TriggerMode.PRESS_HOLD -> ManualPanel(vm)
                        else -> {}
                    }
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        LaunchedEffect(isRunning, status?.shotsTaken) {
            if (isRunning) vm.updateNotification()
        }

        when (mode) {
            TriggerMode.PRESS_HOLD, TriggerMode.PRESS_LOCK -> ManualActions(vm, connected, mode)
            TriggerMode.ASTRO -> AstroActions(vm, connected, isRunning)
            else -> DefaultActions(vm, connected, isRunning)
        }

        Spacer(Modifier.height(8.dp))

        TextButton(
            onClick = { vm.disconnect() },
            enabled = !isRunning,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Disconnect") }
    }
}

@Composable
private fun DefaultActions(vm: PulsarViewModel, connected: Boolean, isRunning: Boolean) {
    DefaultActionsContent(
        connected = connected,
        isRunning = isRunning,
        onStart = { vm.start() },
        onStop = { vm.stop() },
        onSingleShot = { vm.singleShot() }
    )
}

@Composable
internal fun DefaultActionsContent(
    connected: Boolean,
    isRunning: Boolean,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onSingleShot: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                OutlinedButton(
                    onClick = onSingleShot,
                    enabled = connected && !isRunning,
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp),
                    modifier = Modifier.height(40.dp)
                ) {
                    Text("SINGLE", style = MaterialTheme.typography.labelLarge)
                }
            }

            Surface(
                shape = CircleShape,
                color = if (isRunning) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.primary,
                tonalElevation = if (isRunning) 8.dp else 2.dp,
                modifier = Modifier
                    .size(120.dp)
                    .clickable(enabled = connected) {
                        if (isRunning) onStop() else onStart()
                    },
            ) {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                    Text(
                        text = if (isRunning) "STOP" else "START",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimary,
                        textAlign = TextAlign.Center,
                    )
                }
            }

            Box(modifier = Modifier.weight(1f))
        }

        Spacer(Modifier.height(8.dp))
        Text(
            text = if (isRunning) "Sequence running…" else "Ready to start sequence",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ManualActions(vm: PulsarViewModel, connected: Boolean, mode: TriggerMode) {
    ManualActionsContent(
        connected = connected,
        mode = mode,
        onModeSelected = { vm.selectMode(it) },
        onShutterDown = { vm.shutterDown() },
        onShutterUp = { vm.shutterUp() }
    )
}

@Composable
internal fun ManualActionsContent(
    connected: Boolean,
    mode: TriggerMode,
    onModeSelected: (TriggerMode) -> Unit,
    onShutterDown: () -> Unit,
    onShutterUp: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isHold = mode == TriggerMode.PRESS_HOLD
    var active by remember { mutableStateOf(false) }

    LaunchedEffect(mode) { active = false }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                FilterChip(
                    selected = mode == TriggerMode.PRESS_HOLD,
                    onClick = { onModeSelected(TriggerMode.PRESS_HOLD) },
                    enabled = connected,
                    label = { Text("Hold") },
                )
            }

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
                                    onShutterDown()
                                    try { awaitRelease() } finally {
                                        active = false
                                        onShutterUp()
                                    }
                                },
                            )
                        } else {
                            detectTapGestures(
                                onPress = {
                                    if (!active) {
                                        active = true
                                        onShutterDown()
                                    } else {
                                        active = false
                                        onShutterUp()
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
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimary,
                        textAlign = TextAlign.Center,
                    )
                }
            }

            Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                FilterChip(
                    selected = mode == TriggerMode.PRESS_LOCK,
                    onClick = { onModeSelected(TriggerMode.PRESS_LOCK) },
                    enabled = connected,
                    label = { Text("Lock") },
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
    }
}

@Composable
private fun AstroActions(vm: PulsarViewModel, connected: Boolean, isRunning: Boolean) {
    val ruleDivisor by vm.astroRuleDivisor.collectAsState()
    AstroActionsContent(
        connected = connected,
        isRunning = isRunning,
        ruleDivisor = ruleDivisor,
        onRuleChanged = { vm.astroRuleDivisor.value = it },
        onStart = { vm.start() },
        onStop = { vm.stop() }
    )
}

@Composable
internal fun AstroActionsContent(
    connected: Boolean,
    isRunning: Boolean,
    ruleDivisor: Int,
    onRuleChanged: (Int) -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                FilterChip(
                    selected = ruleDivisor == 500,
                    onClick = { onRuleChanged(500) },
                    enabled = connected && !isRunning,
                    label = { Text("500 Rule", style = MaterialTheme.typography.labelMedium) },
                )
            }

            Surface(
                shape = CircleShape,
                color = if (isRunning) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.primary,
                tonalElevation = if (isRunning) 8.dp else 2.dp,
                modifier = Modifier
                    .size(120.dp)
                    .clickable(enabled = connected) {
                        if (isRunning) onStop() else onStart()
                    },
            ) {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                    Text(
                        text = if (isRunning) "STOP" else "START",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimary,
                        textAlign = TextAlign.Center,
                    )
                }
            }

            Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                FilterChip(
                    selected = ruleDivisor == 400,
                    onClick = { onRuleChanged(400) },
                    enabled = connected && !isRunning,
                    label = { Text("400 Rule", style = MaterialTheme.typography.labelMedium) },
                )
            }
        }

        Spacer(Modifier.height(8.dp))
        Text(
            text = if (isRunning) "Capturing stars…" else "Ready for astro sequence",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
internal fun IntervalometerPanel(vm: PulsarViewModel, enabled: Boolean = true) {
    val interval by vm.intervalMs.collectAsState()
    val exposure by vm.exposureMs.collectAsState()
    val count by vm.shotCount.collectAsState()
    val delayVal by vm.delayMs.collectAsState()

    IntervalometerPanelContent(
        intervalMs = interval,
        exposureMs = exposure,
        shotCount = count,
        delayMs = delayVal,
        onIntervalChanged = { vm.intervalMs.value = it.coerceAtLeast(500) },
        onExposureChanged = { vm.exposureMs.value = it.coerceAtLeast(50) },
        onShotCountChanged = { vm.shotCount.value = it.coerceAtLeast(1) },
        onDelayChanged = { vm.delayMs.value = it },
        enabled = enabled
    )
}

@Composable
internal fun IntervalometerPanelContent(
    modifier: Modifier = Modifier,
    intervalMs: Long,
    exposureMs: Long,
    shotCount: Int,
    delayMs: Long,
    onIntervalChanged: (Long) -> Unit,
    onExposureChanged: (Long) -> Unit,
    onShotCountChanged: (Int) -> Unit,
    onDelayChanged: (Long) -> Unit,
    enabled: Boolean = true,
) {
    val totalSequenceTimeMs = delayMs + (intervalMs * shotCount)

    Column(verticalArrangement = Arrangement.spacedBy(24.dp), modifier = modifier) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Intervalometer", style = MaterialTheme.typography.titleLarge)
            Text(
                "Automate complex timelapse sequences or burst captures with precision timing.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Surface(
                shape = RoundedCornerShape(12.dp),
                tonalElevation = 2.dp,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    "Configure individual timings below. For bulb mode, ensure the interval is longer than the exposure.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(12.dp),
                )
            }
        }

        Surface(
            shape = RoundedCornerShape(16.dp),
            tonalElevation = 4.dp,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(
                    "SEQUENCE ESTIMATE",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                )
                
                Row(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("SHOTS", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("$shotCount", style = MaterialTheme.typography.headlineLarge, color = MaterialTheme.colorScheme.primary)
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text("TOTAL DURATION", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(formatDuration(totalSequenceTimeMs), style = MaterialTheme.typography.headlineLarge)
                    }
                }
                
                LinearProgressIndicator(
                    progress = { (exposureMs.toFloat() / intervalMs).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth().height(4.dp),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant,
                    strokeCap = androidx.compose.ui.graphics.StrokeCap.Round,
                )
                
                Text(
                    "Duty Cycle: ${(exposureMs.toFloat() / intervalMs * 100).toInt()}% active exposure",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.alpha(0.7f)
                )
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("TIMING PARAMETERS", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            
            TimePicker(
                totalMs = intervalMs,
                onChanged = { onIntervalChanged(it) },
                label = "Interval (time between shots)",
                enabled = enabled,
            )

            TimePicker(
                totalMs = exposureMs,
                onChanged = { onExposureChanged(it) },
                label = "Exposure duration",
                enabled = enabled,
            )

            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    "Number of Shots",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                ScrollPicker(
                    value = shotCount,
                    range = 1..999,
                    onValueChange = { onShotCountChanged(it) },
                    format = { "$it" },
                    enabled = enabled,
                )
            }

            TimePicker(
                totalMs = delayMs,
                onChanged = { onDelayChanged(it) },
                label = "Start Delay",
                enabled = enabled,
            )
        }
    }
}

@Composable
internal fun SoundPanel(vm: PulsarViewModel, enabled: Boolean = true) {
    val threshold by vm.soundThreshold.collectAsState()
    val exposure by vm.soundExposureMs.collectAsState()

    Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Text("Sound Trigger", style = MaterialTheme.typography.titleLarge)

        Text(
            "Triggers the shutter when a loud sound is detected.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Surface(
            shape = RoundedCornerShape(12.dp),
            tonalElevation = 2.dp,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                "Adjust the threshold based on ambient noise. Higher values require louder sounds to trigger.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(12.dp),
            )
        }

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
internal fun LightningPanel(vm: PulsarViewModel, enabled: Boolean = true) {
    val sensitivity by vm.lightningSensitivity.collectAsState()
    val exposure by vm.lightningExposureMs.collectAsState()

    Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Text("Lightning", style = MaterialTheme.typography.titleLarge)

        Text(
            "Detects sudden brightness changes to capture lightning.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Surface(
            shape = RoundedCornerShape(12.dp),
            tonalElevation = 2.dp,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                "Sensitivity 5 is most sensitive. Use lower values if triggers are too frequent from cloud flicker.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(12.dp),
            )
        }

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
internal fun LaserPanel(vm: PulsarViewModel, enabled: Boolean = true) {
    val exposure by vm.laserExposureMs.collectAsState()

    Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Text("Laser Trigger", style = MaterialTheme.typography.titleLarge)

        Text(
            "Fires the shutter when a laser beam is broken.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Surface(
            shape = RoundedCornerShape(12.dp),
            tonalElevation = 2.dp,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                "Align your laser with the built-in sensor. The shutter fires instantly upon beam interruption.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(12.dp),
            )
        }

        TimePicker(
            totalMs = exposure,
            onChanged = { vm.laserExposureMs.value = it.coerceAtLeast(50) },
            label = "Exposure",
            enabled = enabled,
        )
    }
}

@Composable
internal fun HdrPanel(enabled: Boolean = true) {
    Column(
        verticalArrangement = Arrangement.spacedBy(20.dp),
        modifier = Modifier.alpha(if (enabled) 1f else 0.5f)
    ) {
        Text("HDR Bracketing", style = MaterialTheme.typography.titleLarge)

        Text(
            "Automatic 5-bracket HDR sequence.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Surface(
            shape = RoundedCornerShape(12.dp),
            tonalElevation = 2.dp,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                "Fires five consecutive shots with increasing exposure times to capture high dynamic range.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(12.dp),
            )
        }

        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
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
}

@Composable
internal fun ManualPanel(vm: PulsarViewModel) {
    val mode by vm.currentMode.collectAsState()
    ManualPanelContent(mode = mode)
}

@Composable
internal fun ManualPanelContent(
    modifier: Modifier = Modifier,
    mode: TriggerMode,
) {
    val isLock = mode == TriggerMode.PRESS_LOCK
    Column(
        verticalArrangement = Arrangement.spacedBy(20.dp),
        modifier = modifier
    ) {
        Text("Manual Shutter", style = MaterialTheme.typography.titleLarge)

        Text(
            "Control the shutter directly. Choose Hold (press and hold) or Lock (toggle on/off).",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Surface(
            shape = RoundedCornerShape(12.dp),
            tonalElevation = 2.dp,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Toggle behaviour using the chips in the action area below.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = if (isLock)
                        "LOCK: Tap the center button once to open the shutter, tap again to close. Best for long bulb exposures."
                    else
                        "HOLD: Shutter remains open as long as the center button is pressed. Ideal for quick bursts.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
internal fun AstroPanel(vm: PulsarViewModel, enabled: Boolean = true) {
    val focalLength by vm.astroFocalLength.collectAsState()
    val cropFactor by vm.astroCropFactor.collectAsState()
    val shotCount by vm.astroShotCount.collectAsState()
    val delayVal by vm.astroDelayMs.collectAsState()
    val gapMs by vm.astroGapMs.collectAsState()
    val ruleDivisor by vm.astroRuleDivisor.collectAsState()

    AstroPanelContent(
        focalLength = focalLength,
        cropFactor = cropFactor,
        shotCount = shotCount,
        delayMs = delayVal,
        gapMs = gapMs,
        ruleDivisor = ruleDivisor,
        onCropFactorChanged = { vm.astroCropFactor.value = it },
        onFocalLengthChanged = { vm.astroFocalLength.value = it },
        onGapMsChanged = { vm.astroGapMs.value = it.coerceAtLeast(500) },
        onShotCountChanged = { vm.astroShotCount.value = it.coerceAtLeast(1) },
        onDelayMsChanged = { vm.astroDelayMs.value = it },
        enabled = enabled
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun AstroPanelContent(
    modifier: Modifier = Modifier,
    focalLength: Int,
    cropFactor: Float,
    shotCount: Int,
    delayMs: Long,
    gapMs: Long,
    ruleDivisor: Int,
    onCropFactorChanged: (Float) -> Unit,
    onFocalLengthChanged: (Int) -> Unit,
    onGapMsChanged: (Long) -> Unit,
    onShotCountChanged: (Int) -> Unit,
    onDelayMsChanged: (Long) -> Unit,
    enabled: Boolean = true,
) {
    val maxExposureS = ruleDivisor.toDouble() / (focalLength * cropFactor)
    val maxExposureMs = (maxExposureS * 1000).toLong().coerceAtLeast(100)
    val intervalMs = maxExposureMs + gapMs
    val totalTimeMs = delayMs + (intervalMs * shotCount)

    Column(verticalArrangement = Arrangement.spacedBy(24.dp), modifier = modifier) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Astro Mode", style = MaterialTheme.typography.titleLarge)
            Text(
                "Pinpoint stars by calculating the maximum shutter speed to prevent trails.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Surface(
                shape = RoundedCornerShape(12.dp),
                tonalElevation = 2.dp,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    "Switch rules at the bottom. Rule 400 is ideal for high-resolution sensors.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(12.dp),
                )
            }
        }

        Surface(
            shape = RoundedCornerShape(16.dp),
            tonalElevation = 4.dp,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "$ruleDivisor RULE READOUT",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.weight(1f)
                    )
                    Surface(
                        color = MaterialTheme.colorScheme.primaryContainer,
                        shape = RoundedCornerShape(4.dp)
                    ) {
                        Text(
                            "${"%.1f".format(focalLength * cropFactor)}mm Effective", 
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }
                
                Row(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("MAX EXPOSURE", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(formatDuration(maxExposureMs), style = MaterialTheme.typography.headlineLarge, color = MaterialTheme.colorScheme.primary)
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text("TOTAL TIME", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(formatDuration(totalTimeMs), style = MaterialTheme.typography.headlineLarge)
                    }
                }
                
                LinearProgressIndicator(
                    progress = { (maxExposureMs.toFloat() / intervalMs).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth().height(4.dp),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant,
                    strokeCap = androidx.compose.ui.graphics.StrokeCap.Round,
                )

                Text(
                    "Formula: $ruleDivisor / ($focalLength mm * ${cropFactor}x) = ${"%.1f".format(maxExposureS)}s",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().alpha(0.6f)
                )
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("OPTICS CONFIGURATION", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Sensor Preset", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    SENSOR_PRESETS.forEach { preset ->
                        FilterChip(
                            selected = cropFactor == preset.crop,
                            onClick = { if (enabled) onCropFactorChanged(preset.crop) },
                            enabled = enabled,
                            label = { Text(preset.label, style = MaterialTheme.typography.labelMedium) },
                        )
                    }
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Lens Focal Length",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = "$focalLength mm",
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold
                    )
                }
                
                Slider(
                    value = focalLength.toFloat(),
                    onValueChange = { onFocalLengthChanged(it.toInt()) },
                    valueRange = 8f..600f,
                    enabled = enabled,
                )

                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    listOf(14, 24, 35, 50, 85, 135, 200, 400, 600).forEach { mm ->
                        SuggestionChip(
                            onClick = { if (enabled) onFocalLengthChanged(mm) },
                            label = { Text("${mm}mm", style = MaterialTheme.typography.labelSmall) },
                            enabled = enabled,
                        )
                    }
                }
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("CAPTURE SEQUENCE", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            
            TimePicker(
                totalMs = gapMs,
                onChanged = { onGapMsChanged(it) },
                label = "Gap between shots",
                enabled = enabled,
            )

            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    "Number of Shots",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                ScrollPicker(
                    value = shotCount,
                    range = 1..999,
                    onValueChange = { onShotCountChanged(it) },
                    format = { "$it" },
                    enabled = enabled,
                )
            }

            TimePicker(
                totalMs = delayMs,
                onChanged = { onDelayMsChanged(it) },
                label = "Start Delay",
                enabled = enabled,
            )
        }
    }
}

private data class SensorPreset(val label: String, val crop: Float)

private val SENSOR_PRESETS = listOf(
    SensorPreset("Full Frame", 1.0f),
    SensorPreset("APS-C (Canon)", 1.6f),
    SensorPreset("APS-C (Nikon/Sony)", 1.5f),
    SensorPreset("Micro 4/3", 2.0f),
)

internal fun formatDuration(ms: Long): String {
    val totalS = ms / 1000.0
    return if (totalS >= 60) {
        val m = (totalS / 60).toInt()
        val s = (totalS % 60).toInt()
        if (m >= 60) {
            val h = m / 60
            val rm = m % 60
            "${h}h ${rm}m"
        } else {
            "${m}m ${s}s"
        }
    } else {
        "${"%.1f".format(totalS)}s"
    }
}
