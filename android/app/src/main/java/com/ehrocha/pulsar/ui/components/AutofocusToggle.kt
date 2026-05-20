/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Copyright (C) 2026 Pulsar Trigger contributors
 */

package com.ehrocha.pulsar.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ehrocha.pulsar.R

/**
 * Per-shot autofocus toggle for the Canon transports (CCAPI and PTP). When
 * on, Pulsar instructs the camera to autofocus before each exposure. When
 * off, the camera shoots at the current focus position without attempting
 * to refocus. For bulb-based astro runs you almost always want this off;
 * for daylight Timelapse runs `on` is usually right.
 *
 * Wire mapping:
 *  - CCAPI: `af: true|false` field on the `/shutterbutton` payload.
 *  - PTP: Canon `RemoteReleaseOn` mode parameter — mode 3 (full press + AF)
 *    vs mode 2 (full press, no AF). The ESP32 path doesn't get a say (the
 *    body decides based on its own AF mode + the lens AF/MF switch).
 *
 * Caller should gate on `onCanon || onPtp` and skip rendering on the BLE /
 * simulator paths where the toggle wouldn't change anything.
 */
@Composable
fun AutofocusToggle(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = RoundedCornerShape(12.dp),
        modifier = modifier,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.ccapi_af_toggle_label),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    stringResource(
                        if (checked) R.string.ccapi_af_toggle_on_hint
                        else R.string.ccapi_af_toggle_off_hint
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange,
                enabled = enabled,
            )
        }
    }
}
