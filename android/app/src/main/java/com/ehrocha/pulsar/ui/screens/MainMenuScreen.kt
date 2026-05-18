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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.LensBlur
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stars
import androidx.compose.material.icons.filled.Timer
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
import com.ehrocha.pulsar.viewmodel.PulsarViewModel
import kotlinx.coroutines.launch

@Composable
fun MainMenuScreen(
    vm: PulsarViewModel,
    initialTab: Int = TAB_TRIGGER,
    onTabChanged: (Int) -> Unit = {},
    onQuickFlow: (FlowStepType) -> Unit,
    onManualSelected: () -> Unit,
    onCustomFlowSelected: () -> Unit = {},
    onPlannerSelected: () -> Unit = {},
    onAlignmentSelected: () -> Unit = {},
    onWhatsUpSelected: () -> Unit = {},
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

    LaunchedEffect(pagerState.currentPage) {
        onTabChanged(pagerState.currentPage)
    }

    // Horizontal padding lives on each page, not on the outer Column — the
    // Dashboard page wants edge-to-edge so its inner padding doesn't double.
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(vertical = 12.dp),
    ) {

        // ── Update banner (non-blocking, dismissible) ────────────────
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

        // ── Tab row ──────────────────────────────────────────────────
        TabRow(
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
            when (page) {
                TAB_DASHBOARD -> {
                    DashboardScreen(dashboardManager = vm.dashboardManager)
                }
                TAB_TRIGGER -> {
                    val userModes by vm.userModes.collectAsState()
                    val canonTransport by vm.canonTransport.collectAsState()
                    val canonReconnecting by vm.canonReconnecting.collectAsState()
                    val onCanon = canonTransport != null
                    val builtIns = listOf(
                        launcherItem(R.string.mode_intervalometer, Icons.Default.Timer) {
                            onIntervalometer2Selected()
                        },
                        launcherItem(R.string.mode_timelapse, Icons.Default.PhotoLibrary) {
                            onTimelapseSelected()
                        },
                        launcherItem(R.string.mode_astro, Icons.Default.Stars) {
                            onAstroMode2Selected()
                        },
                        launcherItem(R.string.mode_dark_frame, Icons.Default.LensBlur) {
                            onQuickFlow(FlowStepType.DARK_FRAME)
                        },
                        launcherItem(R.string.mode_ramp, Icons.AutoMirrored.Filled.TrendingUp) {
                            onQuickFlow(FlowStepType.RAMP)
                        },
                        launcherItem(R.string.mode_manual, Icons.Default.TouchApp) {
                            onManualSelected()
                        },
                        launcherItem(R.string.mode_custom_flow, Icons.AutoMirrored.Filled.ViewList) {
                            onCustomFlowSelected()
                        },
                    )
                    // Only bookmarked user modes get quick-launch tiles here.
                    // Other saved presets live in the preset picker for each mode.
                    val userTiles = userModes.filter { it.bookmarked }.map { mode ->
                        LauncherItem(
                            key = "user:${mode.id}",
                            label = mode.name,
                            icon = Icons.Default.Bookmark,
                            onClick = { onUserModeRun(mode) },
                        )
                    }
                    Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                        if (onCanon) {
                            CanonBulbBanner(reconnecting = canonReconnecting)
                            Spacer(Modifier.height(8.dp))
                        }
                        LauncherGrid(builtIns + userTiles)
                    }
                }
                TAB_TOOLS -> {
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
                    )
                    Box(modifier = Modifier.padding(horizontal = 16.dp)) {
                        LauncherGrid(toolItems)
                    }
                }
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
private fun CanonBulbBanner(reconnecting: Boolean) {
    val containerColor = if (reconnecting)
        MaterialTheme.colorScheme.errorContainer
    else MaterialTheme.colorScheme.secondaryContainer
    val onContainer = if (reconnecting)
        MaterialTheme.colorScheme.onErrorContainer
    else MaterialTheme.colorScheme.onSecondaryContainer
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
                text = stringResource(
                    if (reconnecting) R.string.canon_reconnecting_hint
                    else R.string.canon_bulb_hint
                ),
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
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed && enabled) 0.92f else 1f,
        animationSpec = spring(stiffness = 500f),
        label = "tileScale",
    )

    Surface(
        onClick = onClick,
        enabled = enabled,
        interactionSource = interactionSource,
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 2.dp,
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(0.9f)
            .scale(scale),
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.padding(8.dp),
        ) {
            val contentAlpha = if (enabled) 1f else 0.35f
            Icon(
                icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary.copy(alpha = contentAlpha),
                modifier = Modifier.size(24.dp),
            )
            Spacer(Modifier.height(6.dp))
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
