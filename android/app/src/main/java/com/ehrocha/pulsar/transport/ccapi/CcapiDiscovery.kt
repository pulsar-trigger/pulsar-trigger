/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Copyright (C) 2026 Pulsar Trigger contributors
 */

package com.ehrocha.pulsar.transport.ccapi

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.net.DatagramPacket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.net.SocketTimeoutException

/**
 * SSDP listener for Canon CCAPI cameras. Joins the UPnP multicast group on
 * the local network, sends an M-SEARCH for Canon's CCAPI service, and
 * listens for both M-SEARCH responses and unsolicited NOTIFY advertisements.
 * Each unique camera is parsed via [CameraDescription.fetch] and appended
 * to [cameras].
 *
 * Holds a `WifiManager.MulticastLock` while running — Android drops multicast
 * traffic to apps that don't acquire one. The lock is released on [stop].
 *
 * Discovery runs alongside BLE scanning. The scan card UI merges both
 * sources into one list.
 */
class CcapiDiscovery(private val context: Context) {

    companion object {
        private const val TAG = "CcapiDiscovery"
        private const val SSDP_GROUP = "239.255.255.250"
        private const val SSDP_PORT = 1900
        /** Canon-specific service type the camera advertises. */
        private const val SERVICE_TYPE =
            "urn:schemas-canon-com:service:ICPO-CameraControlAPIService:1"
        private const val MULTICAST_LOCK_TAG = "PulsarCcapiDiscovery"
        private const val SOCKET_TIMEOUT_MS = 1_500
    }

    private val _scanning = MutableStateFlow(false)
    val scanning: StateFlow<Boolean> = _scanning

    private val _cameras = MutableStateFlow<List<CanonCamera>>(emptyList())
    val cameras: StateFlow<List<CanonCamera>> = _cameras

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var discoveryJob: Job? = null
    private var multicastLock: WifiManager.MulticastLock? = null

    fun start() {
        if (discoveryJob?.isActive == true) return
        _scanning.value = true
        _cameras.value = emptyList()

        val wifi = context.applicationContext
            .getSystemService(Context.WIFI_SERVICE) as? WifiManager
        multicastLock = wifi?.createMulticastLock(MULTICAST_LOCK_TAG)?.apply {
            setReferenceCounted(false)
            acquire()
        }

        discoveryJob = scope.launch { runDiscovery() }
    }

    fun stop() {
        discoveryJob?.cancel()
        discoveryJob = null
        multicastLock?.takeIf { it.isHeld }?.release()
        multicastLock = null
        _scanning.value = false
    }

    private suspend fun runDiscovery() {
        // CRITICAL: bind the multicast socket explicitly to the WiFi
        // interface. On Android when the phone joins a camera's AP, the
        // OS flags WiFi as "no internet" and may deliver multicast to the
        // wrong interface (or none at all) unless we pin it. Without this
        // discovery silently sees no packets even though the camera is
        // broadcasting normally.
        val wifiInterface = pickWifiInterface()
        Log.i(TAG, "Multicast bound to interface: ${wifiInterface?.name ?: "DEFAULT (may not work on camera AP)"}")

        val groupAddr = InetAddress.getByName(SSDP_GROUP)
        val groupSocketAddr = InetSocketAddress(groupAddr, SSDP_PORT)

        val socket = try {
            MulticastSocket(SSDP_PORT).apply {
                reuseAddress = true
                soTimeout = SOCKET_TIMEOUT_MS
                if (wifiInterface != null) {
                    networkInterface = wifiInterface
                    joinGroup(groupSocketAddr, wifiInterface)
                } else {
                    @Suppress("DEPRECATION")
                    joinGroup(groupAddr)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to open multicast socket", e)
            _scanning.value = false
            return
        }

        try {
            // Active probe — kicks the camera into responding immediately
            // instead of waiting for the next periodic NOTIFY.
            sendMSearch(socket)
            val buf = ByteArray(2048)
            while (currentCoroutineContext().isActive) {
                try {
                    val pkt = DatagramPacket(buf, buf.size)
                    socket.receive(pkt)
                    val msg = String(pkt.data, 0, pkt.length)
                    Log.v(TAG, "SSDP packet from ${pkt.address.hostAddress}:${pkt.port} (${pkt.length} bytes)")
                    handleMessage(msg)
                } catch (_: SocketTimeoutException) {
                    // No packet within window — keep listening
                } catch (e: Exception) {
                    if (currentCoroutineContext().isActive) {
                        Log.w(TAG, "receive error", e)
                    }
                }
            }
        } finally {
            try {
                if (wifiInterface != null) {
                    socket.leaveGroup(groupSocketAddr, wifiInterface)
                } else {
                    @Suppress("DEPRECATION")
                    socket.leaveGroup(groupAddr)
                }
            } catch (_: Exception) {}
            socket.close()
        }
    }

    /** Find the NetworkInterface backing the currently-connected WiFi network.
     *  Returns null if no WiFi network is up or the API is unavailable —
     *  caller falls back to default-interface multicast (typically won't work
     *  on camera AP, but kept for forward compat). */
    private fun pickWifiInterface(): NetworkInterface? {
        val cm = context.applicationContext
            .getSystemService(ConnectivityManager::class.java) ?: return null
        @Suppress("DEPRECATION")
        val wifiNetwork = cm.allNetworks.firstOrNull { net ->
            cm.getNetworkCapabilities(net)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        } ?: return null
        val ifName = cm.getLinkProperties(wifiNetwork)?.interfaceName ?: return null
        return NetworkInterface.getByName(ifName)
    }

    private fun sendMSearch(socket: MulticastSocket) {
        val payload = buildString {
            append("M-SEARCH * HTTP/1.1\r\n")
            append("HOST: $SSDP_GROUP:$SSDP_PORT\r\n")
            append("MAN: \"ssdp:discover\"\r\n")
            append("MX: 3\r\n")
            append("ST: $SERVICE_TYPE\r\n")
            append("\r\n")
        }.toByteArray()
        try {
            socket.send(DatagramPacket(
                payload, payload.size,
                InetAddress.getByName(SSDP_GROUP), SSDP_PORT,
            ))
            Log.i(TAG, "Sent M-SEARCH")
        } catch (e: Exception) {
            Log.w(TAG, "M-SEARCH send failed", e)
        }
    }

    private fun handleMessage(raw: String) {
        // SSDP payloads use CRLF; we accept both. Look for Canon's service
        // type either in the M-SEARCH response (ST:) or NOTIFY (NT:).
        val headers = raw.lineSequence()
            .map { it.trim() }
            .filter { ":" in it }
            .associate {
                val (k, v) = it.split(":", limit = 2)
                k.trim().lowercase() to v.trim()
            }
        val typed = headers["st"] ?: headers["nt"]
        // Require both the Canon namespace AND the specific service so we
        // don't accidentally pick up other UPnP devices that happen to share
        // a substring (e.g. random IoT vendors using "Camera" service names).
        if (typed == null ||
            !typed.contains("schemas-canon-com", ignoreCase = true) ||
            !typed.contains("ICPO-CameraControlAPIService", ignoreCase = true)) {
            if (typed != null) Log.v(TAG, "Filtered out non-Canon SSDP service: $typed")
            return
        }
        val location = headers["location"] ?: return

        scope.launch {
            val camera = CameraDescription.fetch(location) ?: return@launch
            val existing = _cameras.value
            if (existing.any { it.udn == camera.udn }) return@launch
            _cameras.value = existing + camera
            Log.i(TAG, "Discovered ${camera.friendlyName} @ ${camera.ipAddress}")
        }
    }
}
