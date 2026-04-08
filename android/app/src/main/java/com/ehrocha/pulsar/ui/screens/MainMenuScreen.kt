/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Copyright (C) 2026 Pulsar Trigger contributors
 */

package com.ehrocha.pulsar.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stars
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.NightsStay
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ehrocha.pulsar.R
import com.ehrocha.pulsar.model.FlowStepType
import com.ehrocha.pulsar.viewmodel.PulsarViewModel
import kotlinx.coroutines.launch

@Composable
fun MainMenuScreen(
    vm: PulsarViewModel,
    onQuickFlow: (FlowStepType) -> Unit,
    onManualSelected: () -> Unit,
    onCustomFlowSelected: () -> Unit = {},
    onDashboardSelected: () -> Unit = {},
    onPlannerSelected: () -> Unit = {},
    onAlignmentSelected: () -> Unit = {},
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
    val pagerState = rememberPagerState(pageCount = { tabs.size })
    val scope = rememberCoroutineScope()

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
            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                when (page) {
                    0 -> { // Trigger
                        MenuCard(
                            title = stringResource(R.string.mode_intervalometer),
                            description = stringResource(R.string.mode_intervalometer_desc),
                            icon = Icons.Default.Timer,
                            onClick = { onQuickFlow(FlowStepType.INTERVALOMETER) },
                        )
                        MenuCard(
                            title = stringResource(R.string.mode_astro),
                            description = stringResource(R.string.mode_astro_desc),
                            icon = Icons.Default.Stars,
                            onClick = { onQuickFlow(FlowStepType.ASTRO) },
                        )
                        MenuCard(
                            title = stringResource(R.string.mode_manual),
                            description = stringResource(R.string.mode_manual_desc),
                            icon = Icons.Default.TouchApp,
                            onClick = onManualSelected,
                        )
                        MenuCard(
                            title = stringResource(R.string.mode_custom_flow),
                            description = stringResource(R.string.mode_custom_flow_desc),
                            icon = Icons.AutoMirrored.Filled.ViewList,
                            onClick = onCustomFlowSelected,
                        )
                    }
                    1 -> { // Tools
                        MenuCard(
                            title = stringResource(R.string.mode_astro_dashboard),
                            description = stringResource(R.string.mode_astro_dashboard_desc),
                            icon = Icons.Default.NightsStay,
                            onClick = onDashboardSelected,
                        )
                        MenuCard(
                            title = stringResource(R.string.mode_planner),
                            description = stringResource(R.string.mode_planner_desc),
                            icon = Icons.Default.DateRange,
                            onClick = onPlannerSelected,
                        )
                        MenuCard(
                            title = stringResource(R.string.mode_alignment),
                            description = stringResource(R.string.mode_alignment_desc),
                            icon = Icons.Default.Explore,
                            onClick = onAlignmentSelected,
                        )
                    }
                }
            }
        }

        TextButton(
            onClick = { vm.disconnect() },
            modifier = Modifier.fillMaxWidth(),
        ) { Text(stringResource(R.string.disconnect)) }
    }
}

@Composable
private fun MenuCard(
    title: String,
    description: String,
    icon: ImageVector,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 2.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(20.dp),
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(28.dp),
            )
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(8.dp))
            Icon(
                Icons.AutoMirrored.Filled.ArrowForward,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}
