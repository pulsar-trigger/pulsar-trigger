/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Copyright (C) 2026 Pulsar Trigger contributors
 */

package com.ehrocha.pulsar.ui.components

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ehrocha.pulsar.ui.theme.OnWarningContainer
import com.ehrocha.pulsar.ui.theme.WarningContainer

/** Full slot height when a warning is showing. Sized for **3 lines of
 *  body-small text + 10 dp top/bottom padding** — covers the English copy
 *  (~117 chars ≈ 3 lines on a 360 dp phone) and the longer German / French
 *  translations (~140 chars). Text is capped at maxLines = 3 with ellipsis
 *  so a future translation that runs even longer can't push past. */
private val WarningSlotHeight = 88.dp

/** Vertical breathing room above / below the slot when a warning is showing.
 *  Animates with the slot itself so the gap also collapses to zero when no
 *  warning is present — caller passes horizontal padding only. */
private val WarningSlotMargin = 8.dp

/** How long to animate the slot's height when a warning toggles. Short
 *  enough that the editor below settles before the user reaches for it. */
private val SlotAnimationMs = 220

/**
 * In-content caution strip shown above the wizard's value editor.
 *
 * Yellow (cream surface + dark-amber icon/text) signals "you can still
 * proceed, but heads-up" — e.g. an interval the camera won't keep up with,
 * or a sub-second host-timed bulb. Not for "set X to start" (those stay in
 * the bottom bar's hint slot), and not for hard blockers (which would be
 * error red, but we don't currently have one).
 *
 * **Slot height animates** between 0 (no warning) and [WarningSlotHeight]
 * (warning showing). When a warning toggles, the editor below slides
 * smoothly into / out of the reclaimed space — the user perceives it as a
 * UI state change, not an abrupt jump. When no warning is showing the slot
 * is genuinely 0 dp tall, so the editor sits flush at the top of its
 * panel — no wasted-space-above-the-input problem.
 */
@Composable
fun WizardWarning(text: String?, modifier: Modifier = Modifier) {
    val targetHeight =
        if (text != null) WarningSlotHeight + WarningSlotMargin * 2 else 0.dp
    val animatedHeight by animateDpAsState(
        targetValue = targetHeight,
        animationSpec = tween(durationMillis = SlotAnimationMs),
        label = "WizardWarningSlotHeight",
    )
    // Latch the text so the strip can still render its content during the
    // collapse animation (otherwise text would null out the moment a warning
    // clears and the strip would empty mid-collapse, looking abrupt). The
    // latched value updates whenever a non-null text comes in.
    val latchedText = remember(text) { text }
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(animatedHeight),
        contentAlignment = Alignment.Center,
    ) {
        if (animatedHeight > 0.dp && latchedText != null) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = WarningContainer,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(WarningSlotHeight),
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
                        text = latchedText,
                        style = MaterialTheme.typography.bodySmall,
                        color = OnWarningContainer,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}
