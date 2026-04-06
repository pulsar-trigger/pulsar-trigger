/*
 * Minimal Arduino.h stub for native unit tests.
 * Provides just enough to compile triggers.cpp on the host.
 */

#pragma once
#include <cstdint>
#include <cstddef>
#include <cstdio>

// Simulated millis() — test code can advance via stub_set_millis()
extern uint32_t _stub_millis;
inline uint32_t millis() { return _stub_millis; }
void stub_set_millis(uint32_t ms);

// Minimal Serial stub
struct SerialStub {
    template<typename... Args>
    void printf(const char*, Args...) {}
};
extern SerialStub Serial;

// GPIO types used by config.h
#define GPIO_NUM_2  2
#define GPIO_NUM_33 33
