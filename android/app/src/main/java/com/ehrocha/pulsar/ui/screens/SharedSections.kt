/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Copyright (C) 2026 Pulsar Trigger contributors
 */

package com.ehrocha.pulsar.ui.screens

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.History
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.*
import kotlinx.coroutines.launch
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ehrocha.pulsar.ble.TriggerMode
import com.ehrocha.pulsar.ble.OtaState
import com.ehrocha.pulsar.update.AppUpdateState
import com.ehrocha.pulsar.BuildConfig
import com.ehrocha.pulsar.R
import com.ehrocha.pulsar.ui.theme.LocalVisualStyle
import com.ehrocha.pulsar.ui.theme.VisualStyle
import androidx.annotation.StringRes
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.DeveloperBoard
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.BatteryFull
import androidx.compose.material.icons.filled.SaveAlt
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.ui.graphics.vector.ImageVector
import com.ehrocha.pulsar.ui.components.IntScrubField
import com.ehrocha.pulsar.ui.components.ScrubField
import com.ehrocha.pulsar.AppConfig
import com.ehrocha.pulsar.viewmodel.PulsarViewModel
import com.ehrocha.pulsar.ui.theme.LocalDeviceConnected
import com.ehrocha.pulsar.ui.theme.LocalDeviceStatus

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PanelHelpHeader(title: String, helpText: String) {
    val tooltipState = rememberTooltipState(isPersistent = true)
    val scope = rememberCoroutineScope()

    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        TooltipBox(
            positionProvider = TooltipDefaults.rememberRichTooltipPositionProvider(),
            tooltip = {
                RichTooltip(
                    title = { Text(title) },
                    action = {
                        TextButton(onClick = { scope.launch { tooltipState.dismiss() } }) {
                            Text(stringResource(R.string.action_dismiss))
                        }
                    },
                ) {
                    Text(helpText)
                }
            },
            state = tooltipState,
        ) {
            IconButton(onClick = { scope.launch { tooltipState.show() } }) {
                Icon(
                    Icons.Outlined.Info,
                    contentDescription = stringResource(R.string.cd_help),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}



/** Press-and-hold to fire. ~600 ms hold required. Releasing early cancels
 *  with no action; reaching the threshold fires once and resets. A radial
 *  progress sweep fills the button so the user can see how long they've held.
 *  [idleLabelRes] is the resting label; [holdLabelRes] shows once the user
 *  starts holding. Defaults are the generic Start / "Hold to start" pair. */

@Composable
internal fun ManualActions(vm: PulsarViewModel, mode: TriggerMode) {
    val connected = LocalDeviceConnected.current
    ManualActionsContent(
        connected = connected,
        mode = mode,
        onModeSelected = { vm.selectMode(it) },
        onShutterDown = { vm.shutterDown() },
        onShutterUp = { vm.shutterUp() },
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
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            SegmentedButton(
                selected = mode == TriggerMode.PRESS_HOLD,
                onClick = { onModeSelected(TriggerMode.PRESS_HOLD) },
                enabled = connected,
                shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
            ) {
                Text(stringResource(R.string.chip_hold_mode))
            }
            SegmentedButton(
                selected = mode == TriggerMode.PRESS_LOCK,
                onClick = { onModeSelected(TriggerMode.PRESS_LOCK) },
                enabled = connected,
                shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
            ) {
                Text(stringResource(R.string.chip_lock_mode))
            }
        }

        // StartStopBar standard (Eduardo's #3): armed = live gradient,
        // firing = error red — same grammar as every wizard's Start/Stop.
        val armedBrush = androidx.compose.ui.graphics.Brush.horizontalGradient(
            listOf(
                com.ehrocha.pulsar.ui.theme.PulsarTheme.colors.liveStart,
                com.ehrocha.pulsar.ui.theme.PulsarTheme.colors.liveEnd,
            ),
        )
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = when {
                active -> MaterialTheme.colorScheme.error
                else -> androidx.compose.ui.graphics.Color.Transparent
            },
            tonalElevation = if (active) 8.dp else 0.dp,
            modifier = Modifier
                .fillMaxWidth()
                .height(64.dp)
                .then(
                    if (!active) Modifier.background(
                        armedBrush, RoundedCornerShape(20.dp),
                    ) else Modifier,
                )
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
            Box(contentAlignment = Alignment.Center) {
                Text(
                    text = if (isHold) {
                        if (active) stringResource(R.string.btn_release_shutter) else stringResource(R.string.btn_hold_shutter)
                    } else {
                        if (active) stringResource(R.string.btn_close_shutter) else stringResource(R.string.btn_open_shutter)
                    },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    // White on both the gradient and the error red.
                    color = androidx.compose.ui.graphics.Color.White,
                    textAlign = TextAlign.Center,
                )
            }
        }

        Text(
            text = if (isHold) {
                if (active) stringResource(R.string.status_shutter_open) else stringResource(R.string.status_press_hold)
            } else {
                if (active) stringResource(R.string.status_shutter_locked) else stringResource(R.string.status_tap_toggle)
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}



/** Glanceable two-column summary for mode panels.
 *  When [warning] is non-null, a single-line strip below the totals renders
 *  in `error` colour — used for conflict states like a too-short interval. */
@Composable
private fun HeroSummary(
    primaryLabel: String,
    primaryValue: String,
    secondaryLabel: String,
    secondaryValue: String,
    warning: String? = null,
    totalDurationMs: Long? = null,
) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        primaryLabel,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        primaryValue,
                        style = MaterialTheme.typography.displaySmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                VerticalDivider(
                    modifier = Modifier.height(56.dp).padding(horizontal = 12.dp),
                    color = MaterialTheme.colorScheme.outlineVariant,
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        secondaryLabel,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        secondaryValue,
                        style = MaterialTheme.typography.displaySmall,
                        fontWeight = FontWeight.Bold,
                        color = if (warning != null) MaterialTheme.colorScheme.error
                                else MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
            if (totalDurationMs != null && totalDurationMs > 0) {
                Spacer(Modifier.height(8.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.End,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        stringResource(R.string.label_ends_at, formatEndClock(totalDurationMs)),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (warning != null) {
                Spacer(Modifier.height(8.dp))
                Text(
                    warning,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

private fun formatEndClock(durationFromNowMs: Long): String {
    val end = java.util.Calendar.getInstance().apply {
        timeInMillis = System.currentTimeMillis() + durationFromNowMs
    }
    val h = end.get(java.util.Calendar.HOUR_OF_DAY)
    val m = end.get(java.util.Calendar.MINUTE)
    return String.format(java.util.Locale.US, "%02d:%02d", h, m)
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
        Surface(
            shape = RoundedCornerShape(12.dp),
            tonalElevation = 2.dp,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    stringResource(R.string.mode_manual_dial_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    stringResource(R.string.panel_manual_tip),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = if (isLock)
                        stringResource(R.string.panel_manual_lock_info)
                    else
                        stringResource(R.string.panel_manual_hold_info),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

internal fun formatDuration(ms: Long): String {
    val totalS = (ms + 500) / 1000
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
        "${totalS}s"
    }
}

// ── Settings section enum & menu ─────────────────────────────────────────────

enum class SettingsSection(val icon: ImageVector, @StringRes val titleRes: Int) {
    USER_GUIDE(Icons.AutoMirrored.Filled.MenuBook, R.string.section_user_guide),
    LANGUAGE(Icons.Default.Language, R.string.section_language),
    DEVICE(Icons.Default.PhoneAndroid, R.string.section_device),
    PLANNER(Icons.Default.CalendarMonth, R.string.section_planner),
    BACKGROUND(Icons.Default.BatteryFull, R.string.section_background),
    BACKUP_RESTORE(Icons.Default.SaveAlt, R.string.section_backup_restore),
    UPDATES(Icons.Default.SystemUpdate, R.string.section_updates),
    DIAGNOSTICS(Icons.Default.Science, R.string.section_diagnostics),
    // DEVICES moved to the Scan landing (pre-connect only) so the user
    // can't forget the body they're currently driving. See
    // ManageDevicesScreen + AppScreen.ManageDevices.
    ABOUT(Icons.Outlined.Info, R.string.section_about),
}

/** Visual-style switch (Circuit / Classic) at the top of Settings. Reads +
 *  writes the persisted [LocalVisualStyle] — the whole app re-skins live. */
@Composable
private fun VisualStylePicker() {
    val style = LocalVisualStyle.current
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(stringResource(R.string.settings_visual_style), style = MaterialTheme.typography.bodyLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                VisualStyle.entries.forEach { vs ->
                    val selected = style.value == vs
                    Surface(
                        onClick = { style.value = vs },
                        shape = RoundedCornerShape(10.dp),
                        color = if (selected) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.surfaceContainerHighest,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(
                            stringResource(
                                when (vs) {
                                    VisualStyle.CIRCUIT -> R.string.visual_style_circuit
                                    VisualStyle.CLASSIC -> R.string.visual_style_classic
                                    VisualStyle.SPACE -> R.string.visual_style_space
                                }
                            ),
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold,
                            textAlign = TextAlign.Center,
                            color = if (selected) MaterialTheme.colorScheme.onPrimary
                            else MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun SettingsMenu(
    onSectionSelected: (SettingsSection) -> Unit,
    showEspSections: Boolean = true,
) {
    val sections = SettingsSection.entries.filter { section ->
        when (section) {
            // Device section is entirely firmware-specific (rename, auto-off,
            // GPIO pins). Hide it when the active transport isn't the Pulsar
            // ESP32 path — Canon CCAPI / PTP / direct BLE don't have anything
            // to configure here.
            SettingsSection.DEVICE -> showEspSections
            else -> true
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        VisualStylePicker()
        sections.forEach { section ->
            Surface(
                onClick = { onSectionSelected(section) },
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(16.dp),
                ) {
                    Icon(
                        section.icon,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(24.dp),
                    )
                    Spacer(Modifier.width(16.dp))
                    Text(
                        stringResource(section.titleRes),
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.weight(1f),
                    )
                    Icon(
                        Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

// ── Individual settings section content ──────────────────────────────────────

@Composable
internal fun LanguageSectionContent() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val currentLocale = androidx.appcompat.app.AppCompatDelegate.getApplicationLocales()
    val currentTag = if (currentLocale.isEmpty) "" else currentLocale.toLanguageTags()

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // System default option
        val isSystemDefault = currentTag.isEmpty()
        Surface(
            onClick = {
                androidx.appcompat.app.AppCompatDelegate.setApplicationLocales(
                    androidx.core.os.LocaleListCompat.getEmptyLocaleList()
                )
            },
            shape = RoundedCornerShape(12.dp),
            tonalElevation = if (isSystemDefault) 4.dp else 1.dp,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(16.dp),
            ) {
                RadioButton(selected = isSystemDefault, onClick = null)
                Spacer(Modifier.width(12.dp))
                Text(
                    stringResource(R.string.lang_system_default),
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
        }

        // Each supported language
        com.ehrocha.pulsar.AppConfig.SUPPORTED_LOCALES.forEach { (tag, label) ->
            val selected = currentTag.startsWith(tag)
            Surface(
                onClick = {
                    androidx.appcompat.app.AppCompatDelegate.setApplicationLocales(
                        androidx.core.os.LocaleListCompat.forLanguageTags(tag)
                    )
                },
                shape = RoundedCornerShape(12.dp),
                tonalElevation = if (selected) 4.dp else 1.dp,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(16.dp),
                ) {
                    RadioButton(selected = selected, onClick = null)
                    Spacer(Modifier.width(12.dp))
                    Text(label, style = MaterialTheme.typography.bodyLarge)
                    if (tag != "en") {
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "(${java.util.Locale(tag).getDisplayLanguage(java.util.Locale.ENGLISH)})",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(8.dp))
        HorizontalDivider()
        Spacer(Modifier.height(8.dp))

        // Panic button — always in English for discoverability
        OutlinedButton(
            onClick = {
                androidx.appcompat.app.AppCompatDelegate.setApplicationLocales(
                    androidx.core.os.LocaleListCompat.forLanguageTags("en")
                )
            },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.outlinedButtonColors(
                contentColor = MaterialTheme.colorScheme.error,
            ),
        ) {
            Text("Reset to English", fontWeight = FontWeight.Bold)
        }
        Text(
            "If the app is in a language you can't read, tap the button above.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
internal fun DeviceSectionContent(
    vm: PulsarViewModel,
    deviceName: String,
) {
    val connected = LocalDeviceConnected.current
    var showRenameDialog by remember { mutableStateOf(false) }
    val simulatorActive by vm.simulatorActive.collectAsState()
    val hwConnected = connected && !simulatorActive

    val autoOff by vm.autoOffMinutes.collectAsState()
    val autoOffOptions = listOf(0, 5, 15, 30, 60, 120)

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .clickable(enabled = hwConnected) { showRenameDialog = true },
        ) {
            Icon(
                Icons.Default.Edit,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(24.dp),
            )
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.label_device_name), style = MaterialTheme.typography.titleSmall)
                Text(
                    deviceName,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // Auto-shutdown selector
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(
                Icons.Default.PowerSettingsNew,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(24.dp),
            )
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.label_auto_off), style = MaterialTheme.typography.titleSmall)
                @OptIn(ExperimentalLayoutApi::class)
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    autoOffOptions.forEach { minutes ->
                        val label = if (minutes == 0) stringResource(R.string.auto_off_disabled)
                                    else stringResource(R.string.auto_off_minutes, minutes)
                        FilterChip(
                            selected = autoOff == minutes,
                            onClick = { vm.setAutoOff(minutes) },
                            label = { Text(label, style = MaterialTheme.typography.labelSmall) },
                        )
                    }
                }
            }
        }
    }

    if (showRenameDialog) {
        RenameDeviceDialog(
            onDismiss = { showRenameDialog = false },
            onConfirm = { suffix ->
                vm.renameDevice(suffix)
                showRenameDialog = false
            },
        )
    }
}

@Composable
internal fun GpioPinsSectionContent(vm: PulsarViewModel) {
    val connected = LocalDeviceConnected.current
    val simulatorActive by vm.simulatorActive.collectAsState()
    val hwConnected = connected && !simulatorActive
    val shutterPin by vm.pinShutter.collectAsState()
    val focusPin by vm.pinFocus.collectAsState()
    val safePins by vm.safeOutputPins.collectAsState()

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        PanelHelpHeader(
            title = stringResource(R.string.section_gpio_pins),
            helpText = stringResource(R.string.gpio_pins_help),
        )

        GpioPinSelector(
            label = stringResource(R.string.label_shutter_pin),
            selectedPin = shutterPin,
            disabledPin = focusPin,
            onPinSelected = { vm.savePins(it, focusPin) },
            enabled = hwConnected,
            pins = safePins,
        )

        GpioPinSelector(
            label = stringResource(R.string.label_focus_pin),
            selectedPin = focusPin,
            disabledPin = shutterPin,
            onPinSelected = { vm.savePins(shutterPin, it) },
            enabled = hwConnected,
            pins = safePins,
        )

        if (simulatorActive) {
            Text(
                stringResource(R.string.gpio_simulator_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
internal fun BackgroundSectionContent(vm: PulsarViewModel) {
    val context = LocalContext.current
    // Re-checked on every recomposition; the user can flip the OS setting
    // while the screen is open and we want to reflect it.
    var isExempt by remember { mutableStateOf(vm.isIgnoringBatteryOptimizations()) }
    LaunchedEffect(Unit) {
        // Refresh once on screen enter (covers the case where the user
        // returned from the system Settings activity we launched).
        isExempt = vm.isIgnoringBatteryOptimizations()
    }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        PanelHelpHeader(
            title = stringResource(R.string.section_background),
            helpText = stringResource(R.string.background_help),
        )
        Surface(
            color = if (isExempt) MaterialTheme.colorScheme.primaryContainer
                    else MaterialTheme.colorScheme.surfaceContainer,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                modifier = Modifier.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Default.BatteryFull,
                    contentDescription = null,
                    tint = if (isExempt) MaterialTheme.colorScheme.primary
                           else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        stringResource(
                            if (isExempt) R.string.background_status_allowed
                            else R.string.background_status_blocked
                        ),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        stringResource(R.string.background_status_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        if (!isExempt) {
            Button(
                onClick = {
                    runCatching { context.startActivity(vm.batteryOptimisationRequestIntent()) }
                },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
            ) {
                Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.background_request_btn))
            }
        }

        // ── Widget appearance ──────────────────────────────────────
        Spacer(Modifier.height(8.dp))
        HorizontalDivider()
        Spacer(Modifier.height(4.dp))
        WidgetOpacitySlider()
    }
}

@Composable
private fun WidgetOpacitySlider() {
    val context = LocalContext.current
    var alpha by remember {
        mutableStateOf(com.ehrocha.pulsar.widget.DashboardSnapshotStore.backgroundAlpha(context))
    }
    val scope = rememberCoroutineScope()
    Text(
        stringResource(R.string.widget_appearance_title),
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
    )
    Text(
        stringResource(R.string.widget_bg_opacity_label, (alpha * 100).toInt()),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Slider(
        value = alpha,
        onValueChange = { alpha = it },
        onValueChangeFinished = {
            com.ehrocha.pulsar.widget.DashboardSnapshotStore.setBackgroundAlpha(context, alpha)
            // Refresh the widget host so the new alpha lands immediately.
            scope.launch {
                runCatching {
                    androidx.glance.appwidget.GlanceAppWidgetManager(context)
                        .getGlanceIds(com.ehrocha.pulsar.widget.DashboardWidget::class.java)
                        .forEach { id ->
                            com.ehrocha.pulsar.widget.DashboardWidget().update(context, id)
                        }
                }
            }
        },
        valueRange = 0f..1f,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
internal fun BackupRestoreSectionContent(vm: PulsarViewModel) {
    val context = LocalContext.current
    val notify = com.ehrocha.pulsar.ui.components.rememberSnackbarPoster()

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        if (uri != null) {
            try {
                context.contentResolver.openOutputStream(uri)?.use { stream ->
                    stream.write(vm.exportSettingsJson().toByteArray())
                }
                notify(context.getString(R.string.toast_settings_exported))
            } catch (e: Exception) {
                notify(context.getString(R.string.toast_export_failed, e.message))
            }
        }
    }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            try {
                context.contentResolver.openInputStream(uri)?.use { stream ->
                    val json = stream.bufferedReader().readText()
                    vm.importSettingsJson(json)
                }
                notify(context.getString(R.string.toast_settings_imported))
            } catch (e: Exception) {
                notify(context.getString(R.string.toast_import_failed, e.message))
            }
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        PanelHelpHeader(
            title = stringResource(R.string.section_backup_restore),
            helpText = stringResource(R.string.backup_restore_help),
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            OutlinedButton(
                onClick = { exportLauncher.launch("pulsar-settings.json") },
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(12.dp),
            ) {
                Icon(Icons.Default.CloudUpload, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.export_label))
            }
            OutlinedButton(
                onClick = { importLauncher.launch(arrayOf("application/json")) },
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(12.dp),
            ) {
                Icon(Icons.Default.CloudDownload, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.import_label))
            }
        }
    }
}

@Composable
internal fun UpdatesSectionContent(vm: PulsarViewModel, showFirmware: Boolean = true) {
    UpdatesSection(vm = vm, showFirmware = showFirmware)
}

/**
 * Lists every device Pulsar has stored state for — BLE bonds, Canon
 * CCAPI credentials, the last-connection hint — with a per-row Forget
 * button that wipes all matching state in one tap. See
 * [PulsarViewModel.managedDevices] / [PulsarViewModel.forgetDevice].
 */
@Composable
internal fun DevicesSectionContent(vm: PulsarViewModel) {
    // Recompute the device list whenever nicknames change (covers forget
    // → list shrinks; rename → label updates) and when a force-refresh
    // tick is bumped by the row's Forget action below.
    val nicks by vm.canonCcapiNicknames.collectAsState()
    var refreshTick by remember { mutableIntStateOf(0) }
    val devices = remember(nicks, refreshTick) { vm.managedDevices() }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        PanelHelpHeader(
            title = stringResource(R.string.section_devices),
            helpText = stringResource(R.string.devices_help),
        )
        if (devices.isEmpty()) {
            Text(
                stringResource(R.string.devices_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            devices.forEach { d ->
                DeviceRow(vm, d, onForgotten = { refreshTick += 1 })
            }
        }
    }
}

@Composable
private fun DeviceRow(
    vm: PulsarViewModel,
    device: com.ehrocha.pulsar.model.ManagedDevice,
    onForgotten: () -> Unit,
) {
    var pendingForget by remember { mutableStateOf(false) }
    val transportLabel = when (device.kind) {
        com.ehrocha.pulsar.model.DeviceKind.PULSAR_BLE -> stringResource(R.string.devices_kind_pulsar_ble)
        com.ehrocha.pulsar.model.DeviceKind.CANON_BLE -> stringResource(R.string.devices_kind_canon_ble)
        com.ehrocha.pulsar.model.DeviceKind.CANON_CCAPI -> stringResource(R.string.devices_kind_canon_ccapi)
    }
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    device.displayName,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    transportLabel,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    device.id,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                )
            }
            TextButton(onClick = { pendingForget = true }) {
                Text(stringResource(R.string.devices_forget))
            }
        }
    }
    if (pendingForget) {
        AlertDialog(
            onDismissRequest = { pendingForget = false },
            title = { Text(stringResource(R.string.devices_forget_confirm_title)) },
            text = {
                Text(
                    stringResource(
                        R.string.devices_forget_confirm_body,
                        device.displayName,
                    )
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.forgetDevice(device)
                    pendingForget = false
                    onForgotten()
                }) { Text(stringResource(R.string.devices_forget)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingForget = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}

/**
 * Drill-in entries for diagnostics screens that were previously surfaced as
 * Tools-tab tiles. The actual screens (TestCamera, Diagnostics log) live
 * elsewhere and are reached via the passed-in nav callbacks.
 */
@Composable
internal fun DiagnosticsSectionContent(
    onTestCameraClick: () -> Unit,
    onDiagnosticsClick: () -> Unit,
    debugMode: Boolean = false,
    onGattExplorerClick: () -> Unit = {},
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        DiagnosticsRow(
            icon = Icons.Default.Science,
            title = stringResource(R.string.mode_test_camera),
            subtitle = stringResource(R.string.section_diagnostics_test_camera_sub),
            onClick = onTestCameraClick,
        )
        DiagnosticsRow(
            icon = Icons.Default.Description,
            title = stringResource(R.string.mode_diagnostics),
            subtitle = stringResource(R.string.section_diagnostics_logs_sub),
            onClick = onDiagnosticsClick,
        )
        // GATT Explorer drill-in — debug-mode only. See
        // docs/gatt-explorer-draft.md.
        if (debugMode) {
            DiagnosticsRow(
                icon = Icons.Default.BugReport,
                title = stringResource(R.string.section_gatt_explorer),
                subtitle = stringResource(R.string.section_gatt_explorer_sub),
                onClick = onGattExplorerClick,
            )
        }
    }
}

@Composable
private fun DiagnosticsRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 16.dp),
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(24.dp),
            )
            Spacer(Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
internal fun DeviceInfoSectionContent(vm: PulsarViewModel) {
    val connected = LocalDeviceConnected.current
    val info by vm.deviceInfo.collectAsState()
    val simulatorActive by vm.simulatorActive.collectAsState()

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (simulatorActive) {
            Text(
                stringResource(R.string.hw_simulator_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else if (info != null) {
            val i = info!!
            InfoRow(stringResource(R.string.hw_chip), stringResource(R.string.hw_chip_value, i.chipModel, i.chipRevision))
            InfoRow(stringResource(R.string.hw_cpu), stringResource(R.string.hw_cpu_value, i.cpuFreqMhz))
            InfoRow(stringResource(R.string.hw_flash), formatKb(i.flashSizeKb))
            InfoRow(stringResource(R.string.hw_free_heap), formatKb(i.freeHeapKb))
            if (i.psramKb > 0) {
                InfoRow(stringResource(R.string.hw_psram), formatKb(i.psramKb.toLong()))
            }
            InfoRow(stringResource(R.string.hw_gpio), stringResource(R.string.hw_gpio_value, i.gpioCount, i.safeOutputCount))
            InfoRow(stringResource(R.string.hw_uptime), formatUptime(i.uptimeMinutes))

            Spacer(Modifier.height(4.dp))
            OutlinedButton(
                onClick = { vm.requestDeviceInfo() },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
            ) { Text(stringResource(R.string.refresh)) }
        } else if (connected) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(12.dp))
                Text(stringResource(R.string.status_querying_device), style = MaterialTheme.typography.bodySmall)
            }
        } else {
            Text(
                stringResource(R.string.hw_connect_prompt),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
internal fun UserGuideSectionContent() {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        // ── Overview ────────────────────────────────────────────────────
        GuideSection(
            title = stringResource(R.string.guide_overview_title),
            body = stringResource(R.string.guide_overview_body),
        )

        // ── Getting Started ─────────────────────────────────────────────
        GuideSection(
            title = stringResource(R.string.guide_getting_started_title),
            body = stringResource(R.string.guide_getting_started_body),
        )

        // ── Transports ──────────────────────────────────────────────────
        GuideSection(
            title = stringResource(R.string.guide_transports_title),
            body = stringResource(R.string.guide_transports_body),
        )

        // ── Canon BLE in detail ─────────────────────────────────────────
        GuideSection(
            title = stringResource(R.string.guide_canon_ble_title),
            body = stringResource(R.string.guide_canon_ble_body),
        )

        // ── Intervalometer ──────────────────────────────────────────────
        GuideSection(
            title = stringResource(R.string.guide_intervalometer_title),
            body = stringResource(R.string.guide_intervalometer_body),
        )

        // ── Astro Mode ──────────────────────────────────────────────────
        GuideSection(
            title = stringResource(R.string.guide_astro_title),
            body = stringResource(R.string.guide_astro_body),
        )

        // ── Manual Mode ─────────────────────────────────────────────────
        GuideSection(
            title = stringResource(R.string.guide_manual_title),
            body = stringResource(R.string.guide_manual_body),
        )

        // ── Dark Frames ─────────────────────────────────────────────────
        GuideSection(
            title = stringResource(R.string.guide_dark_frames_title),
            body = stringResource(R.string.guide_dark_frames_body),
        )

        // ── Exposure Ramp ───────────────────────────────────────────────
        GuideSection(
            title = stringResource(R.string.guide_ramp_title),
            body = stringResource(R.string.guide_ramp_body),
        )

        // ── Flows & Presets ─────────────────────────────────────────────
        GuideSection(
            title = stringResource(R.string.guide_flows_title),
            body = stringResource(R.string.guide_flows_body),
        )

        // ── Star Focus Assist ───────────────────────────────────────────
        GuideSection(
            title = stringResource(R.string.guide_star_focus_title),
            body = stringResource(R.string.guide_star_focus_body),
        )

        // ── Polar Alignment ─────────────────────────────────────────────
        GuideSection(
            title = stringResource(R.string.guide_polar_align_title),
            body = stringResource(R.string.guide_polar_align_body),
        )

        // ── Camera Test ─────────────────────────────────────────────────
        GuideSection(
            title = stringResource(R.string.guide_camera_test_title),
            body = stringResource(R.string.guide_camera_test_body),
        )

        // ── Astro Dashboard ─────────────────────────────────────────────
        GuideSection(
            title = stringResource(R.string.guide_dashboard_title),
            body = stringResource(R.string.guide_dashboard_body),
        )

        // ── Session Planner ─────────────────────────────────────────────
        GuideSection(
            title = stringResource(R.string.guide_planner_title),
            body = stringResource(R.string.guide_planner_body),
        )

        // ── Shot Log ────────────────────────────────────────────────────
        GuideSection(
            title = stringResource(R.string.guide_shot_log_title),
            body = stringResource(R.string.guide_shot_log_body),
        )

        // ── Settings ────────────────────────────────────────────────────
        GuideSection(
            title = stringResource(R.string.guide_settings_title),
            body = stringResource(R.string.guide_settings_body),
        )

        // ── Tips ────────────────────────────────────────────────────────
        GuideSection(
            title = stringResource(R.string.guide_tips_title),
            body = stringResource(R.string.guide_tips_body),
        )
    }
}

@Composable
private fun GuideSection(title: String, body: String) {
    Column {
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            body,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
internal fun AboutSectionContent(
    debugMode: Boolean = false,
    onDebugModeChanged: (Boolean) -> Unit = {},
) {
    val uriHandler = LocalUriHandler.current

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(
                Icons.Outlined.Info,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(24.dp),
            )
            Spacer(Modifier.width(16.dp))
            Column {
                Text(stringResource(R.string.about_app_name), style = MaterialTheme.typography.titleSmall)
                Text(
                    stringResource(R.string.about_tagline),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Text(
            "v${BuildConfig.VERSION_NAME}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Text(
            stringResource(R.string.about_author),
            style = MaterialTheme.typography.bodyMedium,
        )

        Text(
            stringResource(R.string.about_license),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        OutlinedButton(
            onClick = { uriHandler.openUri("https://github.com/pulsar-trigger/pulsar-trigger") },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
        ) {
            Text(stringResource(R.string.about_github))
        }

        OutlinedButton(
            onClick = { uriHandler.openUri("https://instagram.com/ehrocha.br") },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
        ) {
            Text(stringResource(R.string.about_instagram))
        }

        Spacer(Modifier.height(8.dp))
        HorizontalDivider()
        Spacer(Modifier.height(8.dp))

        Text(
            stringResource(R.string.about_tribute_heading),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            stringResource(R.string.about_tribute_name),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
        )
        Text(
            stringResource(R.string.about_tribute_body),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedButton(
            onClick = { uriHandler.openUri("https://en.wikipedia.org/wiki/Jocelyn_Bell_Burnell") },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
        ) {
            Text(stringResource(R.string.about_tribute_link))
        }

        Spacer(Modifier.height(8.dp))
        HorizontalDivider()
        Spacer(Modifier.height(8.dp))

        Text(
            stringResource(R.string.about_data_sources_title),
            style = MaterialTheme.typography.titleSmall,
        )
        Text(
            stringResource(R.string.about_data_sources),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        // ── Developer options ───────────────────────────────────────
        // Single switch that gates the GATT Explorer (and any future
        // debug tooling). See docs/gatt-explorer-draft.md.
        Spacer(Modifier.height(8.dp))
        HorizontalDivider()
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(R.string.about_developer_options),
            style = MaterialTheme.typography.titleSmall,
        )
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        stringResource(R.string.about_debug_mode_title),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Text(
                        stringResource(R.string.about_debug_mode_sub),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = debugMode, onCheckedChange = onDebugModeChanged)
            }
        }
    }
}

@Composable
private fun RenameDeviceDialog(
    onDismiss: () -> Unit,
    onConfirm: (suffix: String) -> Unit,
) {
    var text by remember { mutableStateOf("") }
    val maxLen = 12

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.dialog_rename_device)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    stringResource(R.string.dialog_rename_instructions),
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedTextField(
                    value = text,
                    onValueChange = { if (it.length <= maxLen) text = it },
                    label = { Text(stringResource(R.string.label_device_name_input)) },
                    prefix = { Text(stringResource(R.string.prefix_pulsar)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { onConfirm(text) }),
                    supportingText = { Text(stringResource(R.string.char_count, text.length, maxLen)) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(text) }) {
                Text(stringResource(R.string.rename))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}

/** Turn a GitHub release body into readable "what's new" notes: drop the
 *  install boilerplate (new + legacy bodies) and strip markdown emphasis. */
private fun cleanReleaseNotes(raw: String): String =
    raw.lines()
        .filterNot {
            val t = it.trim()
            t.startsWith("Install via") ||
                t.startsWith("Automated Android app build") ||
                t.startsWith("**Version:") || t.startsWith("**Commit:") ||
                t == "—" || t == "---"
        }
        .joinToString("\n")
        .replace("**", "")
        .trim()

@Composable
private fun UpdatesSection(vm: PulsarViewModel, showFirmware: Boolean = true) {
    val connected = LocalDeviceConnected.current
    // Firmware state (only relevant when showFirmware == true; collected
    // unconditionally because StateFlow.collectAsState() can't be moved
    // into a conditional branch without breaking Compose state-key rules)
    val fwManager = vm.firmwareManager
    val otaState by fwManager.state.collectAsState()
    val fwProgress by fwManager.progress.collectAsState()
    val fwRelease by fwManager.latestRelease.collectAsState()
    val fwError by fwManager.errorMessage.collectAsState()
    val status = LocalDeviceStatus.current
    val fwVersion = status?.fwVersion ?: ""

    // App state
    val updateManager = vm.appUpdateManager
    val updateState by updateManager.state.collectAsState()
    val appRelease by updateManager.latestRelease.collectAsState()
    val appError by updateManager.errorMessage.collectAsState()
    val appVersion = BuildConfig.VERSION_NAME
    val lastCheckedAt by updateManager.lastCheckedAt.collectAsState()

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (showFirmware) {
        // ── Firmware ─────────────────────────────────────────────────
        Text(stringResource(R.string.label_firmware), style = MaterialTheme.typography.titleSmall)

        if (fwVersion.isNotEmpty()) {
            Text(
                stringResource(R.string.label_current_version, fwVersion),
                style = MaterialTheme.typography.bodyMedium,
            )
        }

        when (otaState) {
            OtaState.IDLE -> {
                OutlinedButton(
                    onClick = { fwManager.checkForUpdate(fwVersion) },
                    enabled = connected,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Icon(Icons.Default.SystemUpdate, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.btn_check_firmware))
                }
            }

            OtaState.CHECKING -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(12.dp))
                    Text(stringResource(R.string.status_checking_github), style = MaterialTheme.typography.bodyMedium)
                }
            }

            OtaState.UP_TO_DATE -> {
                Text(
                    stringResource(R.string.status_up_to_date),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                OutlinedButton(
                    onClick = { fwManager.reset() },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                ) { Text(stringResource(R.string.ok)) }
            }

            OtaState.AVAILABLE -> {
                fwRelease?.let { release ->
                    Text(
                        stringResource(R.string.label_new_version, release.version),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                    )
                    val releaseNotes = release.body
                        .lines()
                        .takeWhile { !it.startsWith("**Included") && !it.startsWith("Flash via") }
                        .joinToString("\n").trim()
                    if (releaseNotes.isNotBlank()) {
                        Text(
                            releaseNotes.take(200),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Button(
                        onClick = { fwManager.startUpdate() },
                        enabled = connected,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                    ) {
                        Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.btn_install_update))
                    }
                }
            }

            OtaState.DOWNLOADING -> {
                Text(stringResource(R.string.status_downloading_firmware), style = MaterialTheme.typography.bodyMedium)
                LinearProgressIndicator(
                    progress = { fwProgress },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "${(fwProgress * 100).toInt()}%",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedButton(
                    onClick = { fwManager.cancel() },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                ) { Text(stringResource(R.string.cancel)) }
            }

            OtaState.UPLOADING -> {
                Text(stringResource(R.string.status_uploading_device), style = MaterialTheme.typography.bodyMedium)
                LinearProgressIndicator(
                    progress = { fwProgress },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "${(fwProgress * 100).toInt()}%",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedButton(
                    onClick = { fwManager.cancel() },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                ) { Text(stringResource(R.string.cancel)) }
            }

            OtaState.VALIDATING -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(12.dp))
                    Text(stringResource(R.string.status_validating_rebooting), style = MaterialTheme.typography.bodyMedium)
                }
            }

            OtaState.COMPLETE -> {
                Text(
                    stringResource(R.string.status_update_complete),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold,
                )
                OutlinedButton(
                    onClick = { fwManager.reset() },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                ) { Text(stringResource(R.string.done)) }
            }

            OtaState.ERROR -> {
                Text(
                    fwError ?: stringResource(R.string.status_update_failed),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
                OutlinedButton(
                    onClick = { fwManager.reset() },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                ) { Text(stringResource(R.string.dismiss)) }
            }
        }

        HorizontalDivider()
        } // end if (showFirmware)

        // ── App ──────────────────────────────────────────────────────
        Text(stringResource(R.string.label_app), style = MaterialTheme.typography.titleSmall)

        Text(
            "Current: v$appVersion",
            style = MaterialTheme.typography.bodyMedium,
        )

        when (updateState) {
            // Before a check AND after an "up to date" result: always offer
            // the check button (no useless no-op OK), and show when we last
            // checked + the up-to-date confirmation.
            AppUpdateState.IDLE, AppUpdateState.UP_TO_DATE -> {
                if (updateState == AppUpdateState.UP_TO_DATE) {
                    Text(
                        stringResource(R.string.status_up_to_date),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                lastCheckedAt?.let { ts ->
                    Text(
                        stringResource(
                            R.string.app_update_last_checked,
                            android.text.format.DateUtils.getRelativeTimeSpanString(
                                ts,
                                System.currentTimeMillis(),
                                android.text.format.DateUtils.MINUTE_IN_MILLIS,
                            ).toString(),
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                OutlinedButton(
                    onClick = { updateManager.checkForUpdate(appVersion) },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Icon(Icons.Default.SystemUpdate, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.btn_check_app_update))
                }
            }

            AppUpdateState.CHECKING -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(12.dp))
                    Text(stringResource(R.string.status_checking_github), style = MaterialTheme.typography.bodyMedium)
                }
            }

            AppUpdateState.AVAILABLE -> {
                appRelease?.let { release ->
                    Text(
                        stringResource(R.string.label_new_version, release.version),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                    )
                    val notes = cleanReleaseNotes(release.body)
                    if (notes.isNotBlank()) {
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerHigh,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        stringResource(R.string.update_whats_new).uppercase(),
                                        style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 1.2.sp),
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    // Release notes are authored in English; cue
                                    // non-English users so it reads as intentional.
                                    if (java.util.Locale.getDefault().language != "en") {
                                        Spacer(Modifier.width(6.dp))
                                        Text(
                                            "· " + stringResource(R.string.update_notes_lang),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                        )
                                    }
                                }
                                Spacer(Modifier.height(6.dp))
                                Text(
                                    notes,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier
                                        .heightIn(max = 220.dp)
                                        .verticalScroll(rememberScrollState()),
                                )
                            }
                        }
                    }
                    Button(
                        onClick = { updateManager.downloadAndInstall() },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                    ) {
                        Icon(Icons.Default.SystemUpdate, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.btn_download_install))
                    }
                    TextButton(
                        onClick = { updateManager.openReleasePage() },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.btn_open_release_page))
                    }
                }
            }

            AppUpdateState.DOWNLOADING -> {
                val progress by updateManager.downloadProgress.collectAsState()
                Text(
                    stringResource(R.string.status_downloading_apk, (progress * 100).toInt()),
                    style = MaterialTheme.typography.bodyMedium,
                )
                LinearProgressIndicator(
                    progress = { progress.coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            AppUpdateState.READY_TO_INSTALL -> {
                Text(
                    stringResource(R.string.status_apk_ready),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                Button(
                    onClick = { updateManager.launchInstaller() },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Icon(Icons.Default.SystemUpdate, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.btn_install_now))
                }
            }

            AppUpdateState.ERROR -> {
                Text(
                    appError ?: stringResource(R.string.status_update_check_failed),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
                OutlinedButton(
                    onClick = { updateManager.reset() },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                ) { Text(stringResource(R.string.dismiss)) }
            }
        }

        // ── Rollback picker: pick from the last ~10 published releases ──
        // Always available, regardless of update state — useful for testers
        // who want to A/B between versions or roll back from a bad release.
        VersionRollbackPicker(updateManager)
    }
}

@Composable
private fun VersionRollbackPicker(updateManager: com.ehrocha.pulsar.update.AppUpdateManager) {
    var showPicker by remember { mutableStateOf(false) }
    var confirmRelease by remember { mutableStateOf<com.ehrocha.pulsar.update.AppRelease?>(null) }
    val releases by updateManager.recentReleases.collectAsState()
    val loading by updateManager.recentReleasesLoading.collectAsState()
    val currentVersion = BuildConfig.VERSION_NAME

    OutlinedButton(
        onClick = {
            showPicker = true
            if (releases.isEmpty()) updateManager.fetchRecentReleases()
        },
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
    ) {
        Icon(Icons.Default.History, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(stringResource(R.string.btn_pick_previous_version))
    }

    if (showPicker) {
        AlertDialog(
            onDismissRequest = { showPicker = false },
            title = { Text(stringResource(R.string.dialog_pick_previous_title)) },
            text = {
                if (loading && releases.isEmpty()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(12.dp))
                        Text(stringResource(R.string.status_checking_github))
                    }
                } else if (releases.isEmpty()) {
                    Text(stringResource(R.string.dialog_pick_previous_empty))
                } else {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 380.dp)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        releases.forEach { release ->
                            val isCurrent = release.version == currentVersion
                            Surface(
                                onClick = { if (!isCurrent) confirmRelease = release },
                                enabled = !isCurrent,
                                shape = RoundedCornerShape(12.dp),
                                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            "v${release.version}",
                                            style = MaterialTheme.typography.titleSmall,
                                            fontWeight = FontWeight.Bold,
                                        )
                                        Text(
                                            release.publishedAt.take(10),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                    if (isCurrent) {
                                        Text(
                                            stringResource(R.string.label_current),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.primary,
                                            fontWeight = FontWeight.Bold,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showPicker = false }) {
                    Text(stringResource(R.string.tools_logs_close))
                }
            },
            dismissButton = {
                if (!loading && releases.isNotEmpty()) {
                    TextButton(onClick = { updateManager.fetchRecentReleases() }) {
                        Text(stringResource(R.string.refresh))
                    }
                }
            },
        )
    }

    confirmRelease?.let { release ->
        AlertDialog(
            onDismissRequest = { confirmRelease = null },
            title = { Text(stringResource(R.string.dialog_install_specific_title, release.version)) },
            text = { Text(stringResource(R.string.dialog_install_specific_body)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmRelease = null
                    showPicker = false
                    updateManager.downloadAndInstall(override = release)
                }) {
                    Text(stringResource(R.string.btn_install_now))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmRelease = null }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GpioPinSelector(
    label: String,
    selectedPin: Int,
    disabledPin: Int,
    onPinSelected: (Int) -> Unit,
    enabled: Boolean,
    pins: List<Int>,
) {
    var expanded by remember { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

        ExposedDropdownMenuBox(
            expanded = expanded,
            onExpandedChange = { if (enabled) expanded = it },
        ) {
            OutlinedTextField(
                value = stringResource(R.string.gpio_value, selectedPin),
                onValueChange = {},
                readOnly = true,
                enabled = enabled,
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable, enabled).fillMaxWidth(),
                singleLine = true,
            )

            ExposedDropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
            ) {
                pins.forEach { pin ->
                    val isDisabled = pin == disabledPin
                    DropdownMenuItem(
                        text = {
                            Text(
                                if (isDisabled) stringResource(R.string.gpio_in_use, pin)
                                else stringResource(R.string.gpio_value, pin),
                                color = if (isDisabled) MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                                        else MaterialTheme.colorScheme.onSurface,
                            )
                        },
                        onClick = {
                            if (!isDisabled) {
                                onPinSelected(pin)
                                expanded = false
                            }
                        },
                        enabled = !isDisabled,
                    )
                }
            }
        }
    }
}

// ── Device Hardware Info helpers ──────────────────────────────────────────────

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
    }
}

private fun formatKb(kb: Long): String = when {
    kb >= 1024 -> "${kb / 1024} MB"
    else -> "$kb KB"
}

private fun formatUptime(minutes: Int): String {
    val h = minutes / 60
    val m = minutes % 60
    return if (h > 0) "${h}h ${m}m" else "${m}m"
}

// ── Planner settings section ─────────────────────────────────────────────────

@Composable
internal fun PlannerSettingsSectionContent(vm: PulsarViewModel) {
    val cacheOptions = listOf(6L, 12L, 24L, 48L, 72L)
    var currentInterval by remember { mutableLongStateOf(vm.plannerManager.cacheIntervalHours) }

    var currentThreshold by remember { mutableIntStateOf(vm.plannerManager.cloudClearThreshold) }

    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        PanelHelpHeader(
            title = stringResource(R.string.section_planner),
            helpText = stringResource(R.string.planner_cache_help),
        )

        // Cache interval selector
        Text(
            stringResource(R.string.planner_cache_interval),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
        )

        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            cacheOptions.forEachIndexed { index, hours ->
                SegmentedButton(
                    selected = currentInterval == hours,
                    onClick = {
                        currentInterval = hours
                        vm.plannerManager.cacheIntervalHours = hours
                    },
                    shape = SegmentedButtonDefaults.itemShape(index = index, count = cacheOptions.size),
                ) {
                    Text(stringResource(R.string.planner_cache_hours, hours))
                }
            }
        }

        HorizontalDivider()

        // Cloud cover threshold slider
        Text(
            stringResource(R.string.planner_cloud_threshold),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
        )
        Text(
            stringResource(R.string.planner_cloud_threshold_help),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Slider(
                value = currentThreshold.toFloat(),
                onValueChange = { currentThreshold = it.toInt() },
                onValueChangeFinished = { vm.plannerManager.cloudClearThreshold = currentThreshold },
                valueRange = 5f..80f,
                steps = 14,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(12.dp))
            Text(
                stringResource(R.string.planner_cloud_threshold_value, currentThreshold),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}
