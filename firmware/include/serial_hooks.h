/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Copyright (C) 2026 Pulsar Trigger contributors
 */

#pragma once

/// Call from loop(): handles single-key test commands on the USB serial
/// console (r, t, s, o, i, ? - see serial_hooks.cpp).
void serial_hooks_poll();
