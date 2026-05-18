/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Copyright (C) 2026 Pulsar Trigger contributors
 */

package com.ehrocha.pulsar.transport.ccapi

/**
 * A Canon CCAPI camera discovered on the local network via SSDP. Identity
 * is the [udn] (stable UUID); [accessUrl] is the CCAPI base URL we use for
 * subsequent HTTP requests.
 */
data class CanonCamera(
    /** Stable UPnP device UUID — survives IP changes, use as primary key. */
    val udn: String,
    /** Human-readable model name from the device description, e.g. "EOS R10". */
    val friendlyName: String,
    /** Optional user-set nickname configured on the camera body. */
    val nickname: String?,
    /** Camera's IP address on the current network. */
    val ipAddress: String,
    /** Port serving the CCAPI HTTP endpoint (varies by camera). */
    val port: Int,
    /** Base URL for CCAPI calls, e.g. `http://192.168.1.2:8080/ccapi/`. */
    val accessUrl: String,
)
