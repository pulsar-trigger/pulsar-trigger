/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Copyright (C) 2026 Pulsar Trigger contributors
 */

#include "wake.h"

#include <Arduino.h>
#include <freertos/FreeRTOS.h>
#include <freertos/task.h>

// The loop task's own notification is the wake signal: wake_wait_ms takes it
// with a timeout, wake_kick gives it. No queue or semaphore to allocate, and
// a kick that arrives between two waits is not lost - the next wait returns
// at once.
static TaskHandle_t _loop_task = nullptr;

void wake_init() {
    _loop_task = xTaskGetCurrentTaskHandle();
}

bool wake_wait_ms(uint32_t ms) {
    if (ms == 0) return false;
    // Any task may wait; only the loop task is ever kicked. Another task
    // (a BLE callback firing a single shot) simply waits the full time.
    return ulTaskNotifyTake(pdTRUE, pdMS_TO_TICKS(ms)) != 0;
}

void wake_kick() {
    if (_loop_task) xTaskNotifyGive(_loop_task);
}

void wake_spin_until_ms(uint32_t target_ms) {
    while (wake_ms_until(millis(), target_ms) != 0) {
        // at most WAKE_EARLY_MS; nothing to yield to that matters here
    }
}
