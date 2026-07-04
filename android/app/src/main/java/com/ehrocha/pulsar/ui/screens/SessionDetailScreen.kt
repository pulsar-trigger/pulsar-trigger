/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Copyright (C) 2026 Pulsar Trigger contributors
 */

package com.ehrocha.pulsar.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.ehrocha.pulsar.R

import com.ehrocha.pulsar.astro.*
import com.ehrocha.pulsar.planner.PlannerEvent
import com.ehrocha.pulsar.planner.PlannerManager
import com.ehrocha.pulsar.planner.PlannerSession
import kotlinx.coroutines.launch
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionDetailScreen(
    session: PlannerSession,
    event: PlannerEvent,
    plannerManager: PlannerManager,
    onBack: () -> Unit,
    /** Plan→shoot handoff: called with a prefilled Astro step (start delay =
     *  time until tonight's best window) when the user arms the window. */
    onShootWindow: ((com.ehrocha.pulsar.model.FlowStep.Astro) -> Unit)? = null,
) {
    val context = LocalContext.current
    val dashManager = remember { AstroDashboardManager(context) }
    val state by dashManager.state.collectAsState()
    val scope = rememberCoroutineScope()
    var isRefreshing by remember { mutableStateOf(false) }

    suspend fun doRefresh() {
        isRefreshing = true
        dashManager.refreshForLocation(
            session.latitude,
            session.longitude,
            session.name,
            session.date,
        )
        plannerManager.putCachedDashboard(session.id, dashManager.serializeState())
        isRefreshing = false
    }

    LaunchedEffect(session.id) {
        // Try cached data first
        val cached = plannerManager.getCachedDashboard(session.id)
        if (cached != null && dashManager.restoreState(cached)) {
            return@LaunchedEffect
        }
        doRefresh()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
    ) {
        // ── Top bar ──────────────────────────────────────────────────
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(vertical = 8.dp),
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    session.name,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    "${event.name} \u00b7 ${session.date.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.LONG))}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = {
                val geoUri = Uri.parse(
                    "geo:${session.latitude},${session.longitude}?q=${session.latitude},${session.longitude}(${Uri.encode(session.name)})"
                )
                val intent = Intent(Intent.ACTION_VIEW, geoUri)
                context.startActivity(intent)
            }) {
                Icon(Icons.Default.Navigation, contentDescription = stringResource(R.string.btn_navigate))
            }
            IconButton(onClick = {
                scope.launch { doRefresh() }
            }) {
                Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.refresh))
            }
        }

        // ── Last Updated + Pull Latest Data ──────────────────────────
        val lastUpdated = state.lastUpdated
        val isStale = lastUpdated != null && (System.currentTimeMillis() - lastUpdated) > 3_600_000L // > 1 hour
        val hasData = state.location != null

        if (hasData) {
            Surface(
                color = if (isStale) MaterialTheme.colorScheme.errorContainer
                        else MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        if (isStale) Icons.Default.Warning else Icons.Default.Schedule,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = if (isStale) MaterialTheme.colorScheme.onErrorContainer
                               else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.width(8.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = if (lastUpdated != null) {
                                stringResource(R.string.last_updated,
                                    DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
                                        .format(Date(lastUpdated)))
                            } else {
                                stringResource(R.string.assessment_unavailable)
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = if (isStale) MaterialTheme.colorScheme.onErrorContainer
                                    else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (isStale) {
                            Text(
                                stringResource(R.string.data_stale),
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                            )
                        }
                    }
                    FilledTonalButton(
                        onClick = { scope.launch { doRefresh() } },
                        enabled = !isRefreshing,
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                    ) {
                        Icon(
                            Icons.Default.Refresh,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            stringResource(R.string.pull_latest_data),
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
        }

        // ── Plan→shoot: arm tonight's best window ────────────────────
        // Only on the session's own night, with a computed best window: one
        // tap opens the Astro wizard with the start delay preloaded so the
        // run begins exactly when the window opens.
        val night = remember(state) {
            if (state.location != null) buildNightModel(state) else null
        }
        if (onShootWindow != null && night != null &&
            night.windowEndF > night.windowStartF &&
            session.date == java.time.LocalDate.now()
        ) {
            Button(
                onClick = {
                    val sunsetT = parseHm(state.sun?.sunset) ?: parseHm(state.twilight?.civilEnd)
                    val sunriseT = parseHm(state.sun?.sunrise) ?: parseHm(state.twilight?.civilStart)
                    if (sunsetT != null && sunriseT != null) {
                        var nightStart = session.date.atTime(sunsetT)
                        var nightEnd = session.date.atTime(sunriseT)
                        if (!nightEnd.isAfter(nightStart)) nightEnd = nightEnd.plusDays(1)
                        val spanMin = java.time.Duration.between(nightStart, nightEnd).toMinutes()
                        val windowStart = nightStart.plusMinutes((night.windowStartF * spanMin).toLong())
                        val delayMs = java.time.Duration
                            .between(java.time.LocalDateTime.now(), windowStart)
                            .toMillis().coerceAtLeast(0L)
                        onShootWindow(
                            com.ehrocha.pulsar.model.FlowStep.Astro(
                                focalLength = 24,
                                delayMs = delayMs,
                            ),
                        )
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Default.CameraAlt, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.planner_shoot_window, night.windowLabel))
            }
            Spacer(Modifier.height(8.dp))
        }

        if (state.loading && state.location == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator()
                    Spacer(Modifier.height(16.dp))
                    Text(stringResource(R.string.status_fetching_data))
                }
            }
            return
        }

        if (state.error != null && state.location == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Default.ErrorOutline,
                        contentDescription = null,
                        modifier = Modifier.size(48.dp),
                        tint = MaterialTheme.colorScheme.error,
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(state.error!!, textAlign = TextAlign.Center)
                    Spacer(Modifier.height(16.dp))
                    OutlinedButton(onClick = {
                        scope.launch { doRefresh() }
                    }) {
                        Text(stringResource(R.string.retry))
                    }
                }
            }
            return
        }

        PullToRefreshBox(
            isRefreshing = isRefreshing,
            onRefresh = { scope.launch { doRefresh() } },
            modifier = Modifier.fillMaxSize(),
        ) {
            AstroDashboardContent(state, Modifier.fillMaxSize())
        } // PullToRefreshBox
    }
}

/** Open-Meteo local ISO ("…THH:mm") or bare "HH:mm" → LocalTime. Mirrors the
 *  NightModel parser so the window-start arithmetic matches the strip. */
private fun parseHm(s: String?): java.time.LocalTime? {
    if (s.isNullOrEmpty()) return null
    val hhmm = s.substringAfter("T", s).take(5)
    return runCatching { java.time.LocalTime.parse(hhmm) }.getOrNull()
}
