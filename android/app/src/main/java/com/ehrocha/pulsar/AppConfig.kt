/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Copyright (C) 2026 Pulsar Trigger contributors
 */

package com.ehrocha.pulsar

/**
 * Centralised application configuration.
 * Every magic number, default value, and range limit lives here.
 */
object AppConfig {

    // ── GitHub ───────────────────────────────────────────────────────────
    const val GITHUB_REPO = "pulsar-trigger/pulsar-trigger"

    // ── Intervalometer defaults ──────────────────────────────────────────
    const val DEFAULT_INTERVAL_MS = 5000L
    const val DEFAULT_EXPOSURE_MS = 200L
    const val DEFAULT_SHOT_COUNT = 1
    const val DEFAULT_DELAY_MS = 0L
    const val DEFAULT_MAX_SHOTS = 100

    // ── Intervalometer range limits ──────────────────────────────────────
    const val MIN_INTERVAL_MS = 500L
    const val MIN_EXPOSURE_MS = 50L
    const val MIN_SHOT_COUNT = 1
    const val MIN_DEFAULT_EXPOSURE_MS = 1000L
    const val MIN_MAX_SHOTS = 10
    const val MAX_MAX_SHOTS = 100

    // ── Astro mode defaults ─────────────────────────────────────────────
    const val DEFAULT_FOCAL_LENGTH = 24
    const val DEFAULT_CROP_FACTOR = 1.0f
    const val DEFAULT_RULE_DIVISOR = 500
    const val DEFAULT_ASTRO_DELAY_MS = 5000L
    const val DEFAULT_ASTRO_GAP_MS = 2000L
    const val MIN_ASTRO_EXPOSURE_MS = 100L
    const val MIN_ASTRO_GAP_MS = 500L

    // ── Focal length range ──────────────────────────────────────────────
    const val MIN_FOCAL_LENGTH = 8
    const val MAX_FOCAL_LENGTH = 600
    val FOCAL_LENGTH_PRESETS = listOf(14, 24, 35, 50, 85, 135, 200, 400, 600)

    // ── GPIO defaults ───────────────────────────────────────────────────
    const val DEFAULT_PIN_SHUTTER = 25
    const val DEFAULT_PIN_FOCUS = 26
    val SAFE_OUTPUT_PINS = listOf(4, 13, 14, 16, 17, 18, 19, 21, 22, 23, 25, 26, 27)

    // ── BLE ─────────────────────────────────────────────────────────────
    const val BLE_FRAME_SIZE = 20
    const val BLE_PAYLOAD_MAX = 19
    const val BLE_DEVICE_NAME_MAX = 12
    const val BLE_OTA_MTU = 517
    const val BLE_DEFAULT_MTU = 23
    const val BLE_MIN_OTA_CHUNK = 20
    const val BLE_CONNECT_RETRIES = 3
    const val BLE_RETRY_DELAY_MS = 200
    const val OTA_CHUNK_DELAY_MS = 10L

    // ── Network ─────────────────────────────────────────────────────────
    const val API_CONNECT_TIMEOUT_MS = 10_000
    const val API_READ_TIMEOUT_MS = 10_000
    const val HOURLY_FORECAST_CAP = 12

    // ── Dashboard / astro calculator ────────────────────────────────────
    const val MOON_GOOD_ASTRO_THRESHOLD = 25.0
    const val GC_ALTITUDE_THRESHOLD = 5.0
    const val ASTRONOMICAL_TWILIGHT_DEG = -18.0
    const val MW_SCAN_STEP_MINUTES = 10.0
    const val DARK_WINDOW_OFFSET_HOURS = 1.0
    const val PRECIPITATION_CLEAR_THRESHOLD = 0.1
    const val MAX_PHOTO_WINDOWS = 3
    const val PHOTO_WINDOW_EXCELLENT = 80
    const val PHOTO_WINDOW_GOOD = 50

    // ── Simulator ───────────────────────────────────────────────────────
    const val SIMULATOR_BATTERY_PCT = 85
}
