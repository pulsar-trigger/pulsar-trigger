/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Copyright (C) 2026 Pulsar Trigger contributors
 */

#include "display.h"
#include "config.h"
#include "triggers.h"
#include "status.h"
#include "ble_server.h"
#include <M5Unified.h>

// ── Layout constants ─────────────────────────────────────────────────────────
static int W, H;
static int BAR_H;       // top/bottom bar height
static int CENTER_Y;    // y start of center zone
static int CENTER_H;    // height of center zone

// ── Sprite (full-screen double buffer) ───────────────────────────────────────
static LGFX_Sprite _sprite(&M5.Display);

// ── Display power management ─────────────────────────────────────────────────
static uint32_t _last_activity_ms = 0;    // last BLE / button / state-change
static uint8_t  _brightness       = 80;   // current brightness (0-255)
static bool     _display_asleep   = false;
static const uint32_t DIM_AFTER_MS   = 15000;  // dim after 15 s idle
static const uint32_t SLEEP_AFTER_MS = 60000;  // sleep after 60 s idle
static const uint8_t  BRIGHT_FULL    = 30;
static const uint8_t  BRIGHT_DIM     = 8;

// ── Refresh throttle ─────────────────────────────────────────────────────────
static uint32_t _last_draw_ms = 0;
static const uint32_t DRAW_INTERVAL_MS = 200;  // ~5 Hz

// ── Cached prev state for activity detection ─────────────────────────────────
static State _prev_state = STATE_IDLE;
static bool  _prev_ble   = false;

// ── Colors (RGB565) ──────────────────────────────────────────────────────────
static const uint16_t COL_BG        = 0x0000;  // black
static const uint16_t COL_BAR_BG    = 0x18E3;  // dark gray
static const uint16_t COL_TEXT      = 0xFFFF;  // white
static const uint16_t COL_TEXT_DIM  = 0x8410;  // gray
static const uint16_t COL_GREEN     = 0x07E0;
static const uint16_t COL_RED       = 0xF800;
static const uint16_t COL_ORANGE    = 0xFD20;
static const uint16_t COL_BLUE      = 0x001F;
static const uint16_t COL_VIOLET    = 0x780F;

// ── Helpers ──────────────────────────────────────────────────────────────────

static void format_time(char* buf, size_t len, uint32_t ms) {
    uint32_t s = ms / 1000;
    if (s >= 3600) {
        snprintf(buf, len, "%luh%02lum", s / 3600, (s % 3600) / 60);
    } else if (s >= 60) {
        snprintf(buf, len, "%lum%02lus", s / 60, s % 60);
    } else if (ms >= 1000) {
        snprintf(buf, len, "%lu.%01lus", s, (ms % 1000) / 100);
    } else {
        snprintf(buf, len, "%lums", ms);
    }
}

static void activity_ping() {
    _last_activity_ms = millis();
    if (_display_asleep) {
        M5.Display.wakeup();
        _display_asleep = false;
    }
    if (_brightness != BRIGHT_FULL) {
        _brightness = BRIGHT_FULL;
        M5.Display.setBrightness(_brightness);
    }
}

// ── Top status bar ───────────────────────────────────────────────────────────
static void draw_top_bar() {
    _sprite.fillRect(0, 0, W, BAR_H, COL_BAR_BG);

    // Device name (left)
    _sprite.setTextDatum(middle_left);
    _sprite.setTextColor(COL_VIOLET);
    _sprite.setFont(&fonts::Font0);
    _sprite.setTextSize(1);
    _sprite.drawString("PULSAR", 2, BAR_H / 2);

    // BLE indicator (center-right)
    bool connected = ble_connected();
    uint16_t ble_col = connected ? COL_BLUE : COL_TEXT_DIM;
    _sprite.setTextDatum(middle_center);
    _sprite.setTextColor(ble_col);
    _sprite.drawString("BLE", W - 36, BAR_H / 2);

    // Battery (right)
    uint8_t batt = battery_read_pct();
    char batt_str[8];
    snprintf(batt_str, sizeof(batt_str), "%d%%", batt);
    uint16_t batt_col = (batt <= 15) ? COL_RED : (batt <= 30) ? COL_ORANGE : COL_GREEN;
    _sprite.setTextDatum(middle_right);
    _sprite.setTextColor(batt_col);
    _sprite.drawString(batt_str, W - 2, BAR_H / 2);
}

// ── Bottom state bar ─────────────────────────────────────────────────────────
static void draw_bottom_bar() {
    State st = triggers_current_state();
    uint16_t bg;
    const char* label;
    switch (st) {
        case STATE_RUNNING: bg = COL_GREEN;  label = "RUN";  break;
        case STATE_WAITING: bg = COL_ORANGE; label = "WAIT"; break;
        case STATE_ERROR:   bg = COL_RED;    label = "ERR";  break;
        default:            bg = COL_BAR_BG; label = "IDLE"; break;
    }
    int bar_y = H - BAR_H;
    _sprite.fillRect(0, bar_y, W, BAR_H, bg);

    // State label (left)
    _sprite.setTextDatum(middle_left);
    _sprite.setTextColor(COL_TEXT);
    _sprite.setFont(&fonts::Font0);
    _sprite.setTextSize(1);
    _sprite.drawString(label, 2, bar_y + BAR_H / 2);

    // Time remaining (right)
    uint32_t remaining = triggers_time_remaining_ms();
    if (remaining > 0 && st != STATE_IDLE) {
        char tbuf[16];
        format_time(tbuf, sizeof(tbuf), remaining);
        _sprite.setTextDatum(middle_right);
        _sprite.drawString(tbuf, W - 2, bar_y + BAR_H / 2);
    }
}

// ── Center zone: IDLE (no mode) ──────────────────────────────────────────────
static void draw_center_idle() {
    int cy = CENTER_Y + CENTER_H / 2;

    _sprite.setTextDatum(middle_center);
    _sprite.setTextColor(COL_VIOLET);
    _sprite.setFont(&fonts::FreeSans9pt7b);
    _sprite.setTextSize(1);
    _sprite.drawString("PULSAR", W / 2, cy - 16);

    char ver[16];
    snprintf(ver, sizeof(ver), "v%d.%d.%d", FW_VERSION_MAJOR, FW_VERSION_MINOR, FW_VERSION_PATCH);
    _sprite.setFont(&fonts::Font0);
    _sprite.setTextColor(COL_TEXT_DIM);
    _sprite.drawString(ver, W / 2, cy + 4);
    _sprite.drawString("Ready", W / 2, cy + 20);
}

// ── Center zone: Intervalometer ──────────────────────────────────────────────
static void draw_center_intervalometer() {
    int cx = W / 2;
    int cy = CENTER_Y + CENTER_H / 2;

    // Mode label
    _sprite.setTextDatum(top_center);
    _sprite.setTextColor(COL_TEXT_DIM);
    _sprite.setFont(&fonts::Font0);
    _sprite.setTextSize(1);
    _sprite.drawString("INTERVALOMETER", cx, CENTER_Y + 2);

    // Shot counter — large (1-based: show current/next shot number)
    uint16_t shots = triggers_shots_taken();
    State st = triggers_current_state();
    uint16_t display_shots = (st == STATE_RUNNING || st == STATE_WAITING) ? shots + 1 : shots;
    const IntervalParams& p = triggers_interval_params();
    char shot_str[12];
    snprintf(shot_str, sizeof(shot_str), "%u", display_shots);

    _sprite.setTextDatum(middle_center);
    _sprite.setTextColor(COL_TEXT);
    _sprite.setFont(&fonts::FreeSans9pt7b);
    _sprite.setTextSize(W >= 200 ? 2 : 1);
    _sprite.drawString(shot_str, cx, cy - 8);

    // Total count
    _sprite.setTextSize(1);
    _sprite.setFont(&fonts::Font0);
    _sprite.setTextColor(COL_TEXT_DIM);
    if (p.count > 0) {
        char total_str[12];
        snprintf(total_str, sizeof(total_str), "/ %u", p.count);
        _sprite.drawString(total_str, cx, cy + 10);
    } else {
        _sprite.drawString("/ INF", cx, cy + 10);
    }

    // Exposure + Gap on bottom of center zone
    char exp_str[16], gap_str[16];
    format_time(exp_str, sizeof(exp_str), p.exposure_ms);
    format_time(gap_str, sizeof(gap_str), p.interval_ms);
    char info[40];
    snprintf(info, sizeof(info), "EXP %s  GAP %s", exp_str, gap_str);
    _sprite.setTextDatum(bottom_center);
    _sprite.setTextColor(COL_TEXT_DIM);
    _sprite.drawString(info, cx, CENTER_Y + CENTER_H - 2);
}

// ── Center zone: Press Hold ──────────────────────────────────────────────────
static void draw_center_hold() {
    int cx = W / 2;
    int cy = CENTER_Y + CENTER_H / 2;

    _sprite.setTextDatum(top_center);
    _sprite.setTextColor(COL_TEXT_DIM);
    _sprite.setFont(&fonts::Font0);
    _sprite.setTextSize(1);
    _sprite.drawString("HOLD MODE", cx, CENTER_Y + 2);

    State st = triggers_current_state();
    bool firing = (st == STATE_RUNNING);

    // Big status block
    int bw = W * 3 / 5;
    int bh = CENTER_H / 3;
    int bx = (W - bw) / 2;
    int by = cy - bh / 2;
    uint16_t block_col = firing ? COL_RED : COL_GREEN;
    _sprite.fillRoundRect(bx, by, bw, bh, 4, block_col);

    _sprite.setTextDatum(middle_center);
    _sprite.setTextColor(COL_TEXT);
    _sprite.setFont(&fonts::FreeSans9pt7b);
    _sprite.setTextSize(1);
    _sprite.drawString(firing ? "FIRING" : "READY", cx, cy);
}

// ── Center zone: Press Lock ──────────────────────────────────────────────────
static void draw_center_lock() {
    int cx = W / 2;
    int cy = CENTER_Y + CENTER_H / 2;

    _sprite.setTextDatum(top_center);
    _sprite.setTextColor(COL_TEXT_DIM);
    _sprite.setFont(&fonts::Font0);
    _sprite.setTextSize(1);
    _sprite.drawString("LOCK MODE", cx, CENTER_Y + 2);

    State st = triggers_current_state();
    bool locked = (st == STATE_RUNNING);

    // Lock icon + state
    int bw = W * 3 / 5;
    int bh = CENTER_H / 3;
    int bx = (W - bw) / 2;
    int by = cy - bh / 2;
    uint16_t block_col = locked ? COL_RED : COL_BAR_BG;
    _sprite.fillRoundRect(bx, by, bw, bh, 4, block_col);

    _sprite.setTextDatum(middle_center);
    _sprite.setTextColor(COL_TEXT);
    _sprite.setFont(&fonts::FreeSans9pt7b);
    _sprite.setTextSize(1);
    _sprite.drawString(locked ? "LOCKED" : "UNLOCK", cx, cy);
}

// ── Center zone: Tracker Alignment ───────────────────────────────────────────
static void draw_center_tracker() {
    int cx = W / 2;
    int cy = CENTER_Y + CENTER_H / 2;

    // Mode label
    _sprite.setTextDatum(top_center);
    _sprite.setTextColor(COL_TEXT_DIM);
    _sprite.setFont(&fonts::Font0);
    _sprite.setTextSize(1);
    _sprite.drawString("TRACKER ALIGN", cx, CENTER_Y + 2);

    // Pitch value — large
    float pitch = triggers_tracker_pitch();
    char pitch_str[16];
    snprintf(pitch_str, sizeof(pitch_str), "%.1f", pitch);

    _sprite.setTextDatum(middle_center);
    _sprite.setTextColor(COL_TEXT);
    _sprite.setFont(&fonts::FreeSans9pt7b);
    _sprite.setTextSize(W >= 200 ? 2 : 1);
    _sprite.drawString(pitch_str, cx, cy - 8);

    // Degree symbol + label
    _sprite.setTextSize(1);
    _sprite.setFont(&fonts::Font0);
    _sprite.setTextColor(COL_TEXT_DIM);
    _sprite.drawString("PITCH (deg)", cx, cy + 14);

    // Graphical bar: center = 0°, left = -90°, right = +90°
    int bar_w = W - 20;
    int bar_h = 6;
    int bar_x = 10;
    int bar_y = CENTER_Y + CENTER_H - 16;
    _sprite.fillRoundRect(bar_x, bar_y, bar_w, bar_h, 2, COL_BAR_BG);

    // Center tick mark (0°)
    _sprite.drawFastVLine(cx, bar_y - 2, bar_h + 4, COL_TEXT_DIM);

    // Current pitch indicator
    float clamped = pitch;
    if (clamped < -90.0f) clamped = -90.0f;
    if (clamped >  90.0f) clamped =  90.0f;
    int indicator_x = bar_x + (int)((clamped + 90.0f) / 180.0f * bar_w);
    _sprite.fillCircle(indicator_x, bar_y + bar_h / 2, 4, COL_VIOLET);
}

// ── Public API ───────────────────────────────────────────────────────────────

void display_init() {
    W = M5.Display.width();
    H = M5.Display.height();
    BAR_H = (H <= 128) ? 14 : 24;
    CENTER_Y = BAR_H;
    CENTER_H = H - 2 * BAR_H;

    _sprite.createSprite(W, H);
    _sprite.setSwapBytes(true);

    M5.Display.setRotation(0);
    M5.Display.setBrightness(0);
    M5.Display.sleep();
    _brightness = 0;
    _display_asleep = true;
}

void display_update() {
    uint32_t now = millis();

    // ── Buttons ──────────────────────────────────────────────────────────
    // Front button (BtnA): wake display on any tap
    if (M5.BtnA.wasClicked()) {
        activity_ping();
    }
    // Top button (BtnB): toggle display on/off
    if (M5.BtnB.wasClicked()) {
        if (_display_asleep) {
            activity_ping();
        } else {
            M5.Display.sleep();
            _display_asleep = true;
            return;
        }
    }

    // ── Activity detection: BLE connect/disconnect or state change ────────
    bool ble_now = ble_connected();
    State st_now = triggers_current_state();
    if (ble_now != _prev_ble || st_now != _prev_state) {
        activity_ping();
        _prev_ble = ble_now;
        _prev_state = st_now;
    }

    // ── Power management ─────────────────────────────────────────────────
    uint32_t idle = now - _last_activity_ms;
    if (!_display_asleep && idle > SLEEP_AFTER_MS) {
        M5.Display.sleep();
        _display_asleep = true;
        return;
    }
    if (!_display_asleep && idle > DIM_AFTER_MS && _brightness != BRIGHT_DIM) {
        _brightness = BRIGHT_DIM;
        M5.Display.setBrightness(_brightness);
    }
    if (_display_asleep) return;

    // ── Throttle redraws ─────────────────────────────────────────────────
    if (now - _last_draw_ms < DRAW_INTERVAL_MS) return;
    _last_draw_ms = now;

    // ── Draw frame into sprite ───────────────────────────────────────────
    _sprite.fillSprite(COL_BG);
    draw_top_bar();
    draw_bottom_bar();

    // Center zone — mode-specific
    Mode mode = triggers_current_mode();
    switch (mode) {
        case MODE_INTERVALOMETER: draw_center_intervalometer(); break;
        case MODE_PRESS_HOLD:    draw_center_hold();           break;
        case MODE_PRESS_LOCK:    draw_center_lock();           break;
        case MODE_TRACKER:       draw_center_tracker();        break;
        default:                 draw_center_idle();            break;
    }

    // Flush to display
    _sprite.pushSprite(0, 0);
}
