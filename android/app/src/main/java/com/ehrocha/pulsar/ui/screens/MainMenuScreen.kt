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
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.LensBlur
import androidx.compose.material.icons.filled.NightsStay
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stars
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.filled.Visibility
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
    initialTab: Int = 0,
    onTabChanged: (Int) -> Unit = {},
    onQuickFlow: (FlowStepType) -> Unit,
    onManualSelected: () -> Unit,
    onCustomFlowSelected: () -> Unit = {},
    onCameraSelected: () -> Unit = {},
    phoneCameraActive: Boolean = false,
    onDashboardSelected: () -> Unit = {},
    onPlannerSelected: () -> Unit = {},
    onAlignmentSelected: () -> Unit = {},
    onWhatsUpSelected: () -> Unit = {},
    onSequencesSelected: () -> Unit = {},
    onSettingsSelected: () -> Unit = {},
) {
    val fwState by vm.firmwareManager.state.collectAsState()
    val appState by vm.appUpdateManager.state.collectAsState()
    val fwRelease by vm.firmwareManager.latestRelease.collectAsState()
    val appRelease by vm.appUpdateManager.latestRelease.collectAsState()
    val hasFwUpdate = fwState == com.ehrocha.pulsar.ble.OtaState.AVAILABLE && fwRelease != null
    val hasAppUpdate = appState == com.ehrocha.pulsar.update.AppUpdateState.AVAILABLE && appRelease != null
    var bannerDismissed by remember { mutableStateOf(false) }

    val tabs = listOf(
        stringResource(R.string.tab_trigger),
        stringResource(R.string.tab_tools),
    )
    val pagerState = rememberPagerState(initialPage = initialTab, pageCount = { tabs.size })
    val scope = rememberCoroutineScope()

    LaunchedEffect(pagerState.currentPage) {
        onTabChanged(pagerState.currentPage)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {

        // ── Update banner (non-blocking, dismissible) ────────────────
        if ((hasFwUpdate || hasAppUpdate) && !bannerDismissed) {
            Surface(
                color = MaterialTheme.colorScheme.primaryContainer,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth(),
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
                0 -> {
                    val triggerItems = if (phoneCameraActive) {
                        listOf(
                            LauncherItem(R.string.mode_camera, Icons.Default.CameraAlt) {
                                onCameraSelected()
                            },
                        )
                    } else {
                        listOf(
                            LauncherItem(R.string.mode_intervalometer, Icons.Default.Timer) {
                                onQuickFlow(FlowStepType.INTERVALOMETER)
                            },
                            LauncherItem(R.string.mode_astro, Icons.Default.Stars) {
                                onQuickFlow(FlowStepType.ASTRO)
                            },
                            LauncherItem(R.string.mode_dark_frame, Icons.Default.LensBlur) {
                                onQuickFlow(FlowStepType.DARK_FRAME)
                            },
                            LauncherItem(R.string.mode_ramp, Icons.AutoMirrored.Filled.TrendingUp) {
                                onQuickFlow(FlowStepType.RAMP)
                            },
                            LauncherItem(R.string.mode_manual, Icons.Default.TouchApp) {
                                onManualSelected()
                            },
                            LauncherItem(R.string.mode_custom_flow, Icons.AutoMirrored.Filled.ViewList) {
                                onCustomFlowSelected()
                            },
                        )
                    }
                    LauncherGrid(triggerItems)
                }
                1 -> {
                    val toolItems = listOf(
                        LauncherItem(R.string.mode_astro_dashboard, Icons.Default.NightsStay) {
                            onDashboardSelected()
                        },
                        LauncherItem(R.string.mode_planner, Icons.Default.DateRange) {
                            onPlannerSelected()
                        },
                        LauncherItem(R.string.mode_alignment, Icons.Default.Explore) {
                            onAlignmentSelected()
                        },
                        LauncherItem(R.string.mode_whats_up, Icons.Default.Visibility) {
                            onWhatsUpSelected()
                        },
                        LauncherItem(R.string.mode_sequences, Icons.Default.Layers) {
                            onSequencesSelected()
                        },
                    )
                    LauncherGrid(toolItems)
                }
            }
        }

    }
}

private data class LauncherItem(
    val labelRes: Int,
    val icon: ImageVector,
    val onClick: () -> Unit,
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
        items(items, key = { it.labelRes }) { item ->
            LauncherTile(
                label = stringResource(item.labelRes),
                icon = item.icon,
                onClick = item.onClick,
            )
        }
    }
}

@Composable
private fun LauncherTile(
    label: String,
    icon: ImageVector,
    onClick: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.92f else 1f,
        animationSpec = spring(stiffness = 500f),
        label = "tileScale",
    )

    Surface(
        onClick = onClick,
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
            Icon(
                icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
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
            )
        }
    }
}
