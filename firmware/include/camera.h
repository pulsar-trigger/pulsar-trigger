#pragma once
#include <Arduino.h>
#include "config.h"

/// Fire the shutter optocoupler for `duration_ms`, with optional pre-focus.
void camera_init();
void camera_focus(bool on);
void camera_shutter(uint32_t duration_ms, uint32_t focus_ms);
