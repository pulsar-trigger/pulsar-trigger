/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Copyright (C) 2026 Pulsar Trigger contributors
 */

package com.ehrocha.pulsar.util

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * The single owner of "keep the screen on" (audit A-H1). Screens no longer
 * touch `FLAG_KEEP_SCREEN_ON` themselves: each one [request]s it under its own
 * tag and [release]s that tag when done. The screen stays on while ANY tag is
 * held, so a screen closing can no longer drop a running flow's request (the
 * Settings, Alignment and Aircraft Watch screens used to clear the flag
 * unconditionally on dispose).
 *
 * Tags make it idempotent: requesting the same tag twice holds it once, and
 * releasing a tag that isn't held is a no-op. MainActivity applies [active]
 * to the window.
 */
class KeepScreenOnRequests {
    private val tags = mutableSetOf<String>()
    private val _active = MutableStateFlow(false)
    val active: StateFlow<Boolean> = _active

    @Synchronized
    fun request(tag: String) {
        tags += tag
        _active.value = true
    }

    @Synchronized
    fun release(tag: String) {
        tags -= tag
        _active.value = tags.isNotEmpty()
    }

    @Synchronized
    fun isHeld(tag: String): Boolean = tag in tags
}
