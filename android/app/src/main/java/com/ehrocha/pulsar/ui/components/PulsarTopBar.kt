/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Copyright (C) 2026 Pulsar Trigger contributors
 */

package com.ehrocha.pulsar.ui.components

import androidx.compose.foundation.layout.RowScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RichTooltip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.res.stringResource
import com.ehrocha.pulsar.R
import kotlinx.coroutines.launch

/**
 * Single source of truth for every back-from-sub-screen top bar. Material3
 * [TopAppBar] with a back arrow on the left, title centred-left, optional
 * help-tooltip + caller-supplied actions on the right. Use this on every
 * screen that isn't the persistent main bar (battery / signal / latency).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PulsarTopBar(
    title: String,
    onBack: () -> Unit,
    helpText: String? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    TopAppBar(
        title = { Text(title) },
        navigationIcon = {
            IconButton(onClick = onBack) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.back),
                )
            }
        },
        actions = {
            if (helpText != null) HelpTooltipAction(title = title, helpText = helpText)
            actions()
        },
        colors = TopAppBarDefaults.topAppBarColors(),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HelpTooltipAction(title: String, helpText: String) {
    val tooltipState = rememberTooltipState(isPersistent = true)
    val scope = rememberCoroutineScope()
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
            )
        }
    }
}
