/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Copyright (C) 2026 Pulsar Trigger contributors
 */

package com.ehrocha.pulsar.widget

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.glance.appwidget.GlanceAppWidgetManager
import com.ehrocha.pulsar.R
import com.ehrocha.pulsar.ui.theme.DarkColorScheme
import kotlinx.coroutines.launch

/**
 * Per-widget configure screen — opens on add (when configuration_optional
 * is off) or via the launcher's reconfigure affordance on long-press for
 * existing widgets (widgetFeatures="reconfigurable").
 *
 * Currently exposes just the background-opacity slider (a global pref, not
 * per-widget — all instances share the value). On Done we update every
 * Glance instance so the change shows immediately.
 */
class DashboardWidgetConfigureActivity : ComponentActivity() {
    private var appWidgetId: Int = AppWidgetManager.INVALID_APPWIDGET_ID

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Default to cancelled — if the user backs out, the launcher won't
        // finish placing the widget (when invoked on add).
        setResult(Activity.RESULT_CANCELED)
        appWidgetId = intent?.extras?.getInt(
            AppWidgetManager.EXTRA_APPWIDGET_ID,
            AppWidgetManager.INVALID_APPWIDGET_ID,
        ) ?: AppWidgetManager.INVALID_APPWIDGET_ID

        enableEdgeToEdge()
        setContent {
            MaterialTheme(colorScheme = DarkColorScheme) {
                Surface(
                    modifier = Modifier
                        .fillMaxSize()
                        .systemBarsPadding(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    Scaffold(
                        topBar = {
                            TopAppBar(title = {
                                Text(
                                    stringResource(R.string.widget_configure_title),
                                    fontWeight = FontWeight.SemiBold,
                                )
                            })
                        },
                    ) { pad ->
                        ConfigureBody(modifier = Modifier.padding(pad))
                    }
                }
            }
        }
    }

    @Composable
    private fun ConfigureBody(modifier: Modifier = Modifier) {
        val context = LocalContext.current
        val scope = rememberCoroutineScope()
        var alpha by remember {
            mutableStateOf(DashboardSnapshotStore.backgroundAlpha(context))
        }
        Column(
            modifier = modifier
                .fillMaxSize()
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                stringResource(R.string.widget_appearance_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                stringResource(R.string.widget_bg_opacity_label, (alpha * 100).toInt()),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Slider(
                value = alpha,
                onValueChange = { alpha = it },
                onValueChangeFinished = {
                    DashboardSnapshotStore.setBackgroundAlpha(context, alpha)
                },
                valueRange = 0f..1f,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = {
                    DashboardSnapshotStore.setBackgroundAlpha(context, alpha)
                    scope.launch {
                        runCatching {
                            GlanceAppWidgetManager(context)
                                .getGlanceIds(DashboardWidget::class.java)
                                .forEach { id ->
                                    DashboardWidget().update(context, id)
                                }
                        }
                        val result = Intent().putExtra(
                            AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId,
                        )
                        setResult(Activity.RESULT_OK, result)
                        finish()
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
            ) {
                Text(stringResource(R.string.done))
            }
        }
    }
}

