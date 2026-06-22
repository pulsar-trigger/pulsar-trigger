/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Copyright (C) 2026 Pulsar Trigger contributors
 */

package com.ehrocha.pulsar.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import com.ehrocha.pulsar.ui.theme.Display
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ehrocha.pulsar.AppConfig
import com.ehrocha.pulsar.R

import com.ehrocha.pulsar.astro.*
import androidx.glance.appwidget.updateAll
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    dashboardManager: AstroDashboardManager,
    recentSessions: List<com.ehrocha.pulsar.model.ShotLogEntry> = emptyList(),
    onSessionHistorySelected: () -> Unit = {},
) {
    val state by dashboardManager.state.collectAsState()
    val scope = rememberCoroutineScope()
    var showDatePicker by remember { mutableStateOf(false) }
    var showDialHelp by remember { mutableStateOf(false) }
    var isRefreshing by remember { mutableStateOf(false) }
    val context = androidx.compose.ui.platform.LocalContext.current

    // The observing night in progress — rolls over at dawn (sunrise from the
    // loaded sun data, else ~6am), NOT midnight. So at 1am you still land on
    // tonight's session, not tomorrow. Computed once on entry.
    val observingNight = remember {
        val sunriseHour = com.ehrocha.pulsar.astro.AstroCalculator
            .parseIsoHour(dashboardManager.state.value.sun?.sunrise) ?: 6.0
        val now = LocalTime.now()
        val nowHour = now.hour + now.minute / 60.0
        if (nowHour < sunriseHour) LocalDate.now().minusDays(1) else LocalDate.now()
    }

    LaunchedEffect(Unit) {
        dashboardManager.refresh(observingNight)
    }

    // Whenever the in-app dashboard has a non-empty state, mirror the
    // snapshot to SharedPrefs + redraw any active home-screen widgets.
    // Triggers on first paint + every refresh.
    LaunchedEffect(state.lastUpdated) {
        if (state.lastUpdated != null && state.location != null &&
            (state.moon != null || state.sun != null)) {
            com.ehrocha.pulsar.widget.DashboardSnapshotStore
                .save(context, dashboardManager.serializeState())
            try {
                com.ehrocha.pulsar.widget.DashboardWidget().updateAll(context)
            } catch (_: Throwable) {
                // No widget on a home screen — no-op.
            }
        }
    }

    // ── Date picker dialog ───────────────────────────────────────
    if (showDatePicker) {
        val datePickerState = rememberDatePickerState(
            initialSelectedDateMillis = state.selectedDate
                .atStartOfDay(java.time.ZoneId.of("UTC"))
                .toInstant().toEpochMilli()
        )
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    showDatePicker = false
                    datePickerState.selectedDateMillis?.let { millis ->
                        val picked = java.time.Instant.ofEpochMilli(millis)
                            .atZone(java.time.ZoneId.of("UTC"))
                            .toLocalDate()
                        scope.launch { dashboardManager.refresh(picked) }
                    }
                }) { Text(stringResource(R.string.ok)) }
            },
            dismissButton = {
                TextButton(onClick = { showDatePicker = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        ) {
            DatePicker(state = datePickerState)
        }
    }

    // ── How to read the dashboard / Sky Dial ─────────────────────────
    if (showDialHelp) {
        com.ehrocha.pulsar.ui.components.DetailSheet(
            onDismiss = { showDialHelp = false },
            title = {
                Text(
                    stringResource(R.string.sky_dial_help_title),
                    style = MaterialTheme.typography.titleMedium,
                )
            },
        ) {
            Text(
                stringResource(R.string.sky_dial_help_body),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
            // Horizontal swipe = previous / next night (tab-swipe is suppressed
            // on this page). Neighbours refresh in place; moon/sun recompute
            // instantly, weather catches up.
            .pointerInput(state.selectedDate) {
                var dragAccum = 0f
                detectHorizontalDragGestures(
                    onDragEnd = {
                        val threshold = 64.dp.toPx()
                        when {
                            dragAccum <= -threshold ->
                                scope.launch { dashboardManager.refresh(state.selectedDate.plusDays(1)) }
                            dragAccum >= threshold ->
                                scope.launch { dashboardManager.refresh(state.selectedDate.minusDays(1)) }
                        }
                        dragAccum = 0f
                    },
                ) { _, dragAmount -> dragAccum += dragAmount }
            },
    ) {
        // ── Location header — the place the dial is computed for (keeps
        // the city name the retired Summary card used to show). ──────────
        state.location?.cityName?.let { city ->
            Text(
                city.uppercase(),
                style = MaterialTheme.typography.labelLarge.copy(
                    fontFamily = Display,
                    letterSpacing = 1.5.sp,
                ),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
        }

        // ── Date selector + refresh on a single row ─────────────────
        // Date chip + (when not today) "Today" shortcut stretch across the
        // left; refresh sits on the right. Previously these were on two
        // rows which ate vertical space for no benefit.
        val isToday = state.selectedDate == observingNight
        // Always state the date we're looking at — even "today" spells it out so
        // there's no ambiguity about which night's conditions are shown.
        val dateStr = state.selectedDate.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM))
        val dateLabel = if (isToday) "${stringResource(R.string.date_today)} · $dateStr" else dateStr

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.secondaryContainer,
                modifier = Modifier.clickable { showDatePicker = true },
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(
                        Icons.Default.CalendarMonth,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                    Text(
                        dateLabel,
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                }
            }
            if (!isToday) {
                Spacer(Modifier.width(8.dp))
                TextButton(onClick = { scope.launch { dashboardManager.refresh(observingNight) } }) {
                    Text(stringResource(R.string.date_today))
                }
            }
            Spacer(Modifier.weight(1f))
            IconButton(onClick = { showDialHelp = true }) {
                Icon(
                    Icons.Default.HelpOutline,
                    contentDescription = stringResource(R.string.sky_dial_help_title),
                )
            }
            IconButton(onClick = { scope.launch { dashboardManager.refresh(state.selectedDate) } }) {
                Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.refresh))
            }
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
                        Icons.Default.LocationOff,
                        contentDescription = null,
                        modifier = Modifier.size(48.dp),
                        tint = MaterialTheme.colorScheme.error,
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(state.error!!, textAlign = TextAlign.Center)
                    Spacer(Modifier.height(16.dp))
                    OutlinedButton(onClick = { scope.launch { dashboardManager.refresh() } }) {
                        Text(stringResource(R.string.retry))
                    }
                }
            }
            return
        }

        PullToRefreshBox(
            isRefreshing = isRefreshing,
            onRefresh = {
                scope.launch {
                    isRefreshing = true
                    dashboardManager.refresh(state.selectedDate)
                    isRefreshing = false
                }
            },
            modifier = Modifier.fillMaxSize(),
        ) {
            AstroDashboardContent(state, Modifier.fillMaxSize()) {
                SessionHistoryCard(recentSessions, onSessionHistorySelected)
            }
        } // PullToRefreshBox
    }
}

/** Session-history "second dashboard" on the Astro tab: brief run / shot /
 *  success stats, tapping through to the full ShotLog. Reuses the shot_log_*
 *  strings + stat logic so it can't drift from the full screen. */
@Composable
private fun SessionHistoryCard(
    entries: List<com.ehrocha.pulsar.model.ShotLogEntry>,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.History, contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    stringResource(R.string.shot_log_title).uppercase(),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold, letterSpacing = 1.sp,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(12.dp))
            if (entries.isEmpty()) {
                Text(
                    stringResource(R.string.shot_log_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                val totalShots = entries.sumOf { it.completedShots }
                val totalRuns = entries.size
                val completedRuns = entries.count { it.status == com.ehrocha.pulsar.model.ShotLogStatus.COMPLETED }
                val successRate = if (totalRuns > 0) (100 * completedRuns) / totalRuns else 0
                Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    SessionStat(stringResource(R.string.shot_log_stat_runs), "$totalRuns")
                    SessionStat(stringResource(R.string.shot_log_stat_shots), "$totalShots")
                    SessionStat(stringResource(R.string.shot_log_stat_success), "$successRate%")
                }
            }
        }
    }
}

@Composable
private fun SessionStat(label: String, value: String) {
    Column {
        Text(
            value,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** The unified Sky-Dial dashboard body — the radial hero, its tap-through
 *  domain pages, and the best-windows list. Shared by the Astro tab
 *  (DashboardScreen) and the planner's SessionDetailScreen so every dial
 *  change lands in both places. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AstroDashboardContent(
    state: com.ehrocha.pulsar.astro.DashboardState,
    modifier: Modifier = Modifier,
    /** Optional extra content at the bottom of the main view (Astro tab uses it
     *  for the session-history card; the planner reuse passes nothing). */
    footer: (@Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit)? = null,
) {
    var openPage by remember { mutableStateOf<DashPage?>(null) }
    BackHandler(enabled = openPage != null) { openPage = null }
        Column(
            modifier = modifier
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // ── Secondary-page header (Phase 2) — replaces the dial when a
            // domain page is open; the gated cards below show only that
            // page's group. ─────────────────────────────────────────────
            openPage?.let { page ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    IconButton(onClick = { openPage = null }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                    Text(
                        stringResource(dashPageTitle(page)).uppercase(),
                        style = MaterialTheme.typography.titleMedium.copy(fontFamily = Display, letterSpacing = 1.sp),
                    )
                }
            }

            // ── THE SKY DIAL + readout ring — the unified hero (main view).
            // Tonight as a dusk→dawn night-clock (arc glow = shooting quality,
            // best window in the live gradient, moon + Milky-Way bands), then
            // four tap-tiles into the domain pages. Tapping the dial opens the
            // Tonight timeline. ─────────────────────────────────────────────
            if (openPage == null) {
                // Readouts now live on the dial itself as corner complications
                // (Moon · Targets · Sky · Light), each tapping to its page;
                // tapping the dial body opens the Tonight timeline.
                SkyDial(
                    state,
                    onTap = { openPage = DashPage.TIMELINE },
                    onOpenPage = { openPage = it },
                )
            }

            // ── Session-history card — a "second dashboard" below the Sky Dial
            // on the main view; taps through to the full ShotLog. ─────────────
            if (openPage == null) {
                footer?.invoke(this)
            }

            // ── Domain pages: each detail card is gated to its page; hidden
            // cards collapse to zero height, so an open page shows only its
            // group. TIMELINE also carries the CP 1919 ridgeline. ───────────
            if (openPage == DashPage.TIMELINE) TonightSignalCard(state)
            // ── Best photo windows → Tonight (timeline) page. The dial face
            // shows tonight's single headline window (below the verdict), so
            // the main view stays clean; the full ranked list lives here with
            // the ridgeline + twilight. ─────────────────────────────────────
            if (openPage == DashPage.TIMELINE) state.location?.let {
                DashCard(title = stringResource(R.string.card_best_windows), icon = Icons.Default.Schedule) {

                    // Best photo windows
                    if (state.bestWindows.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            stringResource(R.string.card_best_windows),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Bold,
                        )
                        state.bestWindows.forEach { w ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    when (w.rating) { 3 -> "⭐"; 2 -> "👍"; else -> "👌" },
                                    fontSize = 16.sp,
                                    modifier = Modifier.width(28.dp),
                                )
                                Text(
                                    "${w.startTime} – ${w.endTime}",
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.weight(1f),
                                )
                                Surface(
                                    color = when (w.rating) {
                                        3 -> com.ehrocha.pulsar.ui.theme.PulsarTheme.colors.positive; 2 -> com.ehrocha.pulsar.ui.theme.PulsarTheme.colors.positiveMuted; else -> com.ehrocha.pulsar.ui.theme.PulsarTheme.colors.caution
                                    }.copy(alpha = 0.15f),
                                    shape = RoundedCornerShape(8.dp),
                                ) {
                                    Text(
                                        text = when (w.rating) {
                                            3 -> stringResource(R.string.window_excellent)
                                            2 -> stringResource(R.string.window_good)
                                            else -> stringResource(R.string.window_fair)
                                        },
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                        color = when (w.rating) {
                                            3 -> com.ehrocha.pulsar.ui.theme.PulsarTheme.colors.positive; 2 -> com.ehrocha.pulsar.ui.theme.PulsarTheme.colors.positiveMuted; else -> com.ehrocha.pulsar.ui.theme.PulsarTheme.colors.caution
                                        },
                                    )
                                }
                            }
                        }
                    } else if (!state.loading && state.weather != null && state.sun != null) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            stringResource(R.string.window_no_clear),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            // ── Per-card error chips ────────────────────────────────
            val errors = listOfNotNull(
                state.weatherError?.let { "☁️ $it" },
                state.bortleError?.let { "💡 $it" },
            )
            if (openPage == null && errors.isNotEmpty()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    errors.forEach { msg ->
                        Surface(
                            color = com.ehrocha.pulsar.ui.theme.PulsarTheme.colors.negative.copy(alpha = 0.12f),
                            shape = RoundedCornerShape(8.dp),
                        ) {
                            Text(
                                msg,
                                style = MaterialTheme.typography.labelSmall,
                                color = com.ehrocha.pulsar.ui.theme.PulsarTheme.colors.negative,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            )
                        }
                    }
                }
            }

            // ── Sun card → Timeline ──────────────────────────────────
            if (openPage == DashPage.TIMELINE) state.sun?.let { sun ->
                DashCard(title = stringResource(R.string.card_sun), icon = Icons.Default.WbSunny, initiallyExpanded = false) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                    ) {
                        sun.sunrise?.let {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("🌅", fontSize = 32.sp)
                                Text(formatTime(it), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                Text(stringResource(R.string.label_sunrise), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        sun.sunset?.let {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("🌇", fontSize = 32.sp)
                                Text(formatTime(it), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                Text(stringResource(R.string.label_sunset), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }

            // ── Moon card → Moon page ────────────────────────────────
            if (openPage == DashPage.MOON) state.moon?.let { moon ->
                DashCard(title = stringResource(R.string.card_moon), icon = Icons.Default.NightsStay, initiallyExpanded = false) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(moon.emoji, fontSize = 48.sp)
                        Spacer(Modifier.width(16.dp))
                        Column {
                            Text(
                                moon.phaseName,
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                            )
                            Text(
                                String.format(Locale.US, stringResource(R.string.moon_illuminated), moon.illuminationPct),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Text(
                                String.format(Locale.US, stringResource(R.string.moon_age), moon.ageInDays),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        moon.rise?.let {
                            InfoChip(stringResource(R.string.label_moonrise), formatTime(it))
                        }
                        moon.set?.let {
                            InfoChip(stringResource(R.string.label_moonset), formatTime(it))
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Surface(
                        color = if (moon.goodForAstro)
                            com.ehrocha.pulsar.ui.theme.PulsarTheme.colors.positive.copy(alpha = 0.15f)
                        else
                            com.ehrocha.pulsar.ui.theme.PulsarTheme.colors.negative.copy(alpha = 0.15f),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            text = if (moon.goodForAstro)
                                stringResource(R.string.moon_good)
                            else
                                stringResource(R.string.moon_bright),
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.padding(8.dp),
                            color = if (moon.goodForAstro) com.ehrocha.pulsar.ui.theme.PulsarTheme.colors.positive else com.ehrocha.pulsar.ui.theme.PulsarTheme.colors.negative,
                        )
                    }
                }
            }

            // ── Milky Way card → Light page ──────────────────────────
            if (openPage == DashPage.LIGHT) state.milkyWay?.let { mw ->
                DashCard(title = stringResource(R.string.card_milky_way), icon = Icons.Default.AutoAwesome, initiallyExpanded = false) {
                    Surface(
                        color = if (mw.visible) com.ehrocha.pulsar.ui.theme.PulsarTheme.colors.positive.copy(alpha = 0.15f)
                                else com.ehrocha.pulsar.ui.theme.PulsarTheme.colors.negative.copy(alpha = 0.15f),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            text = when {
                                mw.visible -> stringResource(R.string.mw_visible)
                                mw.coreRise != null || mw.coreSet != null -> stringResource(R.string.mw_not_visible)
                                else -> stringResource(R.string.mw_never_rises)
                            },
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.padding(8.dp),
                            color = if (mw.visible) com.ehrocha.pulsar.ui.theme.PulsarTheme.colors.positive else com.ehrocha.pulsar.ui.theme.PulsarTheme.colors.negative,
                        )
                    }
                    if (mw.coreRise != null || mw.coreSet != null) {
                        Spacer(Modifier.height(8.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceEvenly,
                        ) {
                            mw.coreRise?.let { InfoChip(stringResource(R.string.label_core_rise), it) }
                            mw.coreSet?.let { InfoChip(stringResource(R.string.label_core_set), it) }
                        }
                    }
                    mw.darkWindow?.let { window ->
                        Spacer(Modifier.height(8.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.Center,
                        ) {
                            InfoChip(stringResource(R.string.label_dark_window), window)
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = if (mw.seasonBest) stringResource(R.string.mw_season_good)
                               else stringResource(R.string.mw_season_off),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            // ── Bortle / Light Pollution card → Light page ───────────
            if (openPage == DashPage.LIGHT) state.bortle?.let { b ->
                val bInt = b.bortleClass.toInt().coerceIn(1, 9)
                val good = bInt <= 4
                DashCard(title = stringResource(R.string.card_bortle), icon = Icons.Default.Lightbulb, initiallyExpanded = false) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("💡", fontSize = 36.sp)
                        Spacer(Modifier.width(16.dp))
                        Column {
                            Text(
                                stringResource(R.string.bortle_class, bInt),
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                            )
                            Text(
                                b.category,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                    ) {
                        InfoChip(stringResource(R.string.bortle_label_category), b.category)
                        InfoChip(stringResource(R.string.bortle_label_mw), b.milkyWayQuality)
                    }
                    Spacer(Modifier.height(8.dp))
                    Surface(
                        color = if (good) com.ehrocha.pulsar.ui.theme.PulsarTheme.colors.positive.copy(alpha = 0.15f)
                                else com.ehrocha.pulsar.ui.theme.PulsarTheme.colors.negative.copy(alpha = 0.15f),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            text = if (good) stringResource(R.string.bortle_good)
                                   else stringResource(R.string.bortle_poor),
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.padding(8.dp),
                            color = if (good) com.ehrocha.pulsar.ui.theme.PulsarTheme.colors.positive else com.ehrocha.pulsar.ui.theme.PulsarTheme.colors.negative,
                        )
                    }
                }
            }

            // ── Weather card → Sky page ──────────────────────────────
            if (openPage == DashPage.SKY) state.weather?.let { weather ->
                DashCard(title = stringResource(R.string.card_weather), icon = Icons.Default.Cloud, initiallyExpanded = false) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            weatherEmoji(weather.weatherCode),
                            fontSize = 40.sp,
                        )
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text(
                                String.format(Locale.US, "%.1f°C", weather.temperatureC),
                                style = MaterialTheme.typography.headlineSmall,
                                fontWeight = FontWeight.Bold,
                            )
                            Text(
                                weatherDescription(weather.weatherCode),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }

                    Spacer(Modifier.height(8.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                    ) {
                        WeatherStat("☁️", "${weather.cloudCoverPct}%", stringResource(R.string.weather_cloud))
                        WeatherStat("🌧️", String.format(Locale.US, "%.1f mm", weather.precipitationMm), stringResource(R.string.weather_rain))
                        WeatherStat("💧", "${weather.humidity}%", stringResource(R.string.weather_humidity))
                        WeatherStat("💨", String.format(Locale.US, "%.0f km/h", weather.windSpeedKmh), stringResource(R.string.weather_wind))
                    }

                    // Weather verdict for astronomy
                    Spacer(Modifier.height(8.dp))
                    val hasRain = weather.precipitationMm > 0.1
                    Surface(
                        color = when {
                            hasRain || weather.cloudCoverPct > AppConfig.CLOUD_COVER_PARTLY_THRESHOLD -> com.ehrocha.pulsar.ui.theme.PulsarTheme.colors.negative.copy(alpha = 0.15f)
                            weather.cloudCoverPct <= AppConfig.CLOUD_COVER_CLEAR_THRESHOLD && !hasRain -> com.ehrocha.pulsar.ui.theme.PulsarTheme.colors.positive.copy(alpha = 0.15f)
                            else -> com.ehrocha.pulsar.ui.theme.PulsarTheme.colors.caution.copy(alpha = 0.15f)
                        },
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            text = when {
                                hasRain -> stringResource(R.string.weather_rain_bad)
                                weather.cloudCoverPct <= AppConfig.CLOUD_COVER_CLEAR_THRESHOLD -> stringResource(R.string.weather_clear)
                                weather.cloudCoverPct <= AppConfig.CLOUD_COVER_PARTLY_THRESHOLD -> stringResource(R.string.weather_partly_cloudy)
                                else -> stringResource(R.string.weather_too_cloudy)
                            },
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.padding(8.dp),
                            color = when {
                                hasRain || weather.cloudCoverPct > AppConfig.CLOUD_COVER_PARTLY_THRESHOLD -> com.ehrocha.pulsar.ui.theme.PulsarTheme.colors.negative
                                weather.cloudCoverPct <= AppConfig.CLOUD_COVER_CLEAR_THRESHOLD -> com.ehrocha.pulsar.ui.theme.PulsarTheme.colors.positive
                                else -> com.ehrocha.pulsar.ui.theme.PulsarTheme.colors.caution
                            },
                        )
                    }
                }
            }

            // ── Dew point card → Sky page ────────────────────────────
            // Risk is wind- and sky-aware when weather is available: clear,
            // calm nights raise the risk the raw spread alone would miss.
            if (openPage == DashPage.SKY) state.dewPoint?.let { dew ->
                val detail = state.weather?.let { w ->
                    com.ehrocha.pulsar.astro.AstroCalculator.dewRiskDetail(
                        dew, w.windSpeedKmh, w.cloudCoverPct,
                    )
                }
                val risk = detail?.risk ?: dew.risk
                if (risk != DewRisk.NONE) {
                    val isCritical = risk == DewRisk.CRITICAL
                    val accent = if (isCritical) com.ehrocha.pulsar.ui.theme.PulsarTheme.colors.negative
                                 else com.ehrocha.pulsar.ui.theme.PulsarTheme.colors.caution
                    DashCard(
                        title = stringResource(R.string.card_dew_point),
                        icon = Icons.Default.WaterDrop,
                        initiallyExpanded = false,
                    ) {
                        Surface(
                            color = accent.copy(alpha = 0.15f),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Text(
                                    text = if (isCritical) stringResource(R.string.dew_critical)
                                           else stringResource(R.string.dew_warning),
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.Bold,
                                    color = accent,
                                )
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    stringResource(
                                        R.string.dew_detail,
                                        String.format(Locale.US, "%.1f", dew.temperatureC),
                                        String.format(Locale.US, "%.1f", dew.dewPointC),
                                        String.format(Locale.US, "%.1f", dew.spreadC),
                                    ),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                // Why — the sky/wind drivers behind the rating.
                                detail?.let { d ->
                                    Text(
                                        stringResource(
                                            R.string.dew_factors,
                                            d.cloudCoverPct,
                                            d.windSpeedKmh.toInt(),
                                        ),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    text = if (isCritical) stringResource(R.string.dew_hint_heater)
                                           else stringResource(R.string.dew_hint_watch),
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = accent,
                                )
                            }
                        }
                    }
                }
            }

            // ── Twilight timeline card → Timeline ────────────────────
            if (openPage == DashPage.TIMELINE) state.twilight?.let { tw ->
                DashCard(
                    title = stringResource(R.string.card_twilight),
                    icon = Icons.Default.Gradient,
                    initiallyExpanded = false,
                ) {
                    val phases = listOfNotNull(
                        tw.civilEnd?.let { stringResource(R.string.tw_civil_end) to it },
                        tw.nauticalEnd?.let { stringResource(R.string.tw_nautical_end) to it },
                        tw.astroEnd?.let { stringResource(R.string.tw_astro_end) to it },
                        tw.astroStart?.let { stringResource(R.string.tw_astro_start) to it },
                        tw.nauticalStart?.let { stringResource(R.string.tw_nautical_start) to it },
                        tw.civilStart?.let { stringResource(R.string.tw_civil_start) to it },
                    )
                    // Visual bar
                    val colors = listOf(
                        com.ehrocha.pulsar.ui.theme.PulsarTheme.colors.skyTwilight, // civil → nautical
                        com.ehrocha.pulsar.ui.theme.PulsarTheme.colors.skyNautical, // nautical → astro
                        com.ehrocha.pulsar.ui.theme.PulsarTheme.colors.skyDark, // full dark
                        com.ehrocha.pulsar.ui.theme.PulsarTheme.colors.skyNautical, // astro → nautical
                        com.ehrocha.pulsar.ui.theme.PulsarTheme.colors.skyTwilight, // nautical → civil
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(12.dp)
                            .clip(RoundedCornerShape(8.dp)),
                    ) {
                        colors.forEach { c ->
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxHeight()
                                    .background(c),
                            )
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    phases.forEach { (label, time) ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 2.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(
                                label,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                formatTime(time),
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Medium,
                            )
                        }
                    }
                }
            }

            // ── Golden & blue hour card → Timeline ───────────────────
            if (openPage == DashPage.TIMELINE) state.goldenBlue?.let { gb ->
                val hasAny = listOf(
                    gb.morningGolden, gb.eveningGolden,
                    gb.morningBlue, gb.eveningBlue,
                ).any { it != null }
                if (hasAny) {
                    DashCard(
                        title = stringResource(R.string.card_golden_blue),
                        icon = Icons.Default.WbSunny,
                        initiallyExpanded = false,
                    ) {
                        GoldenBlueRow(
                            emoji = "🌅",
                            label = stringResource(R.string.golden_hour),
                            morning = gb.morningGolden,
                            evening = gb.eveningGolden,
                        )
                        Spacer(Modifier.height(4.dp))
                        GoldenBlueRow(
                            emoji = "🔵",
                            label = stringResource(R.string.blue_hour),
                            morning = gb.morningBlue,
                            evening = gb.eveningBlue,
                        )
                    }
                }
            }

            // ── Planets card → Targets page ──────────────────────────
            if (openPage == DashPage.TARGETS && state.planets.isNotEmpty()) {
                DashCard(
                    title = stringResource(R.string.card_planets),
                    icon = Icons.Default.Public,
                    initiallyExpanded = false,
                ) {
                    state.planets.forEach { planet ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(planet.emoji, fontSize = 20.sp, modifier = Modifier.width(32.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    planet.name,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Bold,
                                )
                                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    planet.rise?.let {
                                        Text(
                                            "↑${formatTime(it)}",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                    planet.set?.let {
                                        Text(
                                            "↓${formatTime(it)}",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                            }
                            Text(
                                String.format(Locale.US, "%.0f°", planet.altitude),
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Medium,
                            )
                            Spacer(Modifier.width(4.dp))
                            Surface(
                                color = if (planet.visible) com.ehrocha.pulsar.ui.theme.PulsarTheme.colors.positive.copy(alpha = 0.15f)
                                        else com.ehrocha.pulsar.ui.theme.PulsarTheme.colors.negative.copy(alpha = 0.15f),
                                shape = RoundedCornerShape(8.dp),
                            ) {
                                Text(
                                    if (planet.visible) stringResource(R.string.planet_visible)
                                    else stringResource(R.string.planet_low),
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = if (planet.visible) com.ehrocha.pulsar.ui.theme.PulsarTheme.colors.positive else com.ehrocha.pulsar.ui.theme.PulsarTheme.colors.negative,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                )
                            }
                        }
                    }
                }
            }

            // ── Suggested DSO targets → Targets page ─────────────────
            // Surfaces 3–5 deep-sky targets that peak in tonight's dark
            // window above the minimum altitude. Source catalog +
            // recommender live in [com.ehrocha.pulsar.astro.DsoRecommender].
            if (openPage == DashPage.TARGETS) run {
                val loc = state.location ?: return@run
                val tw = state.twilight ?: return@run
                // Twilight times come from AstroCalculator.fmtHour() as
                // wall-clock "HH:mm" strings (already converted to the
                // user's local zone). Parse them directly — they are NOT
                // ISO timestamps, so parseIsoHour() can't handle them.
                val astroEndH = parseHourMinute(tw.astroEnd) ?: return@run
                val astroStartH = parseHourMinute(tw.astroStart) ?: return@run
                // Convert local-clock hours → UTC hours so the recommender
                // and AstroCalculator.lst() can do their math in the same
                // reference frame they expect.
                val zoneOffsetH = java.time.ZoneId.systemDefault()
                    .rules.getOffset(java.time.Instant.now()).totalSeconds / 3600.0
                val ds = (astroEndH - zoneOffsetH + 24.0) % 24.0
                val de = (astroStartH - zoneOffsetH + 24.0) % 24.0
                val recs = remember(loc, tw, state.selectedDate) {
                    com.ehrocha.pulsar.astro.DsoRecommender.recommend(
                        date = state.selectedDate,
                        latDeg = loc.latitude,
                        lonDeg = loc.longitude,
                        darkStartUtcH = ds,
                        darkEndUtcH = de,
                    )
                }
                DashCard(
                    title = stringResource(R.string.card_dso_suggestions),
                    icon = Icons.Default.Stars,
                    initiallyExpanded = false,
                ) {
                    if (recs.isEmpty()) {
                        Text(
                            stringResource(R.string.dso_none_tonight),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        recs.forEach { r -> DsoSuggestionRow(r) }
                    }
                }
            }

            // ── Hourly forecast → Sky page ───────────────────────────
            if (openPage == DashPage.SKY) state.weather?.hourlyForecast?.takeIf { it.isNotEmpty() }?.let { hours ->
                val isToday2 = state.selectedDate == LocalDate.now()
                DashCard(
                    title = if (isToday2) stringResource(R.string.card_forecast)
                            else stringResource(R.string.card_forecast_day),
                    icon = Icons.Default.Schedule,
                    initiallyExpanded = false,
                ) {
                    hours.forEach { h ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                formatTime(h.time),
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.width(52.dp),
                                fontWeight = FontWeight.Medium,
                            )
                            Text(
                                weatherEmoji(h.weatherCode),
                                modifier = Modifier.width(28.dp),
                            )
                            // Cloud bar
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .height(8.dp)
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(MaterialTheme.colorScheme.surfaceVariant),
                            ) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxHeight()
                                        .fillMaxWidth(h.cloudCoverPct / 100f)
                                        .clip(RoundedCornerShape(4.dp))
                                        .background(
                                            when {
                                                h.cloudCoverPct <= AppConfig.CLOUD_COVER_CLEAR_THRESHOLD -> com.ehrocha.pulsar.ui.theme.PulsarTheme.colors.positive
                                                h.cloudCoverPct <= AppConfig.CLOUD_COVER_PARTLY_THRESHOLD -> com.ehrocha.pulsar.ui.theme.PulsarTheme.colors.caution
                                                else -> com.ehrocha.pulsar.ui.theme.PulsarTheme.colors.negative
                                            }
                                        ),
                                )
                            }
                            Text(
                                "${h.cloudCoverPct}%",
                                style = MaterialTheme.typography.labelSmall,
                                modifier = Modifier.width(36.dp),
                                textAlign = TextAlign.End,
                            )
                            // Precipitation
                            if (h.precipitationMm > 0.0) {
                                Text(
                                    String.format(Locale.US, "%.1f", h.precipitationMm),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = com.ehrocha.pulsar.ui.theme.PulsarTheme.colors.info,
                                    modifier = Modifier.width(36.dp),
                                    textAlign = TextAlign.End,
                                )
                            } else {
                                Spacer(Modifier.width(36.dp))
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
        }
}

// ── Unified dashboard: domain pages + readout ring (Phase 2) ─────────────────

/** The secondary domain pages the dial's readout ring + tap open. */
enum class DashPage { MOON, LIGHT, SKY, TARGETS, TIMELINE }

private fun dashPageTitle(p: DashPage): Int = when (p) {
    DashPage.MOON -> R.string.dash_moon
    DashPage.LIGHT -> R.string.dash_light
    DashPage.SKY -> R.string.dash_sky
    DashPage.TARGETS -> R.string.dash_targets
    DashPage.TIMELINE -> R.string.dash_timeline
}

// ── Reusable components ──────────────────────────────────────────────────────

@Composable
internal fun DashCard(
    title: String,
    icon: ImageVector,
    @Suppress("UNUSED_PARAMETER") initiallyExpanded: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    // Cards now live on dedicated secondary pages with only a few per screen,
    // so they no longer collapse — the header is a static label + icon and the
    // content is always shown.
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            title.uppercase(),
            style = MaterialTheme.typography.labelLarge.copy(
                letterSpacing = androidx.compose.ui.unit.TextUnit(
                    1.5f, androidx.compose.ui.unit.TextUnitType.Sp,
                ),
            ),
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.weight(1f),
        )
    }
    // SIGNAL: dashboard section rules carry the pulse, same as the
    // launcher pages' SectionContainer.
    com.ehrocha.pulsar.ui.components.PulseDivider()
    Surface(
        shape = RoundedCornerShape(12.dp),
        tonalElevation = 2.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            content = content,
        )
    }
}

@Composable
internal fun InfoChip(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
internal fun WeatherStat(emoji: String, value: String, label: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(emoji, fontSize = 20.sp)
        Text(value, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
internal fun formatTime(isoTime: String): String {
    // Handles "2026-04-03T14:00" → "14:00"
    return isoTime.substringAfter("T", isoTime).take(5)
}

/** One band (golden or blue) with its morning + evening windows. Shows a
 *  "—" for whichever window doesn't occur at this latitude/date. */
@Composable
private fun GoldenBlueRow(
    emoji: String,
    label: String,
    morning: com.ehrocha.pulsar.astro.TimeWindow?,
    evening: com.ehrocha.pulsar.astro.TimeWindow?,
) {
    val morningStr = morning?.let { "${formatTime(it.start)}–${formatTime(it.end)}" } ?: "—"
    val eveningStr = evening?.let { "${formatTime(it.start)}–${formatTime(it.end)}" } ?: "—"
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(emoji, fontSize = 16.sp, modifier = Modifier.width(26.dp))
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.width(72.dp),
        )
        Column(modifier = Modifier.weight(1f)) {
            Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                Text(
                    stringResource(R.string.golden_blue_am),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(morningStr, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
            }
            Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                Text(
                    stringResource(R.string.golden_blue_pm),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(eveningStr, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
            }
        }
    }
}

internal fun abs(d: Double): Double = kotlin.math.abs(d)

@Composable
private fun DsoSuggestionRow(r: com.ehrocha.pulsar.astro.DsoRecommender.Recommendation) {
    val t = r.target
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(t.emoji, fontSize = 18.sp, modifier = Modifier.width(28.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    t.id,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    t.commonName,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                String.format(
                    Locale.US,
                    "mag %.1f · %.0f′ · %s",
                    t.magnitude,
                    t.sizeArcmin,
                    t.type.name.lowercase().replace('_', ' '),
                ),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(
                String.format(Locale.US, "%.0f°", r.peakAltitudeDeg),
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Bold,
            )
            Text(
                stringResource(R.string.dso_peak_at, formatUtcHourLocal(r.peakUtcHour)),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Parse a wall-clock "HH:mm" string (e.g. "22:30") to a decimal hour.
 *  Used for the local-time twilight strings produced by
 *  [com.ehrocha.pulsar.astro.AstroCalculator.fmtHour]. */
private fun parseHourMinute(s: String?): Double? {
    if (s.isNullOrEmpty()) return null
    val parts = s.split(":")
    val h = parts.getOrNull(0)?.toDoubleOrNull() ?: return null
    val m = parts.getOrNull(1)?.toDoubleOrNull() ?: 0.0
    return h + m / 60.0
}

/** Convert a UTC hour-of-day to local HH:mm using the device's default
 *  zone. Decimal hours from [DsoRecommender] don't carry a date — fine,
 *  we just need the wall-clock minute. */
private fun formatUtcHourLocal(utcHour: Double): String {
    val zone = java.time.ZoneId.systemDefault()
    // Pick a synthetic date so the offset is current. (Date doesn't
    // matter for the time-of-day display; minor edge near DST switch.)
    val today = java.time.LocalDate.now()
    val utc = today.atTime(utcHour.toInt(), ((utcHour % 1) * 60).toInt())
        .atZone(java.time.ZoneId.of("UTC"))
    val local = utc.withZoneSameInstant(zone)
    return "%02d:%02d".format(local.hour, local.minute)
}
