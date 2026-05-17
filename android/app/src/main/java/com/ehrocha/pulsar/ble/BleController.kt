/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Copyright (C) 2026 Pulsar Trigger contributors
 */

package com.ehrocha.pulsar.ble

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

/**
 * Thin owner of the BLE scan + connection pipeline. Wraps [PulsarBleManager]
 * and the Bluetooth LE scanner so the viewmodel doesn't have to know about
 * either. Re-exposes the BLE-side state flows under a stable surface:
 *
 *  - [scanning] / [devices]   — scan lifecycle
 *  - [connected] / [status] / [deviceInfo] / [rssi] — connection lifecycle
 *
 * Commands (`sendCommand`, `connect`, `disconnect`, `requestCacheRefresh`)
 * delegate to [PulsarBleManager]. The viewmodel multiplexes [status] with
 * its simulator status flow.
 *
 * Phase 2 of the refactor (`docs/refactor-plan.md`).
 */
class BleController(private val context: Context) {

    companion object { private const val TAG = "BleController" }

    private val btManager = context.getSystemService(BluetoothManager::class.java)
    // Resolved lazily — must not be cached at init time because Bluetooth
    // permissions may not yet be granted when this controller is created.
    private val scanner get() = btManager?.adapter?.bluetoothLeScanner

    /** Underlying BleManager. Exposed for components that need direct access
     *  (e.g. `FirmwareUpdateManager` for OTA writes). Prefer the methods on
     *  this class for general use. */
    val bleManager = PulsarBleManager(context)

    // ── Scan state ──────────────────────────────────────────────────────
    private val _scanning = MutableStateFlow(false)
    val scanning: StateFlow<Boolean> = _scanning

    private val _devices = MutableStateFlow<List<ScannedDevice>>(emptyList())
    val devices: StateFlow<List<ScannedDevice>> = _devices

    // ── Connection state (forwarded from BleManager) ────────────────────
    val connected: StateFlow<Boolean> = bleManager.connectionState
    val status: StateFlow<StatusFrame?> = bleManager.status
    val deviceInfo: StateFlow<DeviceInfo?> = bleManager.deviceInfo
    val rssi: StateFlow<Int?> = bleManager.rssi
    val latencyMs: StateFlow<Int?> = bleManager.latencyMs

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val dev = result.device
            val mfgData = result.scanRecord?.getManufacturerSpecificData(PULSAR_MFG_COMPANY_ID)
            val boardId = mfgData?.getOrNull(0)?.toInt()?.and(0xFF) ?: 0
            val chipModel = mfgData?.getOrNull(1)?.toInt()?.and(0xFF) ?: 0
            val fwMajor = mfgData?.getOrNull(2)?.toInt()?.and(0xFF) ?: 0
            val fwMinor = mfgData?.getOrNull(3)?.toInt()?.and(0xFF) ?: 0
            val scanned = ScannedDevice(
                device = dev,
                boardKind = BoardKind.fromId(boardId),
                chipModel = chipModel,
                fwMajor = fwMajor,
                fwMinor = fwMinor,
            )
            val current = _devices.value
            val existing = current.indexOfFirst { it.device.address == dev.address }
            _devices.value = if (existing >= 0) {
                // Replace if new entry carries richer info (mfg data not always
                // present in every advertisement packet).
                if (scanned.boardKind != BoardKind.UNKNOWN && current[existing].boardKind == BoardKind.UNKNOWN) {
                    current.toMutableList().also { it[existing] = scanned }
                } else current
            } else current + scanned
        }
    }

    /** Start a filtered LE scan for Pulsar devices. Clears the device list
     *  first; silently no-ops if the scanner is unavailable. */
    @SuppressLint("MissingPermission")
    fun startScan() {
        _devices.value = emptyList()
        _scanning.value = true

        val s = scanner
        if (s == null) {
            Log.w(TAG, "BLE scanner unavailable — Bluetooth off or permissions not granted")
            _scanning.value = false
            return
        }

        val filter = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid(PulsarUuids.SERVICE))
            .build()
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        try {
            s.startScan(listOf(filter), settings, scanCallback)
            Log.i(TAG, "BLE scan started")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start BLE scan", e)
            _scanning.value = false
        }
    }

    @SuppressLint("MissingPermission")
    fun stopScan() {
        try {
            scanner?.stopScan(scanCallback)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to stop BLE scan", e)
        }
        _scanning.value = false
    }

    @SuppressLint("MissingPermission")
    fun connect(device: BluetoothDevice) {
        stopScan()
        bleManager.connectDevice(device)
    }

    fun disconnect() {
        bleManager.disconnectDevice()
    }

    fun sendCommand(packet: ByteArray) {
        bleManager.sendCommand(packet)
    }

    fun requestCacheRefresh() {
        bleManager.requestCacheRefresh()
    }
}
