/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Copyright (C) 2026 Pulsar Trigger contributors
 */

package com.ehrocha.pulsar.wear

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Stop
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material.Button
import androidx.wear.compose.material.ButtonDefaults
import androidx.wear.compose.material.Icon
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Scaffold
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.TimeText
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                WearRoot()
            }
        }
    }
}

@Composable
private fun WearRoot() {
    val state by WearStateListenerService.latest.collectAsState()
    val context = LocalContext.current
    val scope = CoroutineScope(Dispatchers.Main)

    Scaffold(
        timeText = { TimeText() },
    ) {
        val listState = rememberScalingLazyListState()
        ScalingLazyColumn(
            modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp),
            state = listState,
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            when {
                !state.connected -> item { Text(stringResource(R.string.wear_disconnected)) }
                !state.running -> item { IdleCard(state) }
                else -> {
                    item { RunningHeader(state) }
                    item { Spacer(Modifier.height(6.dp)) }
                    item {
                        Button(
                            onClick = {
                                scope.launch {
                                    val nodes = Wearable.getNodeClient(context)
                                        .connectedNodes.await()
                                    val client = Wearable.getMessageClient(context)
                                    nodes.forEach { node ->
                                        client.sendMessage(
                                            node.id,
                                            RunStateProtocol.PATH_CMD_STOP,
                                            ByteArray(0),
                                        )
                                    }
                                }
                            },
                            colors = ButtonDefaults.primaryButtonColors(
                                backgroundColor = MaterialTheme.colors.error,
                            ),
                        ) {
                            Icon(Icons.Default.Stop, contentDescription = null)
                            Spacer(Modifier.height(0.dp))
                            Text(stringResource(R.string.wear_stop))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun IdleCard(state: WearRunState) {
    Text(
        stringResource(R.string.wear_idle),
        fontSize = 16.sp,
        fontWeight = FontWeight.Bold,
    )
}

@Composable
private fun RunningHeader(state: WearRunState) {
    Text(
        state.modeLabel.ifBlank { stringResource(R.string.wear_running) },
        fontSize = 12.sp,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colors.primary,
    )
    Text(
        stringResource(R.string.wear_shots_fmt, state.shotsTaken, state.plannedShots),
        fontSize = 22.sp,
        fontWeight = FontWeight.Bold,
    )
    if (state.timeRemainingMs > 0) {
        Text(
            stringResource(R.string.wear_remaining_fmt, formatDuration(state.timeRemainingMs)),
            fontSize = 12.sp,
        )
    }
}

private fun formatDuration(ms: Long): String {
    val totalSec = ms / 1000
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return when {
        h > 0 -> "%d:%02d:%02d".format(h, m, s)
        else -> "%d:%02d".format(m, s)
    }
}

