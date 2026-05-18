/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Copyright (C) 2026 Pulsar Trigger contributors
 */

package com.ehrocha.pulsar.ui.screens

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ehrocha.pulsar.R
import com.ehrocha.pulsar.transport.ccapi.CcapiTransport
import com.ehrocha.pulsar.ui.components.PulsarTopBar
import com.ehrocha.pulsar.viewmodel.PulsarViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

private const val ROI_PX = 32
private const val FRAME_INTERVAL_MS = 150L  // ~6-7 fps target

/**
 * Star Focus Assist — live view from the Canon body, tap a star, watch
 * sharpness as you walk focus near/far. Useful for nailing pinpoint focus
 * on stars before kicking off an astro run. CCAPI-only; the BLE/ESP32 path
 * has no concept of live view or focus drive.
 *
 * Behavior:
 *  - Starts `/shooting/liveview` on enter, stops on leave or disconnect.
 *  - Polls `/shooting/liveview/flip` at ~6 fps for JPEG frames.
 *  - User taps a point on the frame → ROI center stored in bitmap coords.
 *  - Each frame: extracts a [ROI_PX]² box around the ROI, computes the peak
 *    luminance, displays it. Sharp star = high peak; defocused = lower.
 *  - Six drivefocus buttons step the lens motor in/out at three step sizes.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StarFocusScreen(
    vm: PulsarViewModel,
    onBack: () -> Unit,
) {
    val transport by vm.canonTransport.collectAsState()
    val t = transport
    if (t == null) {
        // Defensive: the menu tile is gated on canonTransport != null, but
        // if the user disconnects while on this screen we drop back.
        LaunchedEffect(Unit) { onBack() }
        return
    }

    var frame by remember { mutableStateOf<Bitmap?>(null) }
    var roiCenter by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    var sharpness by remember { mutableIntStateOf(0) }
    var displayedSize by remember { mutableStateOf<IntSize?>(null) }
    var liveViewError by remember { mutableStateOf<String?>(null) }

    // Stop live view on screen leave. DisposableEffect can't suspend, so
    // the VM provides a fire-and-forget stop that runs in viewModelScope.
    DisposableEffect(t) {
        onDispose { vm.stopCanonLiveView() }
    }

    // Start liveview + run the frame loop.
    LaunchedEffect(t) {
        val started = t.startLiveView()
        if (!started) {
            liveViewError = "start_failed"
            return@LaunchedEffect
        }
        while (isActive) {
            val bytes = t.getLiveViewFrame()
            if (bytes != null && bytes.isNotEmpty()) {
                val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                if (bmp != null) {
                    frame = bmp
                    roiCenter?.let { (cx, cy) ->
                        sharpness = computePeakLuminance(bmp, cx, cy, ROI_PX)
                    }
                }
            }
            delay(FRAME_INTERVAL_MS)
        }
    }

    Scaffold(
        topBar = {
            PulsarTopBar(
                title = stringResource(R.string.star_focus_title),
                onBack = onBack,
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Live-view image
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color.Black)
                    .onSizeChanged { displayedSize = it },
                contentAlignment = Alignment.Center,
            ) {
                val bmp = frame
                when {
                    liveViewError != null -> Text(
                        stringResource(R.string.star_focus_start_failed),
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(16.dp),
                    )
                    bmp == null -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.height(12.dp))
                        Text(
                            stringResource(R.string.star_focus_starting),
                            color = Color.White.copy(alpha = 0.7f),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    else -> {
                        Image(
                            bitmap = bmp.asImageBitmap(),
                            contentDescription = stringResource(R.string.star_focus_liveview_cd),
                            contentScale = ContentScale.Fit,
                            modifier = Modifier
                                .fillMaxSize()
                                .pointerInput(bmp.width, bmp.height) {
                                    detectTapGestures { tap ->
                                        val (bx, by) = viewToBitmap(
                                            tap.x, tap.y,
                                            size.width.toFloat(), size.height.toFloat(),
                                            bmp.width, bmp.height,
                                        )
                                        if (bx in 0 until bmp.width && by in 0 until bmp.height) {
                                            roiCenter = bx to by
                                            sharpness = computePeakLuminance(bmp, bx, by, ROI_PX)
                                        }
                                    }
                                },
                        )
                        // ROI overlay
                        val ds = displayedSize
                        val roi = roiCenter
                        if (roi != null && ds != null) {
                            RoiOverlay(
                                roiX = roi.first, roiY = roi.second,
                                bmpW = bmp.width, bmpH = bmp.height,
                                viewW = ds.width, viewH = ds.height,
                            )
                        }
                    }
                }
            }

            // Sharpness readout
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainer,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Default.Star,
                        contentDescription = null,
                        tint = if (roiCenter != null) MaterialTheme.colorScheme.primary
                               else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            stringResource(R.string.star_focus_sharpness_label),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            if (roiCenter == null)
                                stringResource(R.string.star_focus_tap_to_mark)
                            else "$sharpness / 255",
                            fontWeight = FontWeight.Bold,
                            fontSize = 22.sp,
                        )
                    }
                }
            }

            // Drive-focus row: 3 near, 3 far. 1 = fine, 3 = coarse.
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainer,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Row(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            stringResource(R.string.star_focus_near),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            stringResource(R.string.star_focus_far),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.End,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        FocusBtn("«««", "near3", t, Modifier.weight(1f))
                        FocusBtn("««", "near2", t, Modifier.weight(1f))
                        FocusBtn("«", "near1", t, Modifier.weight(1f))
                        FocusBtn("»", "far1", t, Modifier.weight(1f))
                        FocusBtn("»»", "far2", t, Modifier.weight(1f))
                        FocusBtn("»»»", "far3", t, Modifier.weight(1f))
                    }
                }
            }
            Text(
                stringResource(R.string.star_focus_lens_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }
    }
}

@Composable
private fun BoxScope.RoiOverlay(
    roiX: Int, roiY: Int,
    bmpW: Int, bmpH: Int,
    viewW: Int, viewH: Int,
) {
    val scale = minOf(viewW.toFloat() / bmpW, viewH.toFloat() / bmpH)
    val drawW = bmpW * scale
    val drawH = bmpH * scale
    val offX = (viewW - drawW) / 2f
    val offY = (viewH - drawH) / 2f
    val centerXpx = offX + roiX * scale
    val centerYpx = offY + roiY * scale
    val boxPx = ROI_PX * scale
    val density = LocalDensity.current
    Surface(
        color = Color.Transparent,
        border = BorderStroke(2.dp, Color.Yellow),
        shape = CircleShape,
        modifier = Modifier
            .offset(
                x = with(density) { (centerXpx - boxPx / 2f).toDp() },
                y = with(density) { (centerYpx - boxPx / 2f).toDp() },
            )
            .size(with(density) { boxPx.toDp() }),
    ) {}
}

@Composable
private fun FocusBtn(
    label: String,
    action: String,
    transport: CcapiTransport,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    OutlinedButton(
        onClick = { scope.launch { transport.driveFocus(action) } },
        modifier = modifier.height(48.dp),
        shape = RoundedCornerShape(8.dp),
        contentPadding = PaddingValues(horizontal = 4.dp),
    ) {
        Text(label, fontWeight = FontWeight.Bold)
    }
}

/** Map a tap (composable coords) to bitmap coords given a ContentScale.Fit
 *  image — the image is centered and may letterbox on one axis. */
private fun viewToBitmap(
    tx: Float, ty: Float,
    viewW: Float, viewH: Float,
    bmpW: Int, bmpH: Int,
): Pair<Int, Int> {
    val scale = minOf(viewW / bmpW, viewH / bmpH)
    val drawW = bmpW * scale
    val drawH = bmpH * scale
    val offX = (viewW - drawW) / 2f
    val offY = (viewH - drawH) / 2f
    return ((tx - offX) / scale).toInt() to ((ty - offY) / scale).toInt()
}

/** Peak luminance inside a [size]×[size] box centered at ([cx], [cy]) on
 *  [bmp]. Clamped to the bitmap bounds. Returns 0..255; higher = sharper
 *  star (more concentrated light). */
private fun computePeakLuminance(bmp: Bitmap, cx: Int, cy: Int, size: Int): Int {
    val half = size / 2
    val x0 = (cx - half).coerceAtLeast(0)
    val y0 = (cy - half).coerceAtLeast(0)
    val x1 = (cx + half).coerceAtMost(bmp.width - 1)
    val y1 = (cy + half).coerceAtMost(bmp.height - 1)
    val w = x1 - x0 + 1
    val h = y1 - y0 + 1
    if (w <= 0 || h <= 0) return 0
    val pixels = IntArray(w * h)
    bmp.getPixels(pixels, 0, w, x0, y0, w, h)
    var peak = 0
    for (p in pixels) {
        val r = (p shr 16) and 0xFF
        val g = (p shr 8) and 0xFF
        val b = p and 0xFF
        // Integer approximation of 0.299R + 0.587G + 0.114B (BT.601).
        val luma = (r * 77 + g * 150 + b * 29) shr 8
        if (luma > peak) peak = luma
    }
    return peak
}
