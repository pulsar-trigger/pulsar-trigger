package com.ehrocha.pulsar.viewmodel

import android.app.Application
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.ParcelUuid
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ehrocha.pulsar.ble.*
import com.ehrocha.pulsar.service.PulsarNotificationService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

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

    // ── Astro params ─────────────────────────────────────────────────────
    val astroFocalLength = MutableStateFlow(24)       // mm
    val astroCropFactor = MutableStateFlow(1.5f)      // APS-C default
    val astroRuleDivisor = MutableStateFlow(500)      // 500 or 400
    val astroShotCount = MutableStateFlow(0)          // 0 = infinite
    val astroDelayMs = MutableStateFlow(5000L)
    val astroGapMs = MutableStateFlow(2000L)          // gap between shots

    // ── BLE Scan ─────────────────────────────────────────────────────────
    private val btManager = app.getSystemService(BluetoothManager::class.java)
    private val scanner = btManager?.adapter?.bluetoothLeScanner

    // ── Notification / cancel ────────────────────────────────────────────
    private val cancelReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            if (intent.action == PulsarNotificationService.ACTION_CANCEL) {
                stop()
            }
        }
    }

    init {
        app.registerReceiver(
            cancelReceiver,
            IntentFilter(PulsarNotificationService.ACTION_CANCEL),
            Context.RECEIVER_NOT_EXPORTED,
        )

        // Auto-update notification as status frames arrive
        viewModelScope.launch {
            status.collect { frame ->
                if (frame != null && frame.state == DeviceState.RUNNING) {
                    updateNotification()
                } else if (frame != null && frame.state == DeviceState.IDLE) {
                    dismissNotification()
                }
            }
        }
    }

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
            TriggerMode.ASTRO -> {
                val exposureS = astroRuleDivisor.value.toDouble() / (astroFocalLength.value * astroCropFactor.value)
                val exposureMs = (exposureS * 1000).toLong().coerceAtLeast(100)
                val intervalMs = exposureMs + astroGapMs.value
                CommandBuilder.setAstro(
                    intervalMs, exposureMs, astroShotCount.value, astroDelayMs.value
                )
            }
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
        updateNotification()
    }

    fun stop() {
        bleManager.sendCommand(CommandBuilder.stop())
        dismissNotification()
    }

    fun singleShot() {
        bleManager.sendCommand(CommandBuilder.shutter())
    }

    /** Press & Hold: shutter open on down */
    fun shutterDown() {
        sendConfig()
        bleManager.sendCommand(CommandBuilder.start())
    }

    /** Press & Hold: shutter close on up */
    fun shutterUp() {
        bleManager.sendCommand(CommandBuilder.stop())
    }

    // ── Notification helpers ─────────────────────────────────────────────
    fun updateNotification() {
        val app = getApplication<Application>()
        val s = status.value
        val intent = Intent(app, PulsarNotificationService::class.java).apply {
            putExtra(PulsarNotificationService.EXTRA_MODE, _currentMode.value.name.replace('_', ' '))
            putExtra(PulsarNotificationService.EXTRA_SHOTS, s?.shotsTaken ?: 0)
            putExtra(PulsarNotificationService.EXTRA_TOTAL, shotCount.value)
            putExtra(PulsarNotificationService.EXTRA_STATE, s?.state?.name ?: "RUNNING")
        }
        app.startForegroundService(intent)
    }

    private fun dismissNotification() {
        val app = getApplication<Application>()
        app.stopService(Intent(app, PulsarNotificationService::class.java))
    }

    override fun onCleared() {
        super.onCleared()
        stopScan()
        dismissNotification()
        try {
            getApplication<Application>().unregisterReceiver(cancelReceiver)
        } catch (_: Exception) {}
        bleManager.disconnectDevice()
    }
}
