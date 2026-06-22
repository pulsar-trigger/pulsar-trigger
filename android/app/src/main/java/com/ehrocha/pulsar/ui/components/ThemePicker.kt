/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Copyright (C) 2026 Pulsar Trigger contributors
 */

package com.ehrocha.pulsar.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.Nightlight
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ehrocha.pulsar.R
import com.ehrocha.pulsar.ui.theme.LocalNightMode
import com.ehrocha.pulsar.ui.theme.LocalVisualStyle
import com.ehrocha.pulsar.ui.theme.Mono
import com.ehrocha.pulsar.ui.theme.ThemeMode
import com.ehrocha.pulsar.ui.theme.VisualStyle

/**
 * Top-bar theme control: the icon reflects the current colour scheme, and a tap
 * opens a two-column menu — **colour scheme** (Light / Outdoor / Dark / Red) on
 * the left, **visual style** (Circuit / Classic / Space) on the right. Each list
 * writes its own state ([LocalNightMode] / [LocalVisualStyle]); the menu stays
 * open so both can be set in one go.
 */
@Composable
fun ThemePicker() {
    val nightMode = LocalNightMode.current
    val visualStyle = LocalVisualStyle.current
    var expanded by remember { mutableStateOf(false) }

    Box {
        IconButton(onClick = { expanded = true }) {
            // Generic "theme" icon now that this controls colour scheme AND
            // visual style — the per-mode sun/moon icons live in the left column.
            Icon(
                Icons.Default.Palette,
                contentDescription = stringResource(R.string.night_mode_toggle),
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            Row(modifier = Modifier.padding(horizontal = 4.dp)) {
                Column(modifier = Modifier.width(152.dp)) {
                    PickerHeader(stringResource(R.string.color_scheme))
                    ThemeMode.entries.forEach { mode ->
                        PickerOption(
                            label = stringResource(colorSchemeLabel(mode)),
                            icon = colorSchemeIcon(mode),
                            selected = nightMode.value == mode,
                            onClick = { nightMode.value = mode },
                        )
                    }
                }
                VerticalDivider(modifier = Modifier.height(184.dp).padding(horizontal = 2.dp))
                Column(modifier = Modifier.width(140.dp)) {
                    PickerHeader(stringResource(R.string.settings_visual_style))
                    VisualStyle.entries.forEach { vs ->
                        PickerOption(
                            label = stringResource(visualStyleLabel(vs)),
                            icon = null,
                            selected = visualStyle.value == vs,
                            onClick = { visualStyle.value = vs },
                        )
                    }
                }
            }
        }
    }
}

private fun colorSchemeIcon(m: ThemeMode): ImageVector = when (m) {
    ThemeMode.Light -> Icons.Default.LightMode
    ThemeMode.Outdoor -> Icons.Default.WbSunny
    ThemeMode.Dark -> Icons.Default.Nightlight
    ThemeMode.RedLight -> Icons.Default.Nightlight
}

private fun colorSchemeLabel(m: ThemeMode): Int = when (m) {
    ThemeMode.Light -> R.string.color_scheme_light
    ThemeMode.Outdoor -> R.string.color_scheme_outdoor
    ThemeMode.Dark -> R.string.color_scheme_dark
    ThemeMode.RedLight -> R.string.color_scheme_red
}

private fun visualStyleLabel(v: VisualStyle): Int = when (v) {
    VisualStyle.CIRCUIT -> R.string.visual_style_circuit
    VisualStyle.CLASSIC -> R.string.visual_style_classic
    VisualStyle.SPACE -> R.string.visual_style_space
}

@Composable
private fun PickerHeader(text: String) {
    Text(
        text.uppercase(),
        fontFamily = Mono,
        fontSize = 10.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 1.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 12.dp, top = 4.dp, bottom = 4.dp),
    )
}

@Composable
private fun PickerOption(label: String, icon: ImageVector?, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (icon != null) {
            Icon(
                icon, contentDescription = null, modifier = Modifier.size(16.dp),
                tint = if (selected) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            label,
            modifier = Modifier.weight(1f),
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            style = MaterialTheme.typography.bodyMedium,
        )
        if (selected) {
            Icon(
                Icons.Default.Check, contentDescription = null,
                modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary,
            )
        }
    }
}
