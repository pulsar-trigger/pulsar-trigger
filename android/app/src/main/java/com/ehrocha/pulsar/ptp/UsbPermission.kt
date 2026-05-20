/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Copyright (C) 2026 Pulsar Trigger contributors
 */

package com.ehrocha.pulsar.ptp

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/** Request access to a USB device via Android's system permission dialog.
 *  Suspends until the user grants or denies, or returns true immediately
 *  if permission is already held. */
suspend fun requestUsbPermission(
    ctx: Context,
    usb: UsbManager,
    device: UsbDevice,
    action: String = "com.ehrocha.pulsar.USB_PERMISSION",
): Boolean {
    if (usb.hasPermission(device)) return true
    return suspendCancellableCoroutine { cont ->
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                if (intent.action != action) return
                val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                try { ctx.unregisterReceiver(this) } catch (_: Throwable) {}
                if (cont.isActive) cont.resume(granted)
            }
        }
        val filter = IntentFilter(action)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ctx.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            ctx.registerReceiver(receiver, filter)
        }
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        else
            PendingIntent.FLAG_UPDATE_CURRENT
        val pi = PendingIntent.getBroadcast(
            ctx, 0, Intent(action).setPackage(ctx.packageName), flags,
        )
        usb.requestPermission(device, pi)
        cont.invokeOnCancellation {
            try { ctx.unregisterReceiver(receiver) } catch (_: Throwable) {}
        }
    }
}
