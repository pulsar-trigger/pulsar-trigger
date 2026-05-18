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
 * Per-shot CCAPI autofocus toggle. When on, Pulsar sends `af: true` on each
 * shutter call so the camera autofocuses before the exposure. When off,
 * `af: false` — the camera shoots at the current focus position without
 * attempting to refocus. For bulb-based astro runs you almost always want
 * this off; for daylight Timelapse runs `on` is usually right.
 *
 * Only meaningful on the Canon CCAPI transport. Caller should gate on
 * `vm.canonTransport.value != null` and skip rendering otherwise — the
 * ESP32 path doesn't get a say in AF (the body decides based on its own
 * AF mode and the lens AF/MF switch).
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
