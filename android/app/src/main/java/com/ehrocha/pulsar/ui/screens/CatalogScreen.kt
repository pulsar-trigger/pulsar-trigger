/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Copyright (C) 2026 Pulsar Trigger contributors
 */

package com.ehrocha.pulsar.ui.screens

import android.text.format.DateUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ehrocha.pulsar.R
import com.ehrocha.pulsar.catalog.CatalogInstallResult
import com.ehrocha.pulsar.model.CatalogEntry
import com.ehrocha.pulsar.model.UserMode
import com.ehrocha.pulsar.ui.components.LocalSnackbarHost
import com.ehrocha.pulsar.viewmodel.PulsarViewModel
import kotlinx.coroutines.launch

/**
 * The Library — an apt-style browser over the network preset/flow catalog.
 * "Update" pulls the index; each entry installs on demand (fetched + sanitized
 * by [com.ehrocha.pulsar.catalog.CatalogManager]); installed/update badges come
 * from the local registry.
 */
@Composable
fun CatalogScreen(vm: PulsarViewModel, onBack: () -> Unit) {
    val state by vm.catalogManager.state.collectAsState()
    val scope = rememberCoroutineScope()
    val snackHost = LocalSnackbarHost.current
    val context = LocalContext.current
    val limitMsg = stringResource(R.string.preset_limit, UserMode.MAX_USER_MODES)
    val flowsLabel = stringResource(R.string.catalog_cat_flows)
    // Installed / update state derives from the actual stores (catalogId match),
    // so deleting an imported preset/flow self-corrects — no separate registry.
    val userModes by vm.userModes.collectAsState()
    val savedFlows by vm.savedFlows.collectAsState()
    fun installedVersionOf(entry: CatalogEntry): Int? =
        if (entry.isMode) userModes.firstOrNull { it.catalogId == entry.id }?.let { it.catalogVersion ?: 1 }
        else savedFlows.firstOrNull { it.catalogId == entry.id }?.let { it.catalogVersion ?: 1 }

    // Auto-refresh on open when the cache is missing or stale (>12 h) — NOT on
    // every app launch (network-only feature; don't pay the cost unless the
    // user actually opens the Library).
    LaunchedEffect(Unit) {
        val stale = state.lastUpdatedMs?.let {
            System.currentTimeMillis() - it > 12 * 3_600_000L
        } ?: true
        if (stale && !state.loading) vm.catalogManager.refresh()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
            }
            Spacer(Modifier.width(4.dp))
            Text(
                stringResource(R.string.catalog_title),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.weight(1f))
            TextButton(
                onClick = { scope.launch { vm.catalogManager.refresh() } },
                enabled = !state.loading,
            ) {
                if (state.loading) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                } else {
                    Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                }
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.catalog_update))
            }
        }

        Text(
            text = when {
                state.error != null -> stringResource(R.string.catalog_error, state.error!!)
                state.lastUpdatedMs != null -> stringResource(
                    R.string.catalog_updated,
                    DateUtils.getRelativeTimeSpanString(state.lastUpdatedMs!!).toString(),
                )
                else -> stringResource(R.string.catalog_never)
            },
            style = MaterialTheme.typography.bodySmall,
            color = if (state.error != null) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 4.dp, bottom = 12.dp),
        )

        if (state.entries.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    stringResource(if (state.loading) R.string.catalog_loading else R.string.catalog_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // Flows are their own category; mode presets group by mode.
                val grouped = state.entries.groupBy {
                    if (it.isFlow) flowsLabel else prettyMode(it.mode)
                }
                // Mode-preset categories first (alphabetical), Flows last.
                val keys = grouped.keys.filter { it != flowsLabel }.sorted() +
                    grouped.keys.filter { it == flowsLabel }
                keys.forEach { key ->
                    val entries = grouped.getValue(key)
                    item(key = "hdr-$key") {
                        Text(
                            key,
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(top = 8.dp, start = 4.dp),
                        )
                    }
                    items(entries, key = { it.id }) { entry ->
                        CatalogCard(
                            entry = entry,
                            installedVersion = installedVersionOf(entry),
                            onInstall = {
                                scope.launch {
                                    val msg = when (val r = vm.installCatalogEntry(entry)) {
                                        is CatalogInstallResult.Ok ->
                                            context.getString(R.string.catalog_installed_toast, entry.name)
                                        is CatalogInstallResult.LimitReached -> limitMsg
                                        is CatalogInstallResult.Error ->
                                            context.getString(R.string.catalog_error, r.message)
                                    }
                                    snackHost.showSnackbar(msg)
                                }
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CatalogCard(
    entry: CatalogEntry,
    installedVersion: Int?,
    onInstall: () -> Unit,
) {
    val updatable = installedVersion != null && installedVersion < entry.version
    val installed = installedVersion != null && !updatable
    // Signal-lock accent: lit when installed, caution on update, dim otherwise.
    val accent = when {
        updatable -> MaterialTheme.colorScheme.tertiary
        installed -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.outlineVariant
    }

    Surface(
        shape = RoundedCornerShape(12.dp),
        tonalElevation = if (installed || updatable) 2.dp else 1.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.height(IntrinsicSize.Min),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.width(3.dp).fillMaxHeight().background(accent))
            Row(
                modifier = Modifier.weight(1f).padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    entry.name,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    stringResource(if (entry.isFlow) R.string.catalog_kind_flow else R.string.catalog_kind_preset),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (entry.description.isNotEmpty()) {
                    Spacer(Modifier.size(4.dp))
                    Text(
                        entry.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            when {
                updatable -> FilledTonalButton(onClick = onInstall) {
                    Text(stringResource(R.string.catalog_update))
                }
                installed -> Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.CheckCircle,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        stringResource(R.string.catalog_installed),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                else -> FilledTonalButton(onClick = onInstall) {
                    Text(stringResource(R.string.catalog_install))
                }
            }
            }
        }
    }
}

/** "DARK_FRAME" → "Dark Frame", for the group headers. */
private fun prettyMode(mode: String): String =
    mode.split('_').joinToString(" ") { w ->
        w.lowercase().replaceFirstChar { it.uppercase() }
    }
