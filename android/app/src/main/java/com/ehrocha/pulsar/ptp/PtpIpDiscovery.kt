/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Copyright (C) 2026 Pulsar Trigger contributors
 */

package com.ehrocha.pulsar.ptp

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * mDNS browser for Canon EOS bodies in PTP/IP mode (`_ptp._tcp.local`).
 * Cameras in "Remote Control (EOS Utility)" Wi-Fi mode advertise themselves
 * on the local network; pop the lid on this discovery and they show up in
 * [cameras] as soon as they're announcable.
 *
 * Sister of [com.ehrocha.pulsar.transport.ccapi.CcapiDiscovery] —
 * scan-screen-visible lifecycle, idempotent start/stop, lossy-but-correct
 * resolver that drops entries whose IP can't be looked up. NSD resolves are
 * one-shot per service, so the resolver listener is rebuilt per service to
 * avoid Android's "listener already in use" error.
 */
class PtpIpDiscovery(ctx: Context) {

    private val nsd = ctx.getSystemService(Context.NSD_SERVICE) as NsdManager

    private val _cameras = MutableStateFlow<List<PtpIpCamera>>(emptyList())
    val cameras: StateFlow<List<PtpIpCamera>> = _cameras

    private var discoveryListener: NsdManager.DiscoveryListener? = null

    fun start() {
        if (discoveryListener != null) return
        val l = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {
                Log.i(TAG, "discovery started ($serviceType)")
            }
            override fun onDiscoveryStopped(serviceType: String) {
                Log.i(TAG, "discovery stopped ($serviceType)")
            }
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.w(TAG, "start discovery failed: $errorCode")
            }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.w(TAG, "stop discovery failed: $errorCode")
            }
            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                Log.i(TAG, "found '${serviceInfo.serviceName}' — resolving")
                resolve(serviceInfo)
            }
            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                val name = serviceInfo.serviceName
                _cameras.value = _cameras.value.filterNot { it.name == name }
            }
        }
        discoveryListener = l
        nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, l)
    }

    fun stop() {
        val l = discoveryListener ?: return
        runCatching { nsd.stopServiceDiscovery(l) }
        discoveryListener = null
    }

    private fun resolve(info: NsdServiceInfo) {
        val listener = object : NsdManager.ResolveListener {
            override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                Log.w(TAG, "resolve '${serviceInfo.serviceName}' failed: $errorCode")
            }
            override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                val host = serviceInfo.host?.hostAddress ?: return
                val cam = PtpIpCamera(
                    name = serviceInfo.serviceName,
                    host = host,
                    port = serviceInfo.port.takeIf { it > 0 } ?: PtpIpWire.PTPIP_PORT,
                )
                val now = _cameras.value.filterNot { it.name == cam.name } + cam
                _cameras.value = now.sortedBy { it.name }
                Log.i(TAG, "resolved $cam")
            }
        }
        runCatching { nsd.resolveService(info, listener) }
            .onFailure { Log.w(TAG, "resolveService threw: ${it.message}") }
    }

    companion object {
        private const val TAG = "PtpIpDiscovery"
        /** mDNS service type Canon EOS bodies advertise in PTP/IP mode. */
        private const val SERVICE_TYPE = "_ptp._tcp."
    }
}

/** Discovered PTP/IP camera. Identified by mDNS service name; address and
 *  port are the resolved socket endpoint. */
data class PtpIpCamera(
    val name: String,
    val host: String,
    val port: Int,
)
