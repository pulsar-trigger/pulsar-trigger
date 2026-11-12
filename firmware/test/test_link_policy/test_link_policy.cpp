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


// ── LinkTracker: ask from what the link runs at, not from what was asked ──

static constexpr uint16_t MS30 = 24, MS795 = 636, MS50 = 40;

void test_serves(void) {
    TEST_ASSERT_TRUE(link_serves(LinkMode::Fast, MS30));
    TEST_ASSERT_FALSE(link_serves(LinkMode::Fast, MS50));
    TEST_ASSERT_FALSE(link_serves(LinkMode::Fast, MS795));
    TEST_ASSERT_TRUE(link_serves(LinkMode::Slow, MS795));
    TEST_ASSERT_FALSE(link_serves(LinkMode::Slow, MS30));
    TEST_ASSERT_FALSE(link_serves(LinkMode::Fast, 0));  // unknown: ask
    TEST_ASSERT_TRUE(link_serves(LinkMode::None, 0));
}

void test_no_request_when_the_link_already_serves(void) {
    LinkTracker t;
    t.connected(MS30);
    TEST_ASSERT_FALSE(t.should_ask(LinkMode::Fast, 0));
}

void test_one_request_then_wait_for_the_answer(void) {
    LinkTracker t;
    t.connected(MS50);
    TEST_ASSERT_TRUE(t.should_ask(LinkMode::Fast, 0));
    TEST_ASSERT_FALSE(t.should_ask(LinkMode::Fast, 100));
    TEST_ASSERT_FALSE(t.should_ask(LinkMode::Fast, LINK_ANSWER_MS - 1));
    t.updated(MS30, true);
    TEST_ASSERT_FALSE(t.should_ask(LinkMode::Fast, LINK_ANSWER_MS));
}

void test_no_second_request_while_one_is_in_flight(void) {
    // Mode changes while Slow is still out: Fast waits for Slow's answer,
    // which is what kept Bluedroid refusing ("two connection parameter
    // requests") and the answers landing out of order.
    LinkTracker t;
    t.connected(MS30);
    TEST_ASSERT_TRUE(t.should_ask(LinkMode::Slow, 0));
    TEST_ASSERT_FALSE(t.should_ask(LinkMode::Fast, 50));
    t.updated(MS795, true);
    TEST_ASSERT_TRUE(t.should_ask(LinkMode::Fast, 60));
}

void test_the_bench_failure_a_late_slow_answer_is_corrected(void) {
    // The 2026-11-11 log: Slow asked; an unrelated 30 ms report arrives; the
    // OTA wants Fast and the link looks fast; then Slow's answer lands at
    // 795 ms. The old code believed its last request and stayed there.
    LinkTracker t;
    t.connected(MS30);
    TEST_ASSERT_TRUE(t.should_ask(LinkMode::Slow, 10000));
    t.updated(MS30, true);                                 // not our answer
    TEST_ASSERT_FALSE(t.should_ask(LinkMode::Fast, 17000)); // looks served
    t.updated(MS795, true);                                // late Slow lands
    TEST_ASSERT_TRUE(t.should_ask(LinkMode::Fast, 17300));
    t.updated(MS30, true);
    TEST_ASSERT_FALSE(t.should_ask(LinkMode::Fast, 17400));
}

void test_a_refused_request_is_asked_again(void) {
    LinkTracker t;
    t.connected(MS795);
    TEST_ASSERT_TRUE(t.should_ask(LinkMode::Fast, 0));
    t.updated(MS30, false);  // refused: status != 0, link unchanged
    TEST_ASSERT_TRUE(t.should_ask(LinkMode::Fast, 10));
}

void test_no_answer_is_asked_again_after_the_wait(void) {
    LinkTracker t;
    t.connected(MS795);
    TEST_ASSERT_TRUE(t.should_ask(LinkMode::Fast, 0));
    TEST_ASSERT_TRUE(t.should_ask(LinkMode::Fast, LINK_ANSWER_MS));
}

void test_a_central_that_grants_something_else_is_left_alone(void) {
    LinkTracker t;
    t.connected(MS50);
    for (uint8_t i = 0; i < LINK_MAX_TRIES; i++) {
        TEST_ASSERT_TRUE(t.should_ask(LinkMode::Fast, i * 10));
        t.updated(MS50, true);  // the phone keeps its 50 ms
    }
    TEST_ASSERT_FALSE(t.should_ask(LinkMode::Fast, 1000));
    // A new mode gets its own tries.
    TEST_ASSERT_TRUE(t.should_ask(LinkMode::Slow, 1010));
}

void test_disconnected_never_asks(void) {
    LinkTracker t;
    TEST_ASSERT_FALSE(t.should_ask(LinkMode::None, 0));
}

void test_a_new_connection_starts_clean(void) {
    LinkTracker t;
    t.connected(MS50);
    for (uint8_t i = 0; i < LINK_MAX_TRIES; i++) t.should_ask(LinkMode::Fast, i * LINK_ANSWER_MS);
    t.connected(MS50);
    TEST_ASSERT_TRUE(t.should_ask(LinkMode::Fast, 0));
}

int main(int, char**) {
    UNITY_BEGIN();
    RUN_TEST(test_disconnected_asks_nothing);
    RUN_TEST(test_ota_is_always_fast);
    RUN_TEST(test_a_run_is_slow_even_right_after_a_command);
    RUN_TEST(test_idle_is_fast_while_someone_is_at_the_app_then_slow);
    RUN_TEST(test_the_slow_link_is_the_one_asked_for);
    RUN_TEST(test_the_timeout_rule_rejects_a_bad_set);
    RUN_TEST(test_serves);
    RUN_TEST(test_no_request_when_the_link_already_serves);
    RUN_TEST(test_one_request_then_wait_for_the_answer);
    RUN_TEST(test_no_second_request_while_one_is_in_flight);
    RUN_TEST(test_the_bench_failure_a_late_slow_answer_is_corrected);
    RUN_TEST(test_a_refused_request_is_asked_again);
    RUN_TEST(test_no_answer_is_asked_again_after_the_wait);
    RUN_TEST(test_a_central_that_grants_something_else_is_left_alone);
    RUN_TEST(test_disconnected_never_asks);
    RUN_TEST(test_a_new_connection_starts_clean);
    return UNITY_END();
}
