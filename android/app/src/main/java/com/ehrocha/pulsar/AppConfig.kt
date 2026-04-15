/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Copyright (C) 2026 Pulsar Trigger contributors
 */

package com.ehrocha.pulsar

/**
 * Centralised application configuration — single source of truth for every
 * factory default, range limit, and tuning constant used across the app.
 *
 * Changing a value here propagates everywhere: ViewModel init, SharedPrefs
 * fallbacks, UI coerce limits, BLE framing, API clients, and the astro
 * dashboard calculator.
 */
object AppConfig {

    // ── GitHub ───────────────────────────────────────────────────────────

    /** GitHub owner/repo used by both firmware OTA and app self-update to
     *  query the Releases API. */
    const val GITHUB_REPO = "pulsar-trigger/pulsar-trigger"
    const val GITHUB_RELEASES_URL = "https://api.github.com/repos/$GITHUB_REPO/releases"

    // ── Intervalometer defaults ──────────────────────────────────────────
    // These are the factory values applied on first launch and after a
    // "Reset defaults" action. They also serve as SharedPreferences
    // fallbacks when no persisted value exists.

    /** Time between the start of one exposure and the start of the next (ms). */
    const val DEFAULT_INTERVAL_MS = 5000L

    /** Shutter-open duration for each shot (ms). */
    const val DEFAULT_EXPOSURE_MS = 200L

    /** Number of shots in a single intervalometer run. */
    const val DEFAULT_SHOT_COUNT = 1

    /** Countdown before the first shot fires (ms). */
    const val DEFAULT_DELAY_MS = 0L

    /** Upper limit the user can set for shot count via the "Max shots" setting. */
    const val DEFAULT_MAX_SHOTS = 100

    // ── Intervalometer range limits ──────────────────────────────────────
    // Hard floors/ceilings enforced by the UI steppers and coerce calls.

    /** Shortest allowed interval between shots (ms). Prevents values that
     *  would overlap with exposure time. */
    const val MIN_INTERVAL_MS = 500L

    /** Shortest allowed exposure time (ms). Below this, most cameras cannot
     *  reliably trigger via the shutter-release cable. */
    const val MIN_EXPOSURE_MS = 50L

    /** Minimum shots per run — at least one shot is always required. */
    const val MIN_SHOT_COUNT = 1

    /** Minimum allowed value when the user edits the *default* exposure in
     *  Settings. Higher than [MIN_EXPOSURE_MS] because defaults should be
     *  practical starting points, not edge-case minimums. */
    const val MIN_DEFAULT_EXPOSURE_MS = 1000L

    /** Lowest value the "Max shots" setting will accept. */
    const val MIN_MAX_SHOTS = 10

    /** Highest value the "Max shots" setting will accept. */
    const val MAX_MAX_SHOTS = 100

    // ── Astro mode defaults ─────────────────────────────────────────────
    // Astro mode computes exposure from the NPF/500-rule:
    //   max_exposure_s = rule_divisor / (focal_length × crop_factor)

    /** Lens focal length used when no user preference exists (mm). */
    const val DEFAULT_FOCAL_LENGTH = 24

    /** Sensor crop factor — 1.0 for full-frame (Canon RP / R). */
    const val DEFAULT_CROP_FACTOR = 1.0f

    /** Divisor for the star-trail rule. 500 is conservative (classic "500 rule"),
     *  400 is tighter (less trailing). User can toggle in the UI. */
    const val DEFAULT_RULE_DIVISOR = 500

    /** Tighter star-trail rule divisor ("400 rule"). Produces shorter
     *  exposures with less star trailing — preferred for high-resolution sensors. */
    const val TIGHT_RULE_DIVISOR = 400

    /** Sentinel value indicating the NPF rule should be used instead of a
     *  simple divisor. The NPF formula accounts for pixel pitch (estimated
     *  from crop factor) and a typical f/2.8 aperture. */
    const val NPF_RULE_DIVISOR = 0

    /** Countdown before the first astro exposure (ms). */
    const val DEFAULT_ASTRO_DELAY_MS = 5000L

    /** Dead time between consecutive astro exposures (ms). Allows the camera
     *  to write the previous frame to the SD card. */
    const val DEFAULT_ASTRO_GAP_MS = 2000L

    /** Floor for computed astro exposure (ms). Even an ultra-wide lens should
     *  not produce sub-100 ms exposures — that would be too short to capture
     *  meaningful star light. */
    const val MIN_ASTRO_EXPOSURE_MS = 100L

    /** Minimum gap between astro shots (ms). */
    const val MIN_ASTRO_GAP_MS = 500L

    // ── Focal length range ──────────────────────────────────────────────

    /** Widest focal length the stepper accepts (mm) — fisheye territory. */
    const val MIN_FOCAL_LENGTH = 8

    /** Longest focal length the stepper accepts (mm) — super-telephoto. */
    const val MAX_FOCAL_LENGTH = 600

    /** Quick-pick presets shown as chips below the focal-length stepper.
     *  Covers common astro primes and zooms. */
    val FOCAL_LENGTH_PRESETS = listOf(14, 24, 50, 85, 135, 200)

    // ── GPIO defaults ───────────────────────────────────────────────────
    // ESP32 GPIO numbers used by the Pulsar hardware to drive the
    // optocoupler outputs connected to the camera's remote-release port.

    /** Factory GPIO for the shutter signal (ESP32). */
    const val DEFAULT_PIN_SHUTTER = 25

    /** Factory GPIO for the half-press / focus signal (ESP32). */
    const val DEFAULT_PIN_FOCUS = 26

    /** Factory GPIO for the shutter signal (ESP32-S3 / M5StickS3). */
    const val DEFAULT_PIN_SHUTTER_S3 = 5

    /** Factory GPIO for the focus signal (ESP32-S3 / M5StickS3). */
    const val DEFAULT_PIN_FOCUS_S3 = 6

    /** Allowlist of ESP32 GPIOs that are safe to use as outputs. Excludes
     *  strapping pins, input-only pins, and pins reserved by SPI flash. */
    val SAFE_OUTPUT_PINS = listOf(4, 13, 14, 16, 17, 18, 19, 21, 22, 23, 25, 26, 27)

    /** Allowlist of ESP32-S3 (M5StickS3) GPIOs safe for user-wired outputs.
     *  Excludes Grove port defaults (G9/G10) and internal-only pins. */
    val SAFE_OUTPUT_PINS_S3 = listOf(0, 1, 2, 3, 4, 5, 6, 7, 8, 43, 44)

    /** Return the correct safe-pin list for the given chip model string. */
    fun safeOutputPinsForChip(chipModel: String?): List<Int> = when (chipModel) {
        "ESP32-S3" -> SAFE_OUTPUT_PINS_S3
        else -> SAFE_OUTPUT_PINS
    }

    /** Default shutter pin for the given chip model. */
    fun defaultShutterPinForChip(chipModel: String?): Int = when (chipModel) {
        "ESP32-S3" -> DEFAULT_PIN_SHUTTER_S3
        else -> DEFAULT_PIN_SHUTTER
    }

    /** Default focus pin for the given chip model. */
    fun defaultFocusPinForChip(chipModel: String?): Int = when (chipModel) {
        "ESP32-S3" -> DEFAULT_PIN_FOCUS_S3
        else -> DEFAULT_PIN_FOCUS
    }

    // ── BLE ─────────────────────────────────────────────────────────────
    // Bluetooth Low Energy constants for communication with the Pulsar
    // firmware and OTA (Over-The-Air) firmware updates.

    /** Fixed size of every BLE command frame sent to the device (bytes). */
    const val BLE_FRAME_SIZE = 20

    /** Usable payload within a frame (frame size minus 1-byte command ID). */
    const val BLE_PAYLOAD_MAX = 19

    /** Maximum UTF-8 bytes for a custom device name suffix. */
    const val BLE_DEVICE_NAME_MAX = 12

    /** MTU requested during OTA for maximum throughput (BLE 5.x max). */
    const val BLE_OTA_MTU = 517

    /** Default BLE MTU before negotiation (BLE 4.x baseline). */
    const val BLE_DEFAULT_MTU = 23

    /** Minimum usable OTA chunk size (MTU - 3 ATT header, floor value). */
    const val BLE_MIN_OTA_CHUNK = 20

    /** Number of automatic reconnection attempts on connection failure. */
    const val BLE_CONNECT_RETRIES = 3

    /** Delay between reconnection attempts (ms). */
    const val BLE_RETRY_DELAY_MS = 200

    /** How often the app polls BLE RSSI while connected (ms). */
    const val BLE_RSSI_POLL_INTERVAL_MS = 2_000L

    /** RSSI value (dBm) at or above which the signal is considered strong. */
    const val BLE_RSSI_GOOD = -60

    /** RSSI value (dBm) at or below which the signal is considered weak and
     *  a haptic warning fires. Below this the connection may drop soon. */
    const val BLE_RSSI_WEAK = -80

    /** Throttle between consecutive BLE writes during OTA upload (ms).
     *  Prevents flooding the device's receive buffer. */
    const val OTA_CHUNK_DELAY_MS = 10L

    // ── Network ─────────────────────────────────────────────────────────
    // Timeouts for HTTP calls to Open-Meteo (weather/astronomy) and
    // lightpollutionmap.info (Bortle class).

    // ── Supported languages ───────────────────────────────────────────
    val SUPPORTED_LOCALES = listOf(
        "en" to "English",
        "pt" to "Português",
        "es" to "Español",
        "fr" to "Français",
        "de" to "Deutsch",
        "ja" to "日本語",
        "zh" to "中文",
    )

    /** TCP connection timeout for all API calls (ms). */
    const val API_CONNECT_TIMEOUT_MS = 10_000

    /** Socket read timeout for all API calls (ms). */
    const val API_READ_TIMEOUT_MS = 10_000

    /** TCP connection timeout for large file downloads — APK / firmware (ms).
     *  Longer than [API_CONNECT_TIMEOUT_MS] because CDN redirects may add latency. */
    const val DOWNLOAD_CONNECT_TIMEOUT_MS = 30_000

    /** Socket read timeout for large file downloads (ms). Generous to
     *  accommodate slow connections transferring multi-MB payloads. */
    const val DOWNLOAD_READ_TIMEOUT_MS = 60_000

    /** How often WorkManager runs the background app-update check (hours).
     *  Uses [PeriodicWorkRequest] with a flex window, so actual execution
     *  may vary depending on battery and Doze mode. */
    const val UPDATE_CHECK_INTERVAL_HOURS = 24L

    /** Maximum hourly forecast entries fetched for "today" queries.
     *  Limits payload size from Open-Meteo. */
    const val HOURLY_FORECAST_CAP = 12

    // ── Dashboard / astro calculator ────────────────────────────────────
    // Thresholds used by [AstroCalculator] to evaluate sky conditions and
    // compute Milky Way visibility windows and best-photo-window ratings.

    /** Moon illumination percentage below which conditions are considered
     *  good for deep-sky / Milky Way astrophotography. */
    const val MOON_GOOD_ASTRO_THRESHOLD = 25.0

    /** Galactic center must be at least this many degrees above the horizon
     *  to count as "visible" in the Milky Way window scan. */
    const val GC_ALTITUDE_THRESHOLD = 5.0

    /** Sun altitude (degrees) that defines astronomical twilight — below
     *  this the sky is considered fully dark. */
    const val ASTRONOMICAL_TWILIGHT_DEG = -18.0

    /** Time step for the Milky Way visibility sweep (minutes). Smaller
     *  values give finer resolution but take longer to compute. */
    const val MW_SCAN_STEP_MINUTES = 10.0

    /** Hours added before sunset / after sunrise to define the dark-sky
     *  window for best-photo-window analysis. */
    const val DARK_WINDOW_OFFSET_HOURS = 1.0

    /** Cloud cover percentage at or below which sky is considered "clear"
     *  for astrophotography — used by the dashboard verdict chips and
     *  weather cards. */
    const val CLOUD_COVER_CLEAR_THRESHOLD = 20

    /** Cloud cover percentage at or below which sky is "partly cloudy" —
     *  still acceptable for wide-field shots. Above this is "cloudy". */
    const val CLOUD_COVER_PARTLY_THRESHOLD = 50

    /** Hourly precipitation (mm) at or below which an hour is considered
     *  "clear enough" for astrophotography. */
    const val PRECIPITATION_CLEAR_THRESHOLD = 0.1

    /** Maximum number of best-photo windows returned to the UI. */
    const val MAX_PHOTO_WINDOWS = 3

    /** Cloud-free score (100 − avg cloud %) at or above which a window
     *  earns the highest (★★★) rating. */
    const val PHOTO_WINDOW_EXCELLENT = 80

    /** Cloud-free score at or above which a window earns a good (★★) rating.
     *  Below this it gets ★. */
    const val PHOTO_WINDOW_GOOD = 50

    // ── Dew point ───────────────────────────────────────────────────────
    // Dew point warning thresholds for lens-fogging alerts.

    /** Temperature-to-dew-point spread (°C) at or below which a dew
     *  warning is shown. Below this margin, lenses fog quickly. */
    const val DEW_POINT_WARN_SPREAD_C = 4.0

    /** Spread below which a critical dew alert fires. */
    const val DEW_POINT_CRITICAL_SPREAD_C = 2.0

    // ── Twilight ────────────────────────────────────────────────────────

    /** Sun altitude boundary for civil twilight (degrees above horizon). */
    const val CIVIL_TWILIGHT_DEG = -6.0

    /** Sun altitude boundary for nautical twilight (degrees above horizon). */
    const val NAUTICAL_TWILIGHT_DEG = -12.0

    // ASTRONOMICAL_TWILIGHT_DEG (-18°) already defined above

    // ── Planner ─────────────────────────────────────────────────────────

    /** Maximum number of saved locations the planner will store. */
    const val MAX_SAVED_LOCATIONS = 20

    /** Maximum number of planned sessions the user can create. */
    const val MAX_PLANNER_ENTRIES = 50

    /** How often the background planner condition-check runs (hours). */
    const val PLANNER_CHECK_INTERVAL_HOURS = 24L

    // ── Planetary positions ─────────────────────────────────────────────

    /** Minimum altitude (degrees) above horizon for a planet to be
     *  considered "visible" for photography. */
    const val PLANET_MIN_ALTITUDE_DEG = 5.0

    // ── Dashboard cache ─────────────────────────────────────────────────

    /** Maximum age of cached dashboard data before it's considered stale (ms). */
    const val DASHBOARD_CACHE_MAX_AGE_MS = 3_600_000L  // 1 hour

    /** Default refresh interval for planner session dashboard cache (hours). */
    const val PLANNER_DASHBOARD_CACHE_HOURS_DEFAULT = 24L

    // ── Simulator ───────────────────────────────────────────────────────

    /** Fake battery percentage shown when connected to the built-in
     *  simulator (no real hardware). */
    const val SIMULATOR_BATTERY_PCT = 85

    // ── Astro helpers ───────────────────────────────────────────────────

    /** Compute maximum astro exposure in milliseconds from optics params.
     *  When [ruleDivisor] is [NPF_RULE_DIVISOR], uses the NPF formula instead. */
    fun astroExposureMs(focalLength: Int, cropFactor: Float, ruleDivisor: Int): Long {
        val exposureS = astroExposureS(focalLength, cropFactor, ruleDivisor)
        return (exposureS * 1000).toLong().coerceAtLeast(MIN_ASTRO_EXPOSURE_MS)
    }

    /** Same computation but returns seconds as a Double (for display). */
    fun astroExposureS(focalLength: Int, cropFactor: Float, ruleDivisor: Int): Double =
        if (ruleDivisor == NPF_RULE_DIVISOR) {
            npfExposureS(focalLength, cropFactor)
        } else {
            ruleDivisor.toDouble() / (focalLength * cropFactor)
        }

    /** Estimated pixel pitch (μm) from crop factor, assuming typical modern ~24 MP sensors. */
    fun estimatedPixelPitchUm(cropFactor: Float): Double = when {
        cropFactor <= 1.1f -> 5.9   // Full frame ~24 MP
        cropFactor <= 1.55f -> 3.9  // APS-C Nikon/Sony
        cropFactor <= 1.65f -> 3.7  // APS-C Canon
        else -> 3.3                 // Micro 4/3
    }

    /** Simplified NPF rule: (35 × aperture + 30 × pixelPitch) / (focal × crop).
     *  Pixel pitch is estimated from crop factor for typical modern ~24 MP sensors.
     *  Assumes f/2.8 as a common astro aperture. */
    private fun npfExposureS(focalLength: Int, cropFactor: Float): Double {
        val pixelPitchUm = estimatedPixelPitchUm(cropFactor)
        val aperture = 2.8
        return (35.0 * aperture + 30.0 * pixelPitchUm) / (focalLength * cropFactor)
    }
}
