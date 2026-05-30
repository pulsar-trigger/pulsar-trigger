/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Copyright (C) 2026 Pulsar Trigger contributors
 */

package com.ehrocha.pulsar.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.ehrocha.pulsar.ui.theme.OnWarningContainer
import com.ehrocha.pulsar.ui.theme.WarningContainer

/** Fixed slot height for the caution strip. Reserved whether or not a
 *  warning is showing, so the editor below doesn't jump when state flips. */
private val WarningSlotHeight = 56.dp

/**
 * In-content caution strip shown above the wizard's value editor.
 *
 * Yellow (cream surface + dark-amber icon/text) signals "you can still
 * proceed, but heads-up" — e.g. an interval the camera won't keep up with,
 * or a sub-second host-timed bulb. Not for "set X to start" (those stay in
 * the bottom bar's hint slot), and not for hard blockers (which would be
 * error red, but we don't currently have one).
 *
 * **Always renders a slot of the same height**, even when [text] is null.
 * That way the editor below sits at the same y-coordinate regardless of
 * whether a warning is showing — toggling the warning doesn't shift the
 * other UI elements.
 */
@Composable
fun WizardWarning(text: String?, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(WarningSlotHeight),
        contentAlignment = Alignment.Center,
    ) {
        if (text != null) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = WarningContainer,
                modifier = Modifier.fillMaxSize(),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Icon(
                        Icons.Default.Warning,
                        contentDescription = null,
                        tint = OnWarningContainer,
                        modifier = Modifier.size(20.dp),
                    )
                    Text(
                        text = text,
                        style = MaterialTheme.typography.bodySmall,
                        color = OnWarningContainer,
                    )
                }
            }
        }
    }
}
