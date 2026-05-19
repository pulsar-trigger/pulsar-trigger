/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Copyright (C) 2026 Pulsar Trigger contributors
 */

package com.ehrocha.pulsar.transport

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Test-only [CameraTransport] that records every call and exposes hooks
 *  for forcing failures. Used by `CanonRunner*Test` to verify the run
 *  loops drive any conforming transport with the right sequencing.
 *
 *  The recorded [calls] log lets a test assert "open → close → open → close"
 *  by reading `calls.map { it.tag }` against an expected sequence. */
class FakeTransport(
    override val kind: TransportKind = TransportKind.CCAPI,
    override val supportsBulb: Boolean = true,
    override val supportsSettings: Boolean = false,
    override val supportsLiveView: Boolean = false,
    override val supportsLensInfo: Boolean = false,
    override val supportsBatteryReadout: Boolean = false,
    /** Optional simulated wire-level latency on every call. Models the
     *  ~30 ms BLE write or ~80 ms CCAPI HTTP round-trip so virtual-time
     *  tests can assert "the gap between startBulb and stopBulb included
     *  exposureMs plus two transport hops." Default 0 keeps simple tests
     *  exact. */
    private val transportLatencyMs: Long = 0,
) : CameraTransport {

    data class Call(val tag: String, val arg: Any? = null, val atMs: Long = 0L)

    private val _label = MutableStateFlow("FakeCamera")
    override val label: StateFlow<String> = _label

    private val _connected = MutableStateFlow(true)
    override val connected: StateFlow<Boolean> = _connected

    /** Append-only log of every call the runner made. Tests assert against
     *  this rather than mocking individual methods. */
    val calls = mutableListOf<Call>()

    /** When non-null, the next matching call throws this instead of recording.
     *  Cleared after firing. Used by tests that verify the finally-block
     *  still releases the shutter when an exposure fails mid-flight. */
    var nextStartBulbError: Throwable? = null

    /** Count of stopBulb calls — handy for asserting the finally fired even
     *  when an outer cancellation aborted the loop. */
    val stopBulbCount: Int get() = calls.count { it.tag == "stopBulb" }

    override suspend fun release() {
        calls += Call("release")
        _connected.value = false
    }

    override suspend fun fireShutter(af: Boolean) {
        if (transportLatencyMs > 0) delay(transportLatencyMs)
        calls += Call("fireShutter", af)
    }

    override suspend fun setShutterMode(bulb: Boolean) {
        if (transportLatencyMs > 0) delay(transportLatencyMs)
        calls += Call("setShutterMode", bulb)
    }

    override suspend fun startBulb(af: Boolean) {
        if (transportLatencyMs > 0) delay(transportLatencyMs)
        nextStartBulbError?.let {
            nextStartBulbError = null
            throw it
        }
        calls += Call("startBulb", af)
    }

    override suspend fun stopBulb() {
        if (transportLatencyMs > 0) delay(transportLatencyMs)
        calls += Call("stopBulb")
    }

    override suspend fun stop() {
        calls += Call("stop")
    }

    /** Convenience: extract the sequence of operation tags (no args). */
    fun tags(): List<String> = calls.map { it.tag }
}
