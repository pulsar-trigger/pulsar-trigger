/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Copyright (C) 2026 Pulsar Trigger contributors
 */

package com.ehrocha.pulsar.canonble

import android.content.Context
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Persists the last [CanonBleLog] ring buffer + uncaught throwable to a file
 * in app `filesDir` so a wizard-Start crash that kills the process still
 * leaves a forensic trail. The next [PulsarViewModel.canonDiagnosticsText]
 * call inlines the file, then deletes it.
 */
object CrashPersister {
    private const val FILE = "pulsar_last_crash.txt"

    fun install(context: Context) {
        val appCtx = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                writeCrash(appCtx, thread, throwable)
            } catch (_: Throwable) {
                // Best-effort — never block the OS from finishing the death.
            }
            previous?.uncaughtException(thread, throwable)
        }
    }

    private fun writeCrash(context: Context, thread: Thread, throwable: Throwable) {
        val sw = StringWriter()
        PrintWriter(sw).use { throwable.printStackTrace(it) }
        val ts = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        val body = buildString {
            appendLine("── Last crash ($ts, thread=${thread.name}) ──")
            appendLine(sw.toString().trim())
            appendLine()
            appendLine("── CanonBleLog at time of crash ──")
            val log = CanonBleLog.dump()
            append(if (log.isBlank()) "(empty)" else log)
        }
        File(context.filesDir, FILE).writeText(body)
    }

    /** Read + delete the persisted crash report (if any). */
    fun consume(context: Context): String? {
        val f = File(context.filesDir, FILE)
        if (!f.exists()) return null
        return try {
            val text = f.readText()
            f.delete()
            text
        } catch (_: Throwable) {
            null
        }
    }
}
