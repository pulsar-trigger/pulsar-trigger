/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Copyright (C) 2026 Pulsar Trigger contributors
 */

package com.ehrocha.pulsar.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.staticCompositionLocalOf
import com.ehrocha.pulsar.util.KeepScreenOnRequests

/** The activity's keep-screen-on owner. MainActivity provides the real one;
 *  the default is a detached holder so previews compose without a window. */
val LocalKeepScreenOn = staticCompositionLocalOf { KeepScreenOnRequests() }

/**
 * Hold the screen on under [tag] while [enabled] and this call is composed.
 * Releases only its own tag on dispose — another screen's (or a running
 * flow's) request is untouched.
 */
@Composable
fun KeepScreenOn(tag: String, enabled: Boolean = true) {
    val owner = LocalKeepScreenOn.current
    DisposableEffect(owner, tag, enabled) {
        if (enabled) owner.request(tag) else owner.release(tag)
        onDispose { owner.release(tag) }
    }
}
