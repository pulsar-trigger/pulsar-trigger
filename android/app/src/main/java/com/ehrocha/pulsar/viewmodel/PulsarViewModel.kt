package com.ehrocha.pulsar.viewmodel

import android.app.Application
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.os.ParcelUuid
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import com.ehrocha.pulsar.ble.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class PulsarViewModel(app: Application) : AndroidViewModel(app) {

    companion object {
        private const val TAG = "PulsarVM"
    }

    private val bleManager = PulsarBleManager(app)

    // ── Scan state ───────────────────────────────────────────────────────
    private val _scanning = MutableStateFlow(false)
    val scanning: StateFlow<Boolean> = _scanning

    private val _devices = MutableStateFlow<List<BluetoothDevice>>(emptyList())
    val devices: StateFlow<List<BluetoothDevice>> = _devices

    // ── Connection state ─────────────────────────────────────────────────
    val connected: StateFlow<Boolean> = bleManager.connectionState
    val status: StateFlow<StatusFrame?> = bleManager.status

    // ── Mode config state ────────────────────────────────────────────────
    private val _currentMode = MutableStateFlow(TriggerMode.INTERVALOMETER)
    val currentMode: StateFlow<TriggerMode> = _currentMode

    // ── Intervalometer params ────────────────────────────────────────────
    val intervalMs = MutableStateFlow(5000L)
    val exposureMs = MutableStateFlow(200L)
    val shotCount = MutableStateFlow(0)       // 0 = infinite
    val delayMs = MutableStateFlow(0L)

    // ── Sound params ─────────────────────────────────────────────────────
    val soundThreshold = MutableStateFlow(512)
    val soundExposureMs = MutableStateFlow(200L)

    // ── Lightning params ─────────────────────────────────────────────────
    val lightningSensitivity = MutableStateFlow(3)
    val lightningExposureMs = MutableStateFlow(200L)

    // ── Laser params ─────────────────────────────────────────────────────
    val laserExposureMs = MutableStateFlow(200L)

    // ── BLE Scan ─────────────────────────────────────────────────────────
    private val btManager = app.getSystemService(BluetoothManager::class.java)
    private val scanner = btManager?.adapter?.bluetoothLeScanner

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val dev = result.device
            if (_devices.value.none { it.address == dev.address }) {
                _devices.value = _devices.value + dev
            }
        }
    }

    fun startScan() {
        _devices.value = emptyList()
        _scanning.value = true

        val filter = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid(PulsarUuids.SERVICE))
            .build()
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        scanner?.startScan(listOf(filter), settings, scanCallback)
        Log.i(TAG, "BLE scan started")
    }

    fun stopScan() {
        scanner?.stopScan(scanCallback)
        _scanning.value = false
    }

    fun connectTo(device: BluetoothDevice) {
        stopScan()
        bleManager.connectDevice(device)
    }

    fun disconnect() {
        bleManager.disconnectDevice()
    }

    // ── Commands ─────────────────────────────────────────────────────────
    fun selectMode(mode: TriggerMode) {
        _currentMode.value = mode
    }

    fun sendConfig() {
        val packet = when (_currentMode.value) {
            TriggerMode.INTERVALOMETER -> CommandBuilder.setIntervalometer(
                intervalMs.value, exposureMs.value, shotCount.value, delayMs.value
            )
            TriggerMode.SOUND -> CommandBuilder.setSound(
                soundThreshold.value, soundExposureMs.value
            )
            TriggerMode.LIGHTNING -> CommandBuilder.setLightning(
                lightningSensitivity.value, lightningExposureMs.value
            )
            TriggerMode.LASER -> CommandBuilder.setLaser(laserExposureMs.value)
            TriggerMode.HDR -> CommandBuilder.setHdr(listOf(100, 200, 400, 800, 1600))
            TriggerMode.PRESS_HOLD -> CommandBuilder.setPressHold()
            TriggerMode.PRESS_LOCK -> CommandBuilder.setPressLock()
        }
        bleManager.sendCommand(packet)
    }

    fun start() {
        sendConfig()
        bleManager.sendCommand(CommandBuilder.start())
    }

    fun stop() {
        bleManager.sendCommand(CommandBuilder.stop())
    }

    fun singleShot() {
        bleManager.sendCommand(CommandBuilder.shutter())
    }

    override fun onCleared() {
        super.onCleared()
        stopScan()
        bleManager.disconnectDevice()
    }
}
