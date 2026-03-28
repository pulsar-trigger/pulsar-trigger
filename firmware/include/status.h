#pragma once
#include <Arduino.h>
#include "protocol.h"

/// Read battery percentage (0-100).
uint8_t battery_read_pct();

/// Build and send a StatusFrame via BLE notify.
void status_send(State state, Mode mode, uint16_t shots, uint32_t time_ms, uint8_t error = 0);
