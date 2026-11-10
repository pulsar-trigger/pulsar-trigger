/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Copyright (C) 2026 Pulsar Trigger contributors
 *
 * Unit tests for link_policy.h - which BLE connection parameters the firmware
 * asks for, and that both parameter sets obey the spec's timeout rule.
 */

#include <unity.h>
#include "link_policy.h"

void setUp(void) {}
void tearDown(void) {}

void test_disconnected_asks_nothing(void) {
    TEST_ASSERT_TRUE(link_wanted(false, false, false, 0) == LinkMode::None);
    TEST_ASSERT_TRUE(link_wanted(false, true, true, 0) == LinkMode::None);
}

void test_ota_is_always_fast(void) {
    TEST_ASSERT_TRUE(link_wanted(true, true, false, 999999) == LinkMode::Fast);
    TEST_ASSERT_TRUE(link_wanted(true, true, true, 999999) == LinkMode::Fast);
}

void test_a_run_is_slow_even_right_after_a_command(void) {
    // The START command itself is an interaction; the run must still go slow.
    TEST_ASSERT_TRUE(link_wanted(true, false, true, 0) == LinkMode::Slow);
}

void test_idle_is_fast_while_someone_is_at_the_app_then_slow(void) {
    TEST_ASSERT_TRUE(link_wanted(true, false, false, 0) == LinkMode::Fast);
    TEST_ASSERT_TRUE(link_wanted(true, false, false, LINK_INTERACTIVE_MS - 1) == LinkMode::Fast);
    TEST_ASSERT_TRUE(link_wanted(true, false, false, LINK_INTERACTIVE_MS) == LinkMode::Slow);
}

void test_the_slow_link_is_the_one_asked_for(void) {
    // 400-800 ms, latency 2, 6 s: the values in the power task.
    TEST_ASSERT_EQUAL_UINT16(320, LINK_SLOW.min_interval);
    TEST_ASSERT_EQUAL_UINT16(640, LINK_SLOW.max_interval);
    TEST_ASSERT_EQUAL_UINT16(2, LINK_SLOW.latency);
    TEST_ASSERT_EQUAL_UINT16(600, LINK_SLOW.timeout);
}

void test_the_timeout_rule_rejects_a_bad_set(void) {
    TEST_ASSERT_TRUE(link_params_valid(LINK_FAST));
    TEST_ASSERT_TRUE(link_params_valid(LINK_SLOW));
    // 800 ms, latency 4: needs more than 8 s; 6 s must be refused.
    TEST_ASSERT_FALSE(link_params_valid(LinkParams{320, 640, 4, 600}));
    // min above max
    TEST_ASSERT_FALSE(link_params_valid(LinkParams{640, 320, 0, 600}));
}

int main(int, char**) {
    UNITY_BEGIN();
    RUN_TEST(test_disconnected_asks_nothing);
    RUN_TEST(test_ota_is_always_fast);
    RUN_TEST(test_a_run_is_slow_even_right_after_a_command);
    RUN_TEST(test_idle_is_fast_while_someone_is_at_the_app_then_slow);
    RUN_TEST(test_the_slow_link_is_the_one_asked_for);
    RUN_TEST(test_the_timeout_rule_rejects_a_bad_set);
    return UNITY_END();
}
