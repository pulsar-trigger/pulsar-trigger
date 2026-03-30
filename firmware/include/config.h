/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Copyright (C) 2026 Pulsar Trigger contributors
 */

#pragma once

// ── GPIO pins ────────────────────────────────────────────────────────────────
#define PIN_SHUTTER   GPIO_NUM_25   // optocoupler → camera shutter
#define PIN_FOCUS     GPIO_NUM_26   // optocoupler → camera focus
#define PIN_SOUND     GPIO_NUM_34   // analog — electret mic / sound sensor
#define PIN_LIGHT     GPIO_NUM_35   // analog — photodiode / lightning sensor
#define PIN_LASER_RX  GPIO_NUM_32   // analog — laser break-beam receiver
#define PIN_LED       GPIO_NUM_2    // on-board LED (status)
#define PIN_BATTERY   GPIO_NUM_33   // analog — battery voltage divider

// ── Timing defaults ──────────────────────────────────────────────────────────
#define DEFAULT_FOCUS_MS      200   // pre-focus hold before shutter
#define DEBOUNCE_MS           50

// ── Parameter ranges ─────────────────────────────────────────────────────────
#define MIN_FOCUS_MS          50
#define MAX_FOCUS_MS          5000
#define MIN_INTERVAL_MS       500
#define MAX_INTERVAL_MS       3600000   // 1 hour
#define MIN_EXPOSURE_MS       10
#define MAX_EXPOSURE_MS       3600000   // 1 hour
#define MIN_SHOT_COUNT        0         // 0 = infinite
#define MAX_SHOT_COUNT        9999
#define MIN_DELAY_MS          0
#define MAX_DELAY_MS          3600000   // 1 hour
#define MIN_SOUND_THRESHOLD   1
#define MAX_SOUND_THRESHOLD   4095
#define MIN_LIGHTNING_SENS    1
#define MAX_LIGHTNING_SENS    5
#define MIN_HDR_COUNT         2
#define MAX_HDR_COUNT         5

// ── BLE ──────────────────────────────────────────────────────────────────────
#define BLE_DEVICE_NAME       "Pulsar"
#define BLE_NAME_PREFIX       "Pulsar-"
#define BLE_NAME_SUFFIX_MAX   12          // max chars after "Pulsar-"
#define SERVICE_UUID          "0000ff00-0000-1000-8000-00805f9b34fb"
#define CHAR_COMMAND_UUID     "0000ff01-0000-1000-8000-00805f9b34fb"
#define CHAR_STATUS_UUID      "0000ff02-0000-1000-8000-00805f9b34fb"

// ── Sensor thresholds ────────────────────────────────────────────────────────
#define SOUND_DEFAULT_THRESH  512
#define LIGHT_DEFAULT_SENS    3     // 1-5 scale
#define LASER_BREAK_THRESH    200

// ── Battery ──────────────────────────────────────────────────────────────────
#define BATTERY_FULL_MV       4200
#define BATTERY_EMPTY_MV      3200
#define BATTERY_DIVIDER_RATIO 2.0   // resistor divider factor
