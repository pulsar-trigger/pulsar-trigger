/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Copyright (C) 2026 Pulsar Trigger contributors
 */

#pragma once
#include <cstdint>

// ── GPIO pins ────────────────────────────────────────────────────────────────
#ifdef BOARD_M5STICKS3
  // M5StickS3: user-wired optocoupler on Hat2-Bus (G5, G6)
  #define DEFAULT_PIN_SHUTTER  5
  #define DEFAULT_PIN_FOCUS    6
  // No dedicated status LED GPIO — use M5Unified display
  #define PIN_LED       GPIO_NUM_NC
  // Battery via M5PM1 I2C PMIC — no ADC pin
  #define PIN_BATTERY   GPIO_NUM_NC
#elif defined(BOARD_M5CORE2)
  // M5Stack Core2: M5-Bus exposes G25, G26 for optocoupler
  #define DEFAULT_PIN_SHUTTER  25
  #define DEFAULT_PIN_FOCUS    26
  #define PIN_LED       GPIO_NUM_NC
  #define PIN_BATTERY   GPIO_NUM_NC   // battery via AXP192 PMIC
#else
  // Generic ESP32-DevKit: standard GPIO mapping
  #define DEFAULT_PIN_SHUTTER  25     // optocoupler → camera shutter
  #define DEFAULT_PIN_FOCUS    26     // optocoupler → camera focus
  #define PIN_LED       GPIO_NUM_2    // on-board LED (status)
  #define PIN_BATTERY   GPIO_NUM_33   // analog — battery voltage divider
#endif

// ── Derived feature flags ────────────────────────────────────────────────────
#if defined(BOARD_M5STICKS3) || defined(BOARD_M5CORE2)
  #define HAS_M5DISPLAY  1
#endif

// ── Safe digital-output pins for shutter / focus ─────────────────────────────
#ifdef BOARD_M5STICKS3
  // Hat2-Bus exposes: G0-G8, G43, G44
  static const uint8_t SAFE_OUTPUT_PINS[] = {
      0, 1, 2, 3, 4, 5, 6, 7, 8, 43, 44
  };
#elif defined(BOARD_M5CORE2)
  // Core2 M5-Bus: most GPIOs accessible
  static const uint8_t SAFE_OUTPUT_PINS[] = {
      13, 14, 19, 25, 26, 27, 32, 33
  };
#else
  // ESP32-DevKit: exclude boot-strapping, flash, input-only pins
  static const uint8_t SAFE_OUTPUT_PINS[] = {
      4, 13, 14, 16, 17, 18, 19, 21, 22, 23, 25, 26, 27
  };
#endif
#define SAFE_OUTPUT_PIN_COUNT  (sizeof(SAFE_OUTPUT_PINS) / sizeof(SAFE_OUTPUT_PINS[0]))

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


// ── Firmware version ──────────────────────────────────────────────────────────
#ifndef FW_VERSION_MAJOR
#define FW_VERSION_MAJOR  0
#endif
#ifndef FW_VERSION_MINOR
#define FW_VERSION_MINOR  4
#endif
#ifndef FW_VERSION_PATCH
#define FW_VERSION_PATCH  0
#endif

// ── BLE ──────────────────────────────────────────────────────────────────────
#define BLE_DEVICE_NAME       "Pulsar"
#define BLE_NAME_PREFIX       "Pulsar-"
#define BLE_NAME_SUFFIX_MAX   12          // max chars after "Pulsar-"
#define SERVICE_UUID          "0000ff00-0000-1000-8000-00805f9b34fb"
#define CHAR_COMMAND_UUID     "0000ff01-0000-1000-8000-00805f9b34fb"
#define CHAR_STATUS_UUID      "0000ff02-0000-1000-8000-00805f9b34fb"

// ── Tracker pitch characteristic (on main service) ──────────────────────────
#define CHAR_PITCH_UUID       "0000ff03-0000-1000-8000-00805f9b34fb"

// ── OTA BLE characteristics ──────────────────────────────────────────────────
#define OTA_SERVICE_UUID      "0000ff10-0000-1000-8000-00805f9b34fb"
#define OTA_CONTROL_UUID      "0000ff11-0000-1000-8000-00805f9b34fb"
#define OTA_DATA_UUID         "0000ff12-0000-1000-8000-00805f9b34fb"

// ── Battery ──────────────────────────────────────────────────────────────────
#ifndef HAS_M5DISPLAY
  // Generic ESP32: direct ADC via resistor divider
  #define BATTERY_FULL_MV       4200
  #define BATTERY_EMPTY_MV      3200
  #define BATTERY_DIVIDER_RATIO 2.0   // resistor divider factor
#endif
// M5 boards read battery via M5Unified Power API — no ADC defines needed
