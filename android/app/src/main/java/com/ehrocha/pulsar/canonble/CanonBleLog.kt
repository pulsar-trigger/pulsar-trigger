/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Copyright (C) 2026 Pulsar Trigger contributors
 */

package com.ehrocha.pulsar.canonble

import android.util.Log

/**
 * In-app ring buffer that mirrors the Canon BLE wire diagnostics to a buffer
 * the user can export from Tools → Collect diagnostics — so debugging the
 * connect/handshake path doesn't require `adb logcat`.
 *
 * Every Canon-BLE [Log] call goes through [d]/[w]/[i]/[e] here, which both
 * forwards to Logcat and appends a timestamped line to [buf]. [dump] returns
 * the whole buffer as text for the diagnostics file.
 */
object CanonBleLog {
    private const val MAX_LINES = 600
    private val buf = ArrayDeque<String>()
    @Volatile private var origin = System.currentTimeMillis()

    @Synchronized
    private fun add(tag: String, level: Char, msg: String) {
        val t = (System.currentTimeMillis() - origin) / 1000.0
        if (buf.size >= MAX_LINES) buf.removeFirst()
        buf.addLast("[%9.3f] %c %s: %s".format(t, level, tag, msg))
    }

    fun d(tag: String, msg: String) { Log.d(tag, msg); add(tag, 'D', msg) }
    fun w(tag: String, msg: String) { Log.w(tag, msg); add(tag, 'W', msg) }
    fun i(tag: String, msg: String) { Log.i(tag, msg); add(tag, 'I', msg) }
    fun e(tag: String, msg: String) { Log.e(tag, msg); add(tag, 'E', msg) }

    /** The captured lines as a single string (oldest first). */
    @Synchronized
    fun dump(): String = buf.joinToString("\n")

    @Synchronized
    fun clear() {
        buf.clear()
        origin = System.currentTimeMillis()
    }
}
