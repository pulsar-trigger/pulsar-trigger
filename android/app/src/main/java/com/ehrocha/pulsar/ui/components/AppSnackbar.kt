/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Copyright (C) 2026 Pulsar Trigger contributors
 */

package com.ehrocha.pulsar.ui.components

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.staticCompositionLocalOf
import kotlinx.coroutines.launch

/**
 * App-wide snackbar host — the single "something happened" surface from the
 * v0.411 UX audit (P0-2). Replaces the 12 ad-hoc `Toast.makeText` calls:
 * toasts ignore the app theme (a white system toast is a flashlight in
 * RedLight mode), can't be swiped away, and float over the wrong window.
 * MainActivity owns the state and renders one host above the nav stack.
 */
val LocalSnackbarHost = staticCompositionLocalOf { SnackbarHostState() }

/** Fire-and-forget message poster for click handlers and effects:
 *  `val notify = rememberSnackbarPoster(); … notify(msg)`. */
@Composable
fun rememberSnackbarPoster(): (String) -> Unit {
    val host = LocalSnackbarHost.current
    val scope = rememberCoroutineScope()
    return remember(host, scope) {
        { msg -> scope.launch { host.showSnackbar(msg) } }
    }
}
