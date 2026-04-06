/*
 * Status stub for native unit tests.
 * Records the last status_send() call.
 */

#pragma once
#include "protocol.h"

extern State stub_last_state;
extern Mode stub_last_mode;
extern uint16_t stub_last_shots;
extern uint32_t stub_last_time_ms;
extern uint8_t stub_last_error;
extern int stub_status_send_count;

void status_send(State state, Mode mode, uint16_t shots, uint32_t time_ms, uint8_t error);
void stub_status_reset();
