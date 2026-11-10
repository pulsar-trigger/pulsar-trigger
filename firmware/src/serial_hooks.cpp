/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Copyright (C) 2026 Pulsar Trigger contributors
 */

// Single-key commands on the USB serial console, for measuring power and
// checking timing without the app. They do only what the app can do (set a
// mode, start, stop) and read nothing back from the console otherwise.
//
//   r  start the measurement run: intervalometer, 30 s exposure, 5 s gap,
//      20 shots, no delay - state (c) of docs/power.md
//   t  start a short timing run: 2 s exposure, 1 s gap, 5 shots
//   s  stop the run
//   o  one single shot (what the app's single-shot button does)
//   i  print the power state: CPU MHz, PM, BLE link, run state
//   ?  this list

#include "serial_hooks.h"
#include "triggers.h"
#include "ble_server.h"
#include "protocol.h"

#include <Arduino.h>
#include <cstring>

static void start_interval(uint32_t exposure_ms, uint32_t gap_ms, uint16_t count) {
    // The SET_MODE payload, little-endian: interval, exposure, count, delay.
    uint8_t p[14] = {0};
    const uint32_t delay_ms = 0;
    memcpy(&p[0], &gap_ms, 4);
    memcpy(&p[4], &exposure_ms, 4);
    memcpy(&p[8], &count, 2);
    memcpy(&p[10], &delay_ms, 4);
    if (!triggers_set_mode(MODE_INTERVALOMETER, p, sizeof(p))) {
        Serial.println("[HOOK] set mode refused");
        return;
    }
    triggers_start();
    Serial.printf("[HOOK] run: %lu ms exposure, %lu ms gap, %u shots\n",
                  (unsigned long)exposure_ms, (unsigned long)gap_ms, count);
}

static void print_state() {
    Serial.printf("[HOOK] cpu %u MHz, PM %s, ble %s, state %d, mode %d, shots %u\n",
                  getCpuFrequencyMhz(),
#if CONFIG_PM_ENABLE
                  "enabled",
#else
                  "not built in",
#endif
                  ble_connected() ? "connected" : "advertising",
                  (int)triggers_current_state(), (int)triggers_current_mode(),
                  triggers_shots_taken());
}

void serial_hooks_poll() {
    while (Serial.available() > 0) {
        switch (Serial.read()) {
            case 'r': start_interval(30000, 5000, 20); break;
            case 't': start_interval(2000, 1000, 5); break;
            case 's': triggers_stop(); Serial.println("[HOOK] stopped"); break;
            case 'o': {
                uint32_t t0 = millis();
                triggers_single_shot();
                Serial.printf("[HOOK] single shot took %lu ms\n", (unsigned long)(millis() - t0));
                break;
            }
            case 'i': print_state(); break;
            case '?':
                Serial.println("[HOOK] r=measure run 30s/5s/20  t=timing run 2s/1s/5  "
                               "s=stop  o=single shot  i=state");
                break;
            default: break;  // newlines and anything else
        }
    }
}
