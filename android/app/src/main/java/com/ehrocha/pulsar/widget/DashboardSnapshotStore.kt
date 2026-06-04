/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Copyright (C) 2026 Pulsar Trigger contributors
 */

package com.ehrocha.pulsar.widget

import android.content.Context

/** Tiny SharedPrefs-backed store for the home-screen widget. The widget
 *  host (launcher) runs in our app's process, so SharedPrefs is enough —
 *  no ContentProvider needed. We persist the full serialized
 *  [com.ehrocha.pulsar.astro.DashboardState] JSON produced by
 *  [com.ehrocha.pulsar.astro.AstroDashboardManager.serializeState] and let
 *  [com.ehrocha.pulsar.astro.AstroDashboardManager.restoreState] decode it
 *  on the widget side. */
object DashboardSnapshotStore {
    private const val PREFS = "dashboard_widget"
    private const val KEY_JSON = "snapshot_json"
    private const val KEY_UPDATED_AT = "updated_at"
    private const val KEY_BG_ALPHA = "bg_alpha"
    /** Default opacity used when the user hasn't set one yet. 1.0 = opaque. */
    private const val DEFAULT_BG_ALPHA = 1.0f
    private const val KEY_TEXT_SCALE = "text_scale"
    /** Multiplier applied to every widget font size. 1.0 = the post-v0.367
     *  baseline (which itself is +2sp over the original v0.355 sizes). */
    private const val DEFAULT_TEXT_SCALE = 1.0f
    const val TEXT_SCALE_MIN = 0.8f
    const val TEXT_SCALE_MAX = 1.5f

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun save(context: Context, json: String) {
        prefs(context).edit()
            .putString(KEY_JSON, json)
            .putLong(KEY_UPDATED_AT, System.currentTimeMillis())
            .apply()
    }

    fun load(context: Context): Snapshot? {
        val p = prefs(context)
        val json = p.getString(KEY_JSON, null) ?: return null
        return Snapshot(json = json, updatedAtMs = p.getLong(KEY_UPDATED_AT, 0L))
    }

    /** Background opacity (0.0..1.0) applied to the widget's background
     *  color. Defaults to fully opaque. */
    fun backgroundAlpha(context: Context): Float =
        prefs(context).getFloat(KEY_BG_ALPHA, DEFAULT_BG_ALPHA).coerceIn(0f, 1f)

    fun setBackgroundAlpha(context: Context, alpha: Float) {
        prefs(context).edit().putFloat(KEY_BG_ALPHA, alpha.coerceIn(0f, 1f)).apply()
    }

    /** Text-size multiplier applied to every widget font size. Clamped to
     *  [[TEXT_SCALE_MIN], [TEXT_SCALE_MAX]] to keep layout sane. */
    fun textScale(context: Context): Float =
        prefs(context).getFloat(KEY_TEXT_SCALE, DEFAULT_TEXT_SCALE)
            .coerceIn(TEXT_SCALE_MIN, TEXT_SCALE_MAX)

    fun setTextScale(context: Context, scale: Float) {
        prefs(context).edit()
            .putFloat(KEY_TEXT_SCALE, scale.coerceIn(TEXT_SCALE_MIN, TEXT_SCALE_MAX))
            .apply()
    }

    data class Snapshot(val json: String, val updatedAtMs: Long)
}
