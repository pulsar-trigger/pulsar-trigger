/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Copyright (C) 2026 Pulsar Trigger contributors
 */

package com.ehrocha.pulsar.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ehrocha.pulsar.ui.theme.LocalVisualStyle
import com.ehrocha.pulsar.ui.theme.VisualStyle

/**
 * Grouped panel used to segment a screen into visually distinct sections —
 * scan landing's Transports / Recent / Tools, the Trigger tab's Bulb /
 * Standard / Favorites / Custom, and similar.
 *
 * Same shape and surface treatment everywhere on purpose: the consistency
 * is the point. The optional [title] renders as a small uppercase label
 * above the content; items inside each section can have their own
 * sub-styling but should be visually consistent within their section.
 */
@Composable
fun SectionContainer(
    title: String? = null,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    // CIRCUIT + SPACE: transparent — the section is just a labelled region so
    // the board / starfield shows through. CLASSIC: a raised card with the
    // CP-1919 pulse-divider under the title.
    val classic = LocalVisualStyle.current.value == VisualStyle.CLASSIC
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = if (classic) MaterialTheme.colorScheme.surfaceContainerLow else Color.Transparent,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (title != null) {
                Text(
                    text = title.uppercase(),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.5.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 4.dp, top = 2.dp, bottom = 2.dp),
                )
                if (classic) {
                    PulseDivider(modifier = Modifier.padding(horizontal = 4.dp))
                }
            }
            content()
        }
    }
}
