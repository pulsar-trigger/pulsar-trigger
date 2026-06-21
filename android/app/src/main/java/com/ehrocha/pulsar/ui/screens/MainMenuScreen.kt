/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Copyright (C) 2026 Pulsar Trigger contributors
 */

package com.ehrocha.pulsar.ui.screens

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import com.ehrocha.pulsar.ui.theme.Mono
import kotlin.math.abs
import kotlin.math.roundToInt
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CenterFocusStrong
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.FilterBAndW
import androidx.compose.material.icons.filled.FlightTakeoff
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.LensBlur
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.automirrored.filled.ListAlt
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
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ehrocha.pulsar.R
import com.ehrocha.pulsar.model.FlowStepType
import com.ehrocha.pulsar.ui.components.PcbField
import com.ehrocha.pulsar.ui.components.SectionContainer
import com.ehrocha.pulsar.viewmodel.PulsarViewModel
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainMenuScreen(
    vm: PulsarViewModel,
    initialDest: Int = DEST_TRIGGER,
    onDestChanged: (Int) -> Unit = {},
    onQuickFlow: (FlowStepType) -> Unit,
    onManualSelected: () -> Unit,
    onCableReleaseSelected: () -> Unit = {},
    onCustomFlowSelected: () -> Unit = {},
    onPlannerSelected: () -> Unit = {},
    onAlignmentSelected: () -> Unit = {},
    onWhatsUpSelected: () -> Unit = {},
    onMeteorCalendarSelected: () -> Unit = {},
    onStarFocusSelected: () -> Unit = {},
    onPhotoTransferSelected: () -> Unit = {},
    onTestCameraSelected: () -> Unit = {},
    onAircraftWatchSelected: () -> Unit = {},
    onNdCalcSelected: () -> Unit = {},
    onDofCalcSelected: () -> Unit = {},
    onDiagnosticsSelected: () -> Unit = {},
    onUserModeRun: (com.ehrocha.pulsar.model.UserMode) -> Unit = {},
    onIntervalometer2Selected: () -> Unit = {},
    onAstroMode2Selected: () -> Unit = {},
    onTimelapseSelected: () -> Unit = {},
    onStarTrailsSelected: () -> Unit = {},
    onSettingsSelected: () -> Unit = {},
    /** Routed when the user taps the "Go to Settings" button on the
     *  in-app update banner — should jump to Updates, not the menu root. */
    onUpdatesSelected: () -> Unit = {},
    onDisconnect: () -> Unit = {},
    onShotLogSelected: () -> Unit = {},
    nightModeToggle: @Composable () -> Unit = {},
) {
    val fwState by vm.firmwareManager.state.collectAsState()
    val appState by vm.appUpdateManager.state.collectAsState()
    val fwRelease by vm.firmwareManager.latestRelease.collectAsState()
    val appRelease by vm.appUpdateManager.latestRelease.collectAsState()
    val hasFwUpdate = fwState == com.ehrocha.pulsar.ble.OtaState.AVAILABLE && fwRelease != null
    val hasAppUpdate = appState == com.ehrocha.pulsar.update.AppUpdateState.AVAILABLE && appRelease != null
    var bannerDismissed by remember { mutableStateOf(false) }

    // Dashboard sits to the LEFT of Trigger so a left-swipe from the default
    // Trigger destination reveals the astro dashboard. Trigger is the default
    // landing destination — the one users open the app for. Favorites earns
    // a dedicated destination (between Trigger and Tools) because power users
    // live in their pinned presets.
    // Favorites folded into the Trigger page (v0.438, Eduardo: a whole
    // destination for bookmarked presets was an oddball — wizard preset
    // pickers + the Trigger sections already cover it).
    val destinations = listOf(
        stringResource(R.string.dest_dashboard),
        stringResource(R.string.dest_trigger),
        stringResource(R.string.dest_tools),
    )
    // "Infinite" pager (Eduardo): swiping right past Tools wraps to
    // Dashboard and vice-versa. Virtual page space, real destination =
    // page % count; the bottom bar targets the nearest virtual page so a
    // tap never spins the carousel.
    val destCount = destinations.size
    val virtualCount = destCount * 1000
    val basePage = virtualCount / 2 - (virtualCount / 2) % destCount
    val pagerState = rememberPagerState(
        initialPage = basePage + initialDest,
        pageCount = { virtualCount },
    )
    val currentDest = pagerState.currentPage % destCount
    val scope = rememberCoroutineScope()
    LaunchedEffect(currentDest) { onDestChanged(currentDest) }

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
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
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
                    TextButton(onClick = onUpdatesSelected) {
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

    // Per-destination content — drives the HorizontalPager body below.
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
            onMeteorCalendarSelected = onMeteorCalendarSelected,
            onStarFocusSelected = onStarFocusSelected,
            onPhotoTransferSelected = onPhotoTransferSelected,
            onTestCameraSelected = onTestCameraSelected,
            onAircraftWatchSelected = onAircraftWatchSelected,
            onNdCalcSelected = onNdCalcSelected,
            onDofCalcSelected = onDofCalcSelected,
            onDiagnosticsSelected = onDiagnosticsSelected,
            onUserModeRun = onUserModeRun,
            onIntervalometer2Selected = onIntervalometer2Selected,
            onAstroMode2Selected = onAstroMode2Selected,
            onTimelapseSelected = onTimelapseSelected,
            onStarTrailsSelected = onStarTrailsSelected,
        )
    }


    // Per-destination icons for the bottom NavigationBar.
    val destIcons = listOf(
        Icons.Default.Stars,         // Dashboard
        Icons.Default.PhotoCamera,   // Trigger
        Icons.Default.Science,       // Tools
    )
    val hasAnyUpdate = hasFwUpdate || hasAppUpdate

    Scaffold(
        topBar = {
            // Global actions move from the old bottom strip into the
            // TopAppBar so the bottom belongs entirely to navigation.
            TopAppBar(
                title = {
                    // Destination names are the brand voice — Unbounded,
                    // the only place type shouts on an idle screen.
                    Text(
                        destinations[currentDest],
                        style = MaterialTheme.typography.headlineMedium,
                    )
                },
                actions = {
                    IconButton(onClick = onDisconnect) {
                        Icon(
                            Icons.Default.LinkOff,
                            contentDescription = stringResource(R.string.disconnect),
                        )
                    }
                    nightModeToggle()
                    IconButton(onClick = onShotLogSelected) {
                        Icon(
                            Icons.AutoMirrored.Filled.ListAlt,
                            contentDescription = stringResource(R.string.shot_log_title),
                        )
                    }
                    IconButton(onClick = onSettingsSelected) {
                        BadgedBox(
                            badge = {
                                if (hasAnyUpdate) {
                                    Badge(containerColor = MaterialTheme.colorScheme.error)
                                }
                            }
                        ) {
                            Icon(
                                Icons.Default.Settings,
                                contentDescription = stringResource(R.string.menu_settings),
                            )
                        }
                    }
                },
            )
        },
        bottomBar = {
            // Compact NavigationBar — Instagram-style height instead of
            // Material's default 80dp. Icons-only at this density to keep
            // hit targets readable.
            NavigationBar(modifier = Modifier.height(64.dp)) {
                destinations.forEachIndexed { index, title ->
                    NavigationBarItem(
                        selected = currentDest == index,
                        onClick = {
                            scope.launch {
                                // nearest virtual page carrying this dest
                                val delta = index - currentDest
                                pagerState.animateScrollToPage(pagerState.currentPage + delta)
                            }
                        },
                        icon = { Icon(destIcons[index], contentDescription = title) },
                    )
                }
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(vertical = 8.dp),
        ) {
            updateBanner()
            // One continuous PCB spans all destinations: the board sits behind
            // the pager and pans 1:1 with the swipe, so each page is a different
            // region of the same board.
            Box(modifier = Modifier.weight(1f).fillMaxWidth().clipToBounds()) {
                ContinuousPcb(pagerState, destCount)
                // Pager body — swipe still works to change destinations, the
                // NavigationBar reflects the swipe and vice-versa.
                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier.fillMaxSize(),
                ) { virtualPage ->
                    val page = virtualPage % destCount
                    pageContent(page)
                }
            }
        }
    }
}

/** The single PCB shared by all destinations, panned 1:1 with the pager so each
 *  page lands on a different region of the same board. The board is laid out as
 *  [destCount] distinct regions side by side plus a repeat of region 0, so the
 *  infinite pager's wrap (last → first) stays seamless. */
@Composable
private fun ContinuousPcb(pagerState: PagerState, destCount: Int) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val pageW = maxWidth
        Row(
            modifier = Modifier
                .fillMaxHeight()
                .width(pageW * (destCount + 1))
                .offset {
                    val scroll = pagerState.currentPage + pagerState.currentPageOffsetFraction
                    val pos = ((scroll % destCount) + destCount) % destCount
                    IntOffset((-(pageW.toPx() * pos)).roundToInt(), 0)
                },
        ) {
            for (slot in 0..destCount) {
                PcbField(
                    modifier = Modifier.width(pageW).fillMaxHeight(),
                    variant = slot % destCount,
                )
            }
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
    onMeteorCalendarSelected: () -> Unit,
    onStarFocusSelected: () -> Unit,
    onPhotoTransferSelected: () -> Unit,
    onTestCameraSelected: () -> Unit,
    onAircraftWatchSelected: () -> Unit,
    onNdCalcSelected: () -> Unit,
    onDofCalcSelected: () -> Unit,
    onDiagnosticsSelected: () -> Unit,
    onUserModeRun: (com.ehrocha.pulsar.model.UserMode) -> Unit,
    onIntervalometer2Selected: () -> Unit,
    onAstroMode2Selected: () -> Unit,
    onTimelapseSelected: () -> Unit,
    onStarTrailsSelected: () -> Unit = {},
) {
    when (page) {
        DEST_DASHBOARD -> {
            DashboardScreen(dashboardManager = vm.dashboardManager)
        }
        DEST_TRIGGER -> {
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
                        launcherItem(R.string.mode_star_trails, Icons.Default.AutoAwesome,
                            enabled = !bulbBlocked) { onStarTrailsSelected() },
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
                    // Custom — multi-step flows authored by the user.
                    val customTiles = listOf(
                        launcherItem(R.string.mode_custom_flow, Icons.AutoMirrored.Filled.ViewList,
                            enabled = !bulbBlocked) { onCustomFlowSelected() },
                    )
                    // Every trigger tile records itself as last-used so the
                    // "Continue" row can re-open it next visit.
                    fun rec(items: List<LauncherItem>) = items.map { item ->
                        item.copy(onClick = {
                            vm.recordTriggerUsed(item.key)
                            item.onClick()
                        })
                    }
                    val bulbR = rec(bulbTiles)
                    val standardR = rec(standardTiles)
                    val customR = rec(customTiles)
                    val lastKey by vm.lastTriggerKey.collectAsState()
                    val lastTile = (bulbR + standardR + customR)
                        .find { it.key == lastKey && it.enabled }
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 16.dp)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.Top),
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
                        if (lastTile != null) {
                            ResumeLastRow(lastTile)
                        }
                        // Filter chips removed (v0.416 design review): three
                        // always-visible sections need no filtering ceremony.
                        SectionContainer(title = stringResource(R.string.trigger_section_bulb)) {
                            SectionGrid(bulbR)
                        }
                        SectionContainer(title = stringResource(R.string.trigger_section_standard)) {
                            SectionGrid(standardR)
                        }
                        SectionContainer(title = stringResource(R.string.trigger_section_custom)) {
                            SectionGrid(customR)
                        }
                        Spacer(Modifier.height(8.dp))
                    }
                }
                DEST_TOOLS -> {
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
                    // Photo transfer needs a content-capable transport: CCAPI
                    // (always), or a PTP wire that advertises object ops. BLE /
                    // simulator can't move image data, so the tile greys out.
                    val photoTransferEnabled = canonOn ||
                        ptpTx?.supportsContentTransfer == true ||
                        ptpIpTx?.supportsContentTransfer == true
                    // Grouped like the Trigger page — the flat grid had
                    // become a junk drawer (audit P1-8). Astro planning,
                    // pure-math field calculators, and spotting are
                    // different jobs.
                    val astroTools = listOf(
                        launcherItem(R.string.mode_planner, Icons.Default.DateRange) {
                            onPlannerSelected()
                        },
                        launcherItem(R.string.mode_alignment, Icons.Default.Explore) {
                            onAlignmentSelected()
                        },
                        launcherItem(R.string.mode_whats_up, Icons.Default.Visibility) {
                            onWhatsUpSelected()
                        },
                        launcherItem(R.string.mode_meteor_calendar, Icons.Default.AutoAwesome) {
                            onMeteorCalendarSelected()
                        },
                        // CCAPI or PTP only; greyed out when on BLE / simulator
                        // so its absence is discoverable without breaking layout.
                        launcherItem(
                            R.string.mode_star_focus,
                            Icons.Default.Star,
                            enabled = starFocusEnabled,
                        ) { onStarFocusSelected() },
                        // Pull photos off the card — CCAPI / USB-PTP / PTP-IP.
                        launcherItem(
                            R.string.photo_transfer_title,
                            Icons.Default.PhotoLibrary,
                            enabled = photoTransferEnabled,
                        ) { onPhotoTransferSelected() },
                    )
                    // Pure-math field calculators — no transport needed.
                    val fieldTools = listOf(
                        launcherItem(
                            R.string.nd_calc_title,
                            Icons.Default.FilterBAndW,
                        ) { onNdCalcSelected() },
                        launcherItem(
                            R.string.dof_calc_title,
                            Icons.Default.CenterFocusStrong,
                        ) { onDofCalcSelected() },
                    )
                    // No transport dependency — Aircraft Watch only needs
                    // the planner location + network. Useful pre-connect
                    // (decide whether to wait for the next pass) and
                    // during long bulb runs (trail interference).
                    val spottingTools = listOf(
                        launcherItem(
                            R.string.aircraft_watch_title,
                            Icons.Default.FlightTakeoff,
                        ) { onAircraftWatchSelected() },
                    )
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 16.dp)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.Top),
                    ) {
                        SectionContainer(title = stringResource(R.string.tools_section_astro)) {
                            SectionGrid(astroTools)
                        }
                        SectionContainer(title = stringResource(R.string.tools_section_field)) {
                            SectionGrid(fieldTools)
                        }
                        SectionContainer(title = stringResource(R.string.tools_section_spotting)) {
                            SectionGrid(spottingTools)
                        }
                        Spacer(Modifier.height(8.dp))
                    }
                }
            }
}

const val DEST_DASHBOARD = 0
const val DEST_TRIGGER = 1
const val DEST_TOOLS = 2


@Composable
private fun ResumeLastRow(tile: LauncherItem) {
    Surface(
        onClick = tile.onClick,
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                tile.icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            Text(
                stringResource(R.string.trigger_resume_last, tile.label),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.weight(1f),
            )
            Icon(
                Icons.Default.PlayArrow,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSecondaryContainer,
            )
        }
    }
}

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
    // Resource ENTRY NAME, not the int — R values shuffle between builds
    // and this key is persisted for the Trigger "Continue" row.
    key = "res:" + androidx.compose.ui.platform.LocalContext.current.resources
        .getResourceEntryName(labelRes),
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
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items.chunked(3).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
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
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (reconnecting) {
                com.ehrocha.pulsar.ui.components.SignalSweep(
                    modifier = Modifier.size(32.dp, 12.dp),
                    color = onContainer,
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
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (reconnecting) {
                com.ehrocha.pulsar.ui.components.SignalSweep(
                    modifier = Modifier.size(32.dp, 12.dp),
                    color = onContainer,
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
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (reconnecting) {
                com.ehrocha.pulsar.ui.components.SignalSweep(
                    modifier = Modifier.size(32.dp, 12.dp),
                    color = onContainer,
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

    val contentAlpha = if (enabled) 1f else 0.35f
    val legColor = com.ehrocha.pulsar.ui.theme.PulsarTheme.colors.liveStart
        .copy(alpha = if (enabled) 0.5f else 0.25f)
    // A stable pseudo part-number per tile — silkscreen flavour.
    val ref = remember(label) { "U%02d".format(abs(label.hashCode()) % 100) }
    val legLen = 6.dp

    // Each tile is a little IC soldered to the board: carbon body with side
    // legs, a pin-1 dot and a part-number, the icon + label as its printing.
    // Fixed height so every chip is the same size regardless of label lines.
    Box(modifier = modifier.fillMaxWidth().height(110.dp).scale(scale)) {
        // Side legs, in the margins either side of the chip body.
        Canvas(Modifier.matchParentSize()) {
            val ll = legLen.toPx()
            val lt = 2.dp.toPx()
            val n = 4
            for (k in 0 until n) {
                val y = size.height * (k + 1) / (n + 1)
                drawRect(legColor, topLeft = Offset(0f, y - lt / 2), size = Size(ll, lt))
                drawRect(legColor, topLeft = Offset(size.width - ll, y - lt / 2), size = Size(ll, lt))
            }
        }
        Surface(
            onClick = onClick,
            enabled = enabled,
            interactionSource = interactionSource,
            shape = RoundedCornerShape(8.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)),
            modifier = Modifier.fillMaxSize().padding(horizontal = legLen),
        ) {
            Box(Modifier.fillMaxSize()) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 14.dp),
                ) {
                    // SIGNAL press feedback: the icon jumps to the live magenta.
                    Icon(
                        icon,
                        contentDescription = null,
                        tint = (if (pressed && enabled)
                            com.ehrocha.pulsar.ui.theme.PulsarTheme.colors.liveEnd
                        else MaterialTheme.colorScheme.primary).copy(alpha = contentAlpha),
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
                // pin-1 dot
                Box(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(6.dp)
                        .size(4.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f * contentAlpha)),
                )
                // part-number silkscreen
                Text(
                    ref,
                    fontFamily = Mono,
                    fontSize = 8.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f * contentAlpha),
                    modifier = Modifier.align(Alignment.BottomEnd).padding(horizontal = 6.dp, vertical = 4.dp),
                )
            }
        }
    }
}
