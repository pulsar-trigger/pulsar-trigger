/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Copyright (C) 2026 Pulsar Trigger contributors
 */

package com.ehrocha.pulsar.ui.screens

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.LensBlur
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.Stars
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.automirrored.filled.TrendingUp
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ehrocha.pulsar.R
import com.ehrocha.pulsar.model.FlowStepType
import com.ehrocha.pulsar.ui.components.SectionContainer
import com.ehrocha.pulsar.viewmodel.PulsarViewModel
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainMenuScreen(
    vm: PulsarViewModel,
    initialTab: Int = TAB_TRIGGER,
    onTabChanged: (Int) -> Unit = {},
    onQuickFlow: (FlowStepType) -> Unit,
    onManualSelected: () -> Unit,
    onCableReleaseSelected: () -> Unit = {},
    onCustomFlowSelected: () -> Unit = {},
    onPlannerSelected: () -> Unit = {},
    onAlignmentSelected: () -> Unit = {},
    onWhatsUpSelected: () -> Unit = {},
    onStarFocusSelected: () -> Unit = {},
    onTestCameraSelected: () -> Unit = {},
    onDiagnosticsSelected: () -> Unit = {},
    onUserModeRun: (com.ehrocha.pulsar.model.UserMode) -> Unit = {},
    onIntervalometer2Selected: () -> Unit = {},
    onAstroMode2Selected: () -> Unit = {},
    onTimelapseSelected: () -> Unit = {},
    onSettingsSelected: () -> Unit = {},
) {
    val fwState by vm.firmwareManager.state.collectAsState()
    val appState by vm.appUpdateManager.state.collectAsState()
    val fwRelease by vm.firmwareManager.latestRelease.collectAsState()
    val appRelease by vm.appUpdateManager.latestRelease.collectAsState()
    val hasFwUpdate = fwState == com.ehrocha.pulsar.ble.OtaState.AVAILABLE && fwRelease != null
    val hasAppUpdate = appState == com.ehrocha.pulsar.update.AppUpdateState.AVAILABLE && appRelease != null
    var bannerDismissed by remember { mutableStateOf(false) }

    // Dashboard sits to the LEFT of Trigger so a left-swipe from the default
    // Trigger tab reveals the astro dashboard. Trigger is the default landing
    // tab — the one users open the app for.
    val tabs = listOf(
        stringResource(R.string.tab_dashboard),
        stringResource(R.string.tab_trigger),
        stringResource(R.string.tab_tools),
    )
    val pagerState = rememberPagerState(initialPage = initialTab, pageCount = { tabs.size })
    val scope = rememberCoroutineScope()
    LaunchedEffect(pagerState.currentPage) { onTabChanged(pagerState.currentPage) }

    // Update banner extracted into a lambda so both layout branches render
    // the same dismissible banner without duplicating the markup.
    val updateBanner: @Composable () -> Unit = {
        if ((hasFwUpdate || hasAppUpdate) && !bannerDismissed) {
            Surface(
                color = MaterialTheme.colorScheme.primaryContainer,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Default.Settings,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            stringResource(R.string.dialog_updates_available),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                        if (hasFwUpdate) {
                            Text(
                                stringResource(R.string.update_firmware_available, fwRelease!!.version),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                            )
                        }
                        if (hasAppUpdate) {
                            Text(
                                stringResource(R.string.update_app_available, appRelease!!.version),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                            )
                        }
                    }
                    TextButton(onClick = onSettingsSelected) {
                        Text(stringResource(R.string.btn_go_to_settings))
                    }
                    IconButton(
                        onClick = { bannerDismissed = true },
                        modifier = Modifier.size(24.dp),
                    ) {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = stringResource(R.string.dismiss),
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
        }
    }

    // Per-tab content. Same composables in both layouts.
    val pageContent: @Composable (Int) -> Unit = { page ->
        MenuPageContent(
            page = page, vm = vm,
            onQuickFlow = onQuickFlow,
            onManualSelected = onManualSelected,
            onCableReleaseSelected = onCableReleaseSelected,
            onCustomFlowSelected = onCustomFlowSelected,
            onPlannerSelected = onPlannerSelected,
            onAlignmentSelected = onAlignmentSelected,
            onWhatsUpSelected = onWhatsUpSelected,
            onStarFocusSelected = onStarFocusSelected,
            onTestCameraSelected = onTestCameraSelected,
            onDiagnosticsSelected = onDiagnosticsSelected,
            onUserModeRun = onUserModeRun,
            onIntervalometer2Selected = onIntervalometer2Selected,
            onAstroMode2Selected = onAstroMode2Selected,
            onTimelapseSelected = onTimelapseSelected,
        )
    }


    // Horizontal padding lives on each page, not on the outer Column — the
    // Dashboard page wants edge-to-edge so its inner padding doesn't double.
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(vertical = 12.dp),
    ) {

        updateBanner()

        // ── Tab row ──────────────────────────────────────────────────
        // Material 3 PrimaryTabRow uses a rounded pill indicator (vs the
        // older TabRow underline) — modernizes the look without changing
        // structure.
        PrimaryTabRow(
            selectedTabIndex = pagerState.currentPage,
            containerColor = MaterialTheme.colorScheme.surface,
        ) {
            tabs.forEachIndexed { index, title ->
                Tab(
                    selected = pagerState.currentPage == index,
                    onClick = { scope.launch { pagerState.animateScrollToPage(index) } },
                    text = { Text(title) },
                )
            }
        }

        Spacer(Modifier.height(12.dp))

        // ── Pager ────────────────────────────────────────────────────
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.weight(1f),
        ) { page ->
            pageContent(page)
        }
    }
}

@Composable
private fun MenuPageContent(
    page: Int,
    vm: PulsarViewModel,
    onQuickFlow: (FlowStepType) -> Unit,
    onManualSelected: () -> Unit,
    onCableReleaseSelected: () -> Unit,
    onCustomFlowSelected: () -> Unit,
    onPlannerSelected: () -> Unit,
    onAlignmentSelected: () -> Unit,
    onWhatsUpSelected: () -> Unit,
    onStarFocusSelected: () -> Unit,
    onTestCameraSelected: () -> Unit,
    onDiagnosticsSelected: () -> Unit,
    onUserModeRun: (com.ehrocha.pulsar.model.UserMode) -> Unit,
    onIntervalometer2Selected: () -> Unit,
    onAstroMode2Selected: () -> Unit,
    onTimelapseSelected: () -> Unit,
) {
    when (page) {
        TAB_DASHBOARD -> {
            DashboardScreen(dashboardManager = vm.dashboardManager)
        }
        TAB_TRIGGER -> {
                    val userModes by vm.userModes.collectAsState()
                    val canonCcapiTransport by vm.canonCcapiTransport.collectAsState()
                    val canonCcapiReconnecting by vm.canonCcapiReconnecting.collectAsState()
                    val ptpTransport by vm.ptpTransport.collectAsState()
                    val ptpReconnecting by vm.ptpReconnecting.collectAsState()
                    val canonBleTransport by vm.canonBleTransport.collectAsState()
                    val canonBleReconnecting by vm.canonBleReconnecting.collectAsState()
                    val supportsBulb by vm.activeTransportSupportsBulb.collectAsState()
                    val onCanon = canonCcapiTransport != null
                    val onPtp = ptpTransport != null
                    val onCanonBle = canonBleTransport != null
                    // Bulb-based modes can't run on a phone-driven transport
                    // that doesn't advertise bulb: PowerShots / older R-bodies
                    // over CCAPI (no `/shutterbutton/manual`), Nikon/Sony/Fuji
                    // over PTP (no Canon RemoteRelease op). Dim those tiles
                    // so the user doesn't start a flow that'd fail at the
                    // first bulb call. Canon BLE is hard-true on this flag.
                    val bulbBlocked = (onCanon || onPtp || onCanonBle) && !supportsBulb
                    // Bulb wizards — camera dial → Bulb. The app drives
                    // exposure timing on these.
                    val bulbTiles = listOf(
                        launcherItem(R.string.mode_intervalometer, Icons.Default.Timer,
                            enabled = !bulbBlocked) { onIntervalometer2Selected() },
                        launcherItem(R.string.mode_astro, Icons.Default.Stars,
                            enabled = !bulbBlocked) { onAstroMode2Selected() },
                        launcherItem(R.string.mode_dark_frame, Icons.Default.LensBlur,
                            enabled = !bulbBlocked) { onQuickFlow(FlowStepType.DARK_FRAME) },
                        launcherItem(R.string.mode_ramp, Icons.AutoMirrored.Filled.TrendingUp,
                            enabled = !bulbBlocked) { onQuickFlow(FlowStepType.RAMP) },
                        launcherItem(R.string.mode_manual, Icons.Default.TouchApp) {
                            onManualSelected()
                        },
                    )
                    // Standard modes — camera dial → M. Camera owns the
                    // exposure; Pulsar just fires shutter events.
                    val standardTiles = listOf(
                        launcherItem(R.string.mode_timelapse, Icons.Default.PhotoLibrary) {
                            onTimelapseSelected()
                        },
                        launcherItem(R.string.mode_cable_release, Icons.Default.PhotoCamera) {
                            onCableReleaseSelected()
                        },
                    )
                    // Favorites — bookmarked user presets only. Other saved
                    // presets live in the per-mode preset picker.
                    val favoriteTiles = userModes.filter { it.bookmarked }.map { mode ->
                        LauncherItem(
                            key = "user:${mode.id}",
                            label = mode.name,
                            icon = Icons.Default.Bookmark,
                            onClick = { onUserModeRun(mode) },
                        )
                    }
                    // Custom — multi-step flows authored by the user.
                    val customTiles = listOf(
                        launcherItem(R.string.mode_custom_flow, Icons.AutoMirrored.Filled.ViewList,
                            enabled = !bulbBlocked) { onCustomFlowSelected() },
                    )
                    Column(
                        modifier = Modifier
                            .padding(horizontal = 16.dp)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        if (onCanon) {
                            CanonBulbBanner(
                                reconnecting = canonCcapiReconnecting,
                                bulbUnsupported = bulbBlocked,
                            )
                        }
                        if (onPtp || ptpReconnecting) {
                            PtpBanner(reconnecting = ptpReconnecting)
                        }
                        if (onCanonBle || canonBleReconnecting) {
                            CanonBleBanner(reconnecting = canonBleReconnecting)
                        }
                        SectionContainer(title = stringResource(R.string.trigger_section_bulb)) {
                            SectionGrid(bulbTiles)
                        }
                        SectionContainer(title = stringResource(R.string.trigger_section_standard)) {
                            SectionGrid(standardTiles)
                        }
                        if (favoriteTiles.isNotEmpty()) {
                            SectionContainer(title = stringResource(R.string.trigger_section_favorites)) {
                                SectionGrid(favoriteTiles)
                            }
                        }
                        SectionContainer(title = stringResource(R.string.trigger_section_custom)) {
                            SectionGrid(customTiles)
                        }
                        Spacer(Modifier.height(8.dp))
                    }
                }
                TAB_TOOLS -> {
                    val canonOn = vm.canonCcapiTransport.collectAsState().value != null
                    // Star Focus needs a live-view-capable Canon transport.
                    // CCAPI always supplies live view (gated by canonOn).
                    // For PTP / PTP/IP we observe `liveViewSupportedFlow` so a
                    // **runtime downgrade** (body advertised GetViewFinderData
                    // but rejects SetEvfOutput — see EOS R / RP) re-greys the
                    // tile without an app restart.
                    val ptpTx = vm.ptpTransport.collectAsState().value
                    val ptpLive = ptpTx?.liveViewSupportedFlow
                        ?.collectAsState(initial = false)?.value == true
                    val ptpIpTx = vm.ptpIpTransport.collectAsState().value
                    val ptpIpLive = ptpIpTx?.liveViewSupportedFlow
                        ?.collectAsState(initial = false)?.value == true
                    val starFocusEnabled = canonOn || ptpLive || ptpIpLive
                    val simulatorActive by vm.simulatorActive.collectAsState()
                    // Camera Test exists to verify the *real* wire — pulsing
                    // the simulator would just prove the simulator works.
                    val cameraTestEnabled = !simulatorActive
                    val toolItems = listOf(
                        launcherItem(R.string.mode_planner, Icons.Default.DateRange) {
                            onPlannerSelected()
                        },
                        launcherItem(R.string.mode_alignment, Icons.Default.Explore) {
                            onAlignmentSelected()
                        },
                        launcherItem(R.string.mode_whats_up, Icons.Default.Visibility) {
                            onWhatsUpSelected()
                        },
                        // CCAPI or PTP only; greyed out when on BLE / simulator
                        // so its absence is discoverable without breaking layout.
                        launcherItem(
                            R.string.mode_star_focus,
                            Icons.Default.Star,
                            enabled = starFocusEnabled,
                        ) { onStarFocusSelected() },
                        launcherItem(
                            R.string.mode_test_camera,
                            Icons.Default.Science,
                            enabled = cameraTestEnabled,
                        ) { onTestCameraSelected() },
                        launcherItem(R.string.mode_diagnostics, Icons.Default.Description) {
                            onDiagnosticsSelected()
                        },
                    )
                    Box(modifier = Modifier.padding(horizontal = 16.dp)) {
                        LauncherGrid(toolItems)
                    }
                }
            }
}

const val TAB_DASHBOARD = 0
const val TAB_TRIGGER = 1
const val TAB_TOOLS = 2

private data class LauncherItem(
    val key: String,
    val label: String,
    val icon: ImageVector,
    val enabled: Boolean = true,
    val onClick: () -> Unit,
)

@Composable
private fun launcherItem(
    labelRes: Int,
    icon: ImageVector,
    enabled: Boolean = true,
    onClick: () -> Unit,
): LauncherItem = LauncherItem(
    key = "res:$labelRes",
    label = stringResource(labelRes),
    icon = icon,
    enabled = enabled,
    onClick = onClick,
)

/** Non-lazy 3-column tile grid for placement inside a [SectionContainer],
 *  where a LazyVerticalGrid would fail to size (infinite height) and
 *  inflate the layout pass. Empty trailing cells are spacered so the
 *  partial last row stays left-aligned with the rows above it. */
@Composable
private fun SectionGrid(items: List<LauncherItem>) {
    if (items.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        items.chunked(3).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { item ->
                    LauncherTile(
                        label = item.label,
                        icon = item.icon,
                        enabled = item.enabled,
                        onClick = item.onClick,
                        modifier = Modifier.weight(1f),
                    )
                }
                repeat(3 - row.size) {
                    Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun LauncherGrid(items: List<LauncherItem>) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(3),
        contentPadding = PaddingValues(4.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        items(items, key = { it.key }) { item ->
            LauncherTile(
                label = item.label,
                icon = item.icon,
                enabled = item.enabled,
                onClick = item.onClick,
            )
        }
    }
}

@Composable
private fun CanonBulbBanner(
    reconnecting: Boolean,
    bulbUnsupported: Boolean,
) {
    // Three-state precedence: reconnecting > bulbUnsupported > healthy.
    val containerColor = when {
        reconnecting || bulbUnsupported -> MaterialTheme.colorScheme.errorContainer
        else -> MaterialTheme.colorScheme.secondaryContainer
    }
    val onContainer = when {
        reconnecting || bulbUnsupported -> MaterialTheme.colorScheme.onErrorContainer
        else -> MaterialTheme.colorScheme.onSecondaryContainer
    }
    val copyRes = when {
        reconnecting -> R.string.canon_reconnecting_hint
        bulbUnsupported -> R.string.canon_no_bulb_hint
        else -> R.string.canon_bulb_hint
    }
    Surface(
        color = containerColor,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (reconnecting) {
                CircularProgressIndicator(
                    strokeWidth = 2.dp,
                    color = onContainer,
                    modifier = Modifier.size(16.dp),
                )
            } else {
                Icon(
                    Icons.Default.Wifi,
                    contentDescription = null,
                    tint = onContainer,
                    modifier = Modifier.size(20.dp),
                )
            }
            Spacer(Modifier.width(8.dp))
            Text(
                text = stringResource(copyRes),
                style = MaterialTheme.typography.labelMedium,
                color = onContainer,
            )
        }
    }
}

@Composable
private fun CanonBleBanner(reconnecting: Boolean) {
    val containerColor = if (reconnecting) MaterialTheme.colorScheme.errorContainer
                        else MaterialTheme.colorScheme.secondaryContainer
    val onContainer = if (reconnecting) MaterialTheme.colorScheme.onErrorContainer
                     else MaterialTheme.colorScheme.onSecondaryContainer
    val copyRes = if (reconnecting) R.string.canon_ble_reconnecting_hint
                  else R.string.canon_ble_connected_hint
    Surface(
        color = containerColor,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (reconnecting) {
                CircularProgressIndicator(
                    strokeWidth = 2.dp,
                    color = onContainer,
                    modifier = Modifier.size(16.dp),
                )
            } else {
                Icon(
                    Icons.Default.Bluetooth,
                    contentDescription = null,
                    tint = onContainer,
                    modifier = Modifier.size(20.dp),
                )
            }
            Spacer(Modifier.width(8.dp))
            Text(
                text = stringResource(copyRes),
                style = MaterialTheme.typography.labelMedium,
                color = onContainer,
            )
        }
    }
}

@Composable
private fun PtpBanner(reconnecting: Boolean) {
    val containerColor = if (reconnecting) MaterialTheme.colorScheme.errorContainer
                        else MaterialTheme.colorScheme.secondaryContainer
    val onContainer = if (reconnecting) MaterialTheme.colorScheme.onErrorContainer
                     else MaterialTheme.colorScheme.onSecondaryContainer
    val copyRes = if (reconnecting) R.string.ptp_reconnecting_hint
                  else R.string.ptp_connected_hint
    Surface(
        color = containerColor,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (reconnecting) {
                CircularProgressIndicator(
                    strokeWidth = 2.dp,
                    color = onContainer,
                    modifier = Modifier.size(16.dp),
                )
            } else {
                Icon(
                    Icons.Default.Usb,
                    contentDescription = null,
                    tint = onContainer,
                    modifier = Modifier.size(20.dp),
                )
            }
            Spacer(Modifier.width(8.dp))
            Text(
                text = stringResource(copyRes),
                style = MaterialTheme.typography.labelMedium,
                color = onContainer,
            )
        }
    }
}

@Composable
private fun LauncherTile(
    label: String,
    icon: ImageVector,
    onClick: () -> Unit,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed && enabled) 0.92f else 1f,
        animationSpec = spring(stiffness = 500f),
        label = "tileScale",
    )

    // Denser tile: drop the 0.9 aspect-ratio square so the tile sizes to
    // content (much shorter), bump corner radius for a more "expressive"
    // M3 feel, use surfaceContainerHigh (M3 token) instead of plain
    // surface + tonalElevation.
    Surface(
        onClick = onClick,
        enabled = enabled,
        interactionSource = interactionSource,
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = modifier
            .fillMaxWidth()
            .scale(scale),
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 14.dp),
        ) {
            val contentAlpha = if (enabled) 1f else 0.35f
            Icon(
                icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary.copy(alpha = contentAlpha),
                modifier = Modifier.size(26.dp),
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = contentAlpha),
            )
        }
    }
}
