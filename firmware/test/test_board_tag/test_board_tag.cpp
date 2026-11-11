/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Copyright (C) 2026 Pulsar Trigger contributors
 *
 * Unit tests for board_tag.h - finding "PULSAR-BOARD-ID=<n>;" in a firmware
 * image as it streams in over OTA.
 */

#include <unity.h>
#include <cstring>
#include "board_tag.h"

void setUp(void) {}
void tearDown(void) {}

static int scan(const char* s) {
    BoardTagScanner t;
    t.feed(reinterpret_cast<const uint8_t*>(s), strlen(s));
    return t.found;
}

void test_finds_the_tag_in_one_chunk(void) {
    TEST_ASSERT_EQUAL_INT(2, scan("\x01\x02junkPULSAR-BOARD-ID=2;more"));
    TEST_ASSERT_EQUAL_INT(3, scan("PULSAR-BOARD-ID=3;"));
    TEST_ASSERT_EQUAL_INT(17, scan("..PULSAR-BOARD-ID=17;.."));
}

void test_finds_a_tag_split_across_chunks(void) {
    const char* img = "xxxxPULSAR-BOARD-ID=3;yyyy";
    size_t n = strlen(img);
    for (size_t cut = 1; cut < n; cut++) {  // every possible split point
        BoardTagScanner t;
        t.feed(reinterpret_cast<const uint8_t*>(img), cut);
        t.feed(reinterpret_cast<const uint8_t*>(img) + cut, n - cut);
        TEST_ASSERT_EQUAL_INT_MESSAGE(3, t.found, "split tag not found");
    }
}

void test_no_tag_is_minus_one(void) {
    TEST_ASSERT_EQUAL_INT(-1, scan("an old image with no tag at all"));
    TEST_ASSERT_EQUAL_INT(-1, scan(""));
}

void test_malformed_tags_are_ignored(void) {
    TEST_ASSERT_EQUAL_INT(-1, scan("PULSAR-BOARD-ID=;"));      // no digits
    TEST_ASSERT_EQUAL_INT(-1, scan("PULSAR-BOARD-ID=2"));      // no terminator
    TEST_ASSERT_EQUAL_INT(-1, scan("PULSAR-BOARD-ID=2x;"));    // junk after digit
    TEST_ASSERT_EQUAL_INT(-1, scan("PULSAR-BOARD-ID=1234;"));  // too many digits
    TEST_ASSERT_EQUAL_INT(-1, scan("PULSAR-BOARD-I=2;"));      // wrong prefix
}

void test_a_false_start_does_not_hide_a_real_tag(void) {
    TEST_ASSERT_EQUAL_INT(1, scan("PULSAR-BPULSAR-BOARD-ID=1;"));
    TEST_ASSERT_EQUAL_INT(2, scan("PULSAR-BOARD-ID=x;PULSAR-BOARD-ID=2;"));
}

void test_the_first_tag_wins(void) {
    TEST_ASSERT_EQUAL_INT(1, scan("PULSAR-BOARD-ID=1;PULSAR-BOARD-ID=2;"));
}

int main(int, char**) {
    UNITY_BEGIN();
    RUN_TEST(test_finds_the_tag_in_one_chunk);
    RUN_TEST(test_finds_a_tag_split_across_chunks);
    RUN_TEST(test_no_tag_is_minus_one);
    RUN_TEST(test_malformed_tags_are_ignored);
    RUN_TEST(test_a_false_start_does_not_hide_a_real_tag);
    RUN_TEST(test_the_first_tag_wins);
    return UNITY_END();
}
