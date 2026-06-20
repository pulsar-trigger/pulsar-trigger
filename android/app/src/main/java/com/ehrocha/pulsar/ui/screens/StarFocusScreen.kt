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
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.CenterFocusStrong
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
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
import com.ehrocha.pulsar.transport.CameraTransport
import com.ehrocha.pulsar.ui.components.PulsarTopBar
import com.ehrocha.pulsar.viewmodel.PulsarViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val ROI_PX = 32
private const val FRAME_INTERVAL_MS = 150L  // ~6-7 fps

private enum class Step(
    val labelRes: Int,
    val titleRes: Int,
    val icon: ImageVector,
) {
    PREP(R.string.star_focus_step_prep, R.string.star_focus_prep_title, Icons.Default.CameraAlt),
    AIM(R.string.star_focus_step_aim, R.string.star_focus_aim_title, Icons.Default.Star),
    FOCUS(R.string.star_focus_step_focus, R.string.star_focus_focus_title, Icons.Default.CenterFocusStrong),
    LOCK(R.string.star_focus_step_lock, R.string.star_focus_lock_title, Icons.Default.Lock),
}

/**
 * Star Focus Assist — 4-step wizard for nailing pinpoint focus on stars over
 * CCAPI before kicking off an astro run.
 *
 *  1. PREP   — instructions: lens to AF, camera mode dial to M (not Bulb).
 *  2. AIM    — live view appears, user taps a bright star.
 *  3. FOCUS  — live view + sharpness readout + drive-focus buttons.
 *  4. LOCK   — instructions: flip lens to MF if it has the switch; either way
 *              Pulsar will send `af: false` during the actual run.
 *
 * Live view starts on PREP→AIM and stops on AIM/FOCUS→LOCK (or screen exit).
 * Drive-focus on the RP requires the lens to be in AF mode on the body switch.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StarFocusScreen(
    vm: PulsarViewModel,
    onBack: () -> Unit,
) {
    // Star Focus runs on whichever Canon transport is active: CCAPI over
    // Wi-Fi, USB PTP, or PTP/IP. The wire-level live view + drive-focus
    // ops live behind the `CameraTransport` interface; this screen doesn't
    // care which one is on the other side.
    val canon by vm.canonCcapiTransport.collectAsState()
    val ptp by vm.ptpTransport.collectAsState()
    val ptpIp by vm.ptpIpTransport.collectAsState()
    val t: CameraTransport? = canon
        ?: ptp.takeIf { it?.supportsLiveView == true }
        ?: ptpIp.takeIf { it?.supportsLiveView == true }
    if (t == null) {
        // Defensive: the menu tile is gated, but if the user disconnects
        // mid-flow we bail.
        LaunchedEffect(Unit) { onBack() }
        return
    }

    var step by remember { mutableStateOf(Step.PREP) }
    var frame by remember { mutableStateOf<Bitmap?>(null) }
    var roiCenter by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    var sharpness by remember { mutableIntStateOf(0) }
    var displayedSize by remember { mutableStateOf<IntSize?>(null) }
    var liveViewError by remember { mutableStateOf<String?>(null) }

    // Live view runs only on AIM + FOCUS steps. Start on entry to AIM, stop
    // on transition out (or screen exit). Restarting on every recomposition
    // would thrash the camera, so we key on a derived flag.
    val liveViewActive = step == Step.AIM || step == Step.FOCUS
    LaunchedEffect(liveViewActive) {
        if (!liveViewActive) return@LaunchedEffect
        val started = t.startLiveView()
        if (!started) {
            // Surface what the camera actually told us so failures are
            // debuggable from the screen instead of needing logcat.
            liveViewError = t.lastLiveViewError ?: "start_failed"
            return@LaunchedEffect
        }
        liveViewError = null
        while (isActive) {
            val bytes = t.getLiveViewFrame()
            if (bytes != null && bytes.isNotEmpty()) {
                // JPEG decode + per-ROI luminance scan are CPU-bound; off-
                // load them to a worker thread so the Compose recomposition
                // pass and the polling delay aren't blocked.
                val (bmp, sharp) = withContext(Dispatchers.Default) {
                    val b = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    val s = roiCenter?.let { (cx, cy) ->
                        if (b != null) computePeakLuminance(b, cx, cy, ROI_PX)
                        else null
                    }
                    b to s
                }
                if (bmp != null) {
                    frame = bmp
                    if (sharp != null) sharpness = sharp
                }
            }
            delay(FRAME_INTERVAL_MS)
        }
    }
    // Belt-and-braces: stop live view when leaving the screen entirely.
    DisposableEffect(t) {
        onDispose { vm.stopCanonLiveView() }
    }
    // Also stop when we move to LOCK explicitly so the user gets battery back
    // while reading the lock instructions.
    LaunchedEffect(step) {
        if (step == Step.LOCK) vm.stopCanonLiveView()
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
                .fillMaxSize(),
        ) {
            StepIndicator(current = step, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
            HorizontalDivider()
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                when (step) {
                    Step.PREP -> PrepStep(onNext = { step = Step.AIM })
                    Step.AIM -> AimStep(
                        transport = t,
                        frame = frame,
                        liveViewError = liveViewError,
                        roiCenter = roiCenter,
                        displayedSize = displayedSize,
                        onDisplayedSizeChange = { displayedSize = it },
                        onRoiPicked = { x, y, bmp ->
                            roiCenter = x to y
                            sharpness = computePeakLuminance(bmp, x, y, ROI_PX)
                        },
                        onBack = { step = Step.PREP },
                        onNext = { step = Step.FOCUS },
                    )
                    Step.FOCUS -> FocusStep(
                        transport = t,
                        frame = frame,
                        liveViewError = liveViewError,
                        roiCenter = roiCenter,
                        sharpness = sharpness,
                        displayedSize = displayedSize,
                        onDisplayedSizeChange = { displayedSize = it },
                        onBack = { step = Step.AIM },
                        onDone = { step = Step.LOCK },
                    )
                    Step.LOCK -> LockStep(
                        onBack = { step = Step.FOCUS },
                        onDone = onBack,
                    )
                }
            }
        }
    }
}

// ── Step UIs ─────────────────────────────────────────────────────────────

@Composable
private fun StepIndicator(current: Step, modifier: Modifier = Modifier) {
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        Step.entries.forEachIndexed { idx, s ->
            val active = s == current
            val done = s.ordinal < current.ordinal
            val color = when {
                active -> MaterialTheme.colorScheme.primary
                done -> MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
                else -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)
            }
            Surface(
                color = color.copy(alpha = if (active) 0.2f else 0.1f),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.weight(1f),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        s.icon,
                        contentDescription = null,
                        tint = color,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        stringResource(s.labelRes),
                        style = MaterialTheme.typography.labelSmall,
                        color = color,
                        fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
                    )
                }
            }
            if (idx < Step.entries.size - 1) Spacer(Modifier.width(4.dp))
        }
    }
}

@Composable
private fun PrepStep(onNext: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            stringResource(R.string.star_focus_prep_title),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
        )
        Text(
            stringResource(R.string.star_focus_prep_intro),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        InstructionRow("1.", stringResource(R.string.star_focus_prep_step_af))
        InstructionRow("2.", stringResource(R.string.star_focus_prep_step_mode))
        InstructionRow("3.", stringResource(R.string.star_focus_prep_step_aim))
        Spacer(Modifier.weight(1f))
        Button(
            onClick = onNext,
            modifier = Modifier.fillMaxWidth().height(56.dp),
            shape = RoundedCornerShape(12.dp),
        ) { Text(stringResource(R.string.star_focus_btn_next)) }
    }
}

@Composable
private fun AimStep(
    transport: CameraTransport,
    frame: Bitmap?,
    liveViewError: String?,
    roiCenter: Pair<Int, Int>?,
    displayedSize: IntSize?,
    onDisplayedSizeChange: (IntSize) -> Unit,
    onRoiPicked: (Int, Int, Bitmap) -> Unit,
    onBack: () -> Unit,
    onNext: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            stringResource(R.string.star_focus_aim_hint),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        LiveViewBox(
            frame = frame,
            liveViewError = liveViewError,
            roiCenter = roiCenter,
            onDisplayedSizeChange = onDisplayedSizeChange,
            onTap = { bmp, x, y -> onRoiPicked(x, y, bmp) },
            displayedSize = displayedSize,
            modifier = Modifier.weight(1f),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onBack, modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.star_focus_btn_back))
            }
            Button(
                onClick = onNext,
                modifier = Modifier.weight(2f),
                enabled = roiCenter != null,
            ) { Text(stringResource(R.string.star_focus_btn_next)) }
        }
    }
}

@Composable
private fun FocusStep(
    transport: CameraTransport,
    frame: Bitmap?,
    liveViewError: String?,
    roiCenter: Pair<Int, Int>?,
    sharpness: Int,
    displayedSize: IntSize?,
    onDisplayedSizeChange: (IntSize) -> Unit,
    onBack: () -> Unit,
    onDone: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        LiveViewBox(
            frame = frame,
            liveViewError = liveViewError,
            roiCenter = roiCenter,
            onDisplayedSizeChange = onDisplayedSizeChange,
            onTap = null,  // ROI locked during focus
            displayedSize = displayedSize,
            modifier = Modifier.weight(1f),
        )
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
                    tint = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        stringResource(R.string.star_focus_sharpness_label),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        "$sharpness / 255",
                        style = androidx.compose.material3.LocalTextStyle.current.copy(fontFamily = com.ehrocha.pulsar.ui.theme.Mono, fontFeatureSettings = "tnum"),
                        fontWeight = FontWeight.Bold,
                        fontSize = 22.sp,
                    )
                }
            }
        }
        // Drive-focus row
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
                    FocusBtn("«««", "near3", transport, Modifier.weight(1f))
                    FocusBtn("««", "near2", transport, Modifier.weight(1f))
                    FocusBtn("«", "near1", transport, Modifier.weight(1f))
                    FocusBtn("»", "far1", transport, Modifier.weight(1f))
                    FocusBtn("»»", "far2", transport, Modifier.weight(1f))
                    FocusBtn("»»»", "far3", transport, Modifier.weight(1f))
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onBack, modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.star_focus_btn_back))
            }
            Button(onClick = onDone, modifier = Modifier.weight(2f)) {
                Text(stringResource(R.string.star_focus_btn_focus_done))
            }
        }
    }
}

@Composable
private fun LockStep(onBack: () -> Unit, onDone: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            stringResource(R.string.star_focus_lock_title),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
        )
        Text(
            stringResource(R.string.star_focus_lock_intro),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        InstructionRow("•", stringResource(R.string.star_focus_lock_step_switch))
        InstructionRow("•", stringResource(R.string.star_focus_lock_step_no_switch))
        InstructionRow("•", stringResource(R.string.star_focus_lock_step_avoid_ring))
        Spacer(Modifier.weight(1f))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onBack, modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.star_focus_btn_back))
            }
            Button(
                onClick = onDone,
                modifier = Modifier.weight(2f).height(48.dp),
            ) { Text(stringResource(R.string.star_focus_btn_done)) }
        }
    }
}

// ── Shared building blocks ───────────────────────────────────────────────

@Composable
private fun InstructionRow(bullet: String, text: String) {
    Row(verticalAlignment = Alignment.Top) {
        Text(
            bullet,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(end = 12.dp).width(20.dp),
        )
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun LiveViewBox(
    frame: Bitmap?,
    liveViewError: String?,
    roiCenter: Pair<Int, Int>?,
    onDisplayedSizeChange: (IntSize) -> Unit,
    onTap: ((Bitmap, Int, Int) -> Unit)?,
    displayedSize: IntSize?,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Color.Black)
            .onSizeChanged { onDisplayedSizeChange(it) },
        contentAlignment = Alignment.Center,
    ) {
        val bmp = frame
        when {
            liveViewError != null -> Column(
                modifier = Modifier.padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    stringResource(R.string.star_focus_start_failed),
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center,
                )
                if (liveViewError != "start_failed") {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        liveViewError,
                        color = MaterialTheme.colorScheme.error.copy(alpha = 0.7f),
                        style = MaterialTheme.typography.bodySmall,
                        textAlign = TextAlign.Center,
                    )
                }
            }
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
                    modifier = if (onTap != null) {
                        Modifier
                            .fillMaxSize()
                            .pointerInput(bmp.width, bmp.height) {
                                detectTapGestures { tap ->
                                    val (bx, by) = viewToBitmap(
                                        tap.x, tap.y,
                                        size.width.toFloat(), size.height.toFloat(),
                                        bmp.width, bmp.height,
                                    )
                                    if (bx in 0 until bmp.width && by in 0 until bmp.height) {
                                        onTap(bmp, bx, by)
                                    }
                                }
                            }
                    } else {
                        Modifier.fillMaxSize()
                    },
                )
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
            // Anchor top-left so the offset is absolute. Without this the
            // parent Box's contentAlignment=Center placed the circle at the
            // centre first, then the absolute offset pushed it bottom-right.
            .align(Alignment.TopStart)
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
    transport: CameraTransport,
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
 *  [bmp]. Returns 0..255; higher = sharper star (more concentrated light). */
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
        val luma = (r * 77 + g * 150 + b * 29) shr 8
        if (luma > peak) peak = luma
    }
    return peak
}
