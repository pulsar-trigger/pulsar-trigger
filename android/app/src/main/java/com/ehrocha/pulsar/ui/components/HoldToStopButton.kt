/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Copyright (C) 2026 Pulsar Trigger contributors
 */

package com.ehrocha.pulsar.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.ehrocha.pulsar.R
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** How long Stop must be held. Long enough that a pocket or a bag can't
 *  end a night's session; short enough not to annoy a deliberate press. */
const val HOLD_TO_STOP_MS = 800

/**
 * Press-and-hold Stop (audit A-H2). A plain tap only shows "Hold to stop";
 * holding fills the button left to right and fires [onStop] with a haptic
 * when the fill completes. Releasing early drains the fill.
 *
 * Accessibility: TalkBack's double-tap ("activate") shows the hint like a tap;
 * double-tap-and-hold (long press) and the "Stop the run" custom action both
 * stop — the same deliberate-gesture bar the visual hold sets.
 */
@Composable
fun HoldToStopButton(
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
    label: String = stringResource(R.string.btn_stop),
    height: Dp = 56.dp,
    cornerRadius: Dp = 20.dp,
) {
    val currentOnStop by rememberUpdatedState(onStop)
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val fill = remember { Animatable(0f) }
    // Bumped on each early release; shows the hint for a moment.
    var hintTick by remember { mutableIntStateOf(0) }
    var showingHint by remember { mutableStateOf(false) }
    LaunchedEffect(hintTick) {
        if (hintTick == 0) return@LaunchedEffect
        showingHint = true
        delay(2_000)
        showingHint = false
    }

    val hint = stringResource(R.string.hold_to_stop_hint)
    val stopAction = stringResource(R.string.a11y_stop_run_action)
    val shape = RoundedCornerShape(cornerRadius)
    val container = MaterialTheme.colorScheme.error
    val content = MaterialTheme.colorScheme.onError

    fun fire() {
        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        currentOnStop()
    }

    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .heightIn(min = height)
            .clip(shape)
            .background(container)
            .drawBehind {
                drawRect(
                    color = content.copy(alpha = 0.32f),
                    size = Size(size.width * fill.value, size.height),
                )
            }
            .pointerInput(Unit) {
                detectTapGestures(
                    onPress = {
                        var fired = false
                        val job: Job = scope.launch {
                            fill.snapTo(0f)
                            fill.animateTo(
                                1f,
                                tween(durationMillis = HOLD_TO_STOP_MS, easing = LinearEasing),
                            )
                            fired = true
                            fire()
                        }
                        tryAwaitRelease()
                        if (!fired) {
                            job.cancel()
                            hintTick++
                            scope.launch { fill.animateTo(0f, tween(durationMillis = 150)) }
                        } else {
                            scope.launch { fill.snapTo(0f) }
                        }
                    },
                )
            }
            .clearAndSetSemantics {
                role = Role.Button
                contentDescription = label
                stateDescription = hint
                if (showingHint) liveRegion = LiveRegionMode.Polite
                onClick(label = hint) { hintTick++; true }
                onLongClick(label = stopAction) { fire(); true }
                customActions = listOf(CustomAccessibilityAction(stopAction) { fire(); true })
            }
            .padding(horizontal = 16.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            Icon(Icons.Default.Stop, contentDescription = null, tint = content)
            Spacer(Modifier.width(8.dp))
            Text(
                if (showingHint) hint else label,
                color = content,
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.labelLarge,
            )
        }
    }
}
