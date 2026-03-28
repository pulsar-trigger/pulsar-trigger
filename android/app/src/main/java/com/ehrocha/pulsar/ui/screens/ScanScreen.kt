package com.ehrocha.pulsar.ui.screens

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.ehrocha.pulsar.viewmodel.PulsarViewModel

@SuppressLint("MissingPermission")
@Composable
fun ScanScreen(vm: PulsarViewModel, onConnected: () -> Unit) {
    val scanning by vm.scanning.collectAsState()
    val devices by vm.devices.collectAsState()
    val connected by vm.connected.collectAsState()

    if (connected) {
        onConnected()
        return
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp)
    ) {
        Text("Pulsar", style = MaterialTheme.typography.headlineLarge)
        Spacer(Modifier.height(8.dp))
        Text("Scan for your Pulsar device", style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(24.dp))

        Button(
            onClick = { if (scanning) vm.stopScan() else vm.startScan() },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(if (scanning) "Stop Scan" else "Scan for Devices")
        }

        if (scanning) {
            Spacer(Modifier.height(16.dp))
            LinearProgressIndicator(Modifier.fillMaxWidth())
        }

        Spacer(Modifier.height(16.dp))

        LazyColumn {
            items(devices) { device ->
                DeviceRow(device) { vm.connectTo(device) }
            }
        }
    }
}

@SuppressLint("MissingPermission")
@Composable
private fun DeviceRow(device: BluetoothDevice, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(device.name ?: "Unknown", style = MaterialTheme.typography.bodyLarge)
                Text(device.address, style = MaterialTheme.typography.bodySmall)
            }
            Text("Connect →", color = MaterialTheme.colorScheme.primary)
        }
    }
}
