/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Copyright (C) 2026 Pulsar Trigger contributors
 */

package com.ehrocha.pulsar.canonble

import android.util.Log

/**
 * Separate ring buffer for the GATT Explorer wizard. Kept distinct from
 * [CanonBleLog] so the RE artifact a community tester shares contains
 * only the explorer's interactions, not the surrounding Canon BLE
 * transport noise (autoconnect logs, reconnect banners, etc.).
 *
 * Pairs with the explorer screen's Share button — the dumped report
 * goes to a `FileProvider` temp file via `shareDiagnostics`.
 */
object GattExplorerLog {
    private const val MAX_LINES = 1_000
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

    @Synchronized
    fun dump(): String = buf.joinToString("\n")

    @Synchronized
    fun clear() {
        buf.clear()
        origin = System.currentTimeMillis()
    }
}
