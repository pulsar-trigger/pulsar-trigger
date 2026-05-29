/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Copyright (C) 2026 Pulsar Trigger contributors
 */

package com.ehrocha.pulsar.canonble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.ParcelUuid
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.UUID

/**
 * BLE scanner filtered on Canon's BR-E1 remote service. Surfaces cameras
 * advertising service [SERVICE_UUID] as [cameras]. The ScanScreen
 * subscribes to that flow to render a "Canon BLE remotes" section
 * parallel to the existing "Bluetooth devices" (Pulsar ESP32) and
 * "USB cameras" (PTP) sections.
 *
 * Concurrent with [com.ehrocha.pulsar.ble.BleController]'s Pulsar-ESP32
 * scan — Android's BLE scanner handles multiple ScanCallbacks; each filter
 * is independent. Bodies that don't advertise [SERVICE_UUID] (= every
 * non-BR-E1-capable Canon and all non-Canon BLE devices) are dropped here.
 */
class CanonBleDiscovery(private val ctx: Context) {

    companion object {
        private const val TAG = "CanonBleDiscovery"
        /** Canon BR-E1 remote-control service. Same UUID across all bodies
         *  in the BR-E1 compatibility list (EOS R/RP/R5/R6, M50, 200D,
         *  77D, 800D, Ra, 850D, M200, 6D II, G7X III, G5X II). */
        val SERVICE_UUID: UUID = UUID.fromString("00050000-0000-1000-0000-d8492fffa821")

        /** Canon smartphone-mode service (advertised when the body's BT is
         *  set to "Connect to smartphone" / register-a-device). A different,
         *  richer protocol than BR-E1 — and the only BLE path that fires the
         *  EOS R-series. See docs/canon-ble.md. We scan for BOTH because a
         *  body in smartphone mode may still advertise only [SERVICE_UUID]
         *  in its (tiny) adv packet — the actual protocol is detected after
         *  connect, not from the advertisement. */
        val SMART_SERVICE_UUID: UUID = UUID.fromString("00010000-0000-1000-0000-d8492fffa821")
    }

    private val btManager = ctx.getSystemService(BluetoothManager::class.java)
    private val scanner get() = btManager?.adapter?.bluetoothLeScanner

    private val _cameras = MutableStateFlow<List<BluetoothDevice>>(emptyList())
    val cameras: StateFlow<List<BluetoothDevice>> = _cameras

    private val _scanning = MutableStateFlow(false)
    val scanning: StateFlow<Boolean> = _scanning

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            // Belt-and-suspenders: some BLE stacks don't honour the offloaded
            // service-UUID ScanFilter and deliver *every* advertisement (phones,
            // earbuds, etc.). Re-verify the device actually advertises a Canon
            // service before listing it as a camera.
            val isCanon = result.scanRecord?.serviceUuids?.any {
                it.uuid == SERVICE_UUID || it.uuid == SMART_SERVICE_UUID
            } ?: false
            if (!isCanon) return
            val dev = result.device
            val current = _cameras.value
            if (current.none { it.address == dev.address }) {
                _cameras.value = current + dev
                CanonBleLog.d(TAG, "found Canon BLE camera ${dev.address}")
            }
        }

        override fun onScanFailed(errorCode: Int) {
            CanonBleLog.w(TAG, "scan failed code=$errorCode")
            _scanning.value = false
        }
    }

    /** Begin scanning. Safe to call multiple times; subsequent calls no-op
     *  while a scan is already running. */
    @SuppressLint("MissingPermission")
    fun start() {
        if (_scanning.value) return
        val s = scanner ?: run {
            CanonBleLog.w(TAG, "no BluetoothLeScanner — adapter off or permission missing")
            return
        }
        // Scan for BOTH Canon services (OR semantics): BR-E1 remote (00050000)
        // AND smartphone-mode (00010000). A body advertises one or the other
        // depending on its Bluetooth menu state — the RP in "add a device"
        // smartphone pairing advertises 00010000, which the single BR-E1 filter
        // missed. The protocol is still auto-detected after connect.
        val filters = listOf(
            ScanFilter.Builder().setServiceUuid(ParcelUuid(SERVICE_UUID)).build(),
            ScanFilter.Builder().setServiceUuid(ParcelUuid(SMART_SERVICE_UUID)).build(),
        )
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        try {
            s.startScan(filters, settings, scanCallback)
            _scanning.value = true
            CanonBleLog.d(TAG, "scan started (BR-E1 + smartphone service filters)")
        } catch (e: SecurityException) {
            CanonBleLog.w(TAG, "scan blocked by permission: ${e.message}")
        }
    }

    /** Stop scanning. Clears the discovered list. */
    @SuppressLint("MissingPermission")
    fun stop() {
        if (!_scanning.value) return
        try {
            scanner?.stopScan(scanCallback)
        } catch (e: SecurityException) {
            CanonBleLog.w(TAG, "stopScan blocked: ${e.message}")
        }
        _scanning.value = false
        _cameras.value = emptyList()
    }
}
