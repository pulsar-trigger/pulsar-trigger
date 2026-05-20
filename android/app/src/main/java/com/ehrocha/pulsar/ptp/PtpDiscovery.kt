/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Copyright (C) 2026 Pulsar Trigger contributors
 */

package com.ehrocha.pulsar.ptp

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Watches Android's USB host stack for plugged-in cameras with a PTP
 * interface and surfaces them as [cameras]. Scan-screen subscribes to that
 * flow to render a "USB cameras" section.
 *
 * Filtering policy: any USB device that exposes a Still-Image-class
 * interface with subclass 1 / protocol 1 (PTP). We don't filter by vendor
 * ID — Canon, Nikon, Sony, Fuji etc all speak the same baseline PTP and
 * any of them would benefit from being listed; vendor-specific operations
 * are negotiated per-camera after connect.
 *
 * Initial enumeration runs in [refresh] (also called on first attach).
 * USB attach/detach broadcasts update the list in real time — no polling.
 */
class PtpDiscovery(private val ctx: Context) {

    private val usb = ctx.getSystemService(Context.USB_SERVICE) as UsbManager

    private val _cameras = MutableStateFlow<List<UsbDevice>>(emptyList())
    val cameras: StateFlow<List<UsbDevice>> = _cameras

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, intent: Intent) {
            when (intent.action) {
                UsbManager.ACTION_USB_DEVICE_ATTACHED,
                UsbManager.ACTION_USB_DEVICE_DETACHED -> refresh()
            }
        }
    }

    private var started = false

    /** Begin watching. Safe to call multiple times. */
    fun start() {
        if (started) return
        started = true
        val filter = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ctx.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            ctx.registerReceiver(receiver, filter)
        }
        refresh()
        Log.d(TAG, "started — initial cameras=${_cameras.value.size}")
    }

    /** Stop watching. Subsequent [start] re-registers. */
    fun stop() {
        if (!started) return
        started = false
        runCatching { ctx.unregisterReceiver(receiver) }
        _cameras.value = emptyList()
    }

    /** Re-enumerate attached USB devices, update [cameras]. */
    fun refresh() {
        val matched = usb.deviceList.values.filter { hasPtpInterface(it) }
        _cameras.value = matched
        Log.d(TAG, "refresh: ${matched.size} PTP camera(s) attached " +
                   "(of ${usb.deviceList.size} total USB devices)")
    }

    companion object {
        private const val TAG = "PtpDiscovery"

        fun hasPtpInterface(device: UsbDevice): Boolean {
            for (i in 0 until device.interfaceCount) {
                val iface = device.getInterface(i)
                if (iface.interfaceClass == UsbConstants.USB_CLASS_STILL_IMAGE &&
                    iface.interfaceSubclass == 0x01 &&
                    iface.interfaceProtocol == 0x01) return true
            }
            return false
        }
    }
}
