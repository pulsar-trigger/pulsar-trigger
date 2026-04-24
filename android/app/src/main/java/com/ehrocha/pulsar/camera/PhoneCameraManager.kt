/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Copyright (C) 2026 Pulsar Trigger contributors
 */

package com.ehrocha.pulsar.camera

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CameraManager as Camera2Manager
import android.hardware.camera2.CameraMetadata
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import android.util.Range
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import java.text.SimpleDateFormat
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Per-lens capabilities queried from Camera2. */
data class LensCapabilities(
    val supportsManualExposure: Boolean = false,
    val isoRange: Range<Int>? = null,
    val exposureTimeRange: Range<Long>? = null,  // nanoseconds
    val supportsManualFocus: Boolean = false,
    val minFocusDistance: Float = 0f,  // diopters (0 = infinity)
    val cameraId: String = "",
)

data class PhoneLens(
    val id: Int,
    val label: String,
    val selector: CameraSelector,
    val focalLength: Float = 0f,
    val aperture: Float = 0f,
    val sensorWidth: Float = 0f,
    val sensorHeight: Float = 0f,
    val megapixels: Float = 0f,
    val facing: Int = CameraCharacteristics.LENS_FACING_BACK,
    val capabilities: LensCapabilities = LensCapabilities(),
)

class PhoneCameraManager(private val context: Context) {

    companion object {
        private const val TAG = "PhoneCamera"
    }

    private var cameraProvider: ProcessCameraProvider? = null
    private var imageCapture: ImageCapture? = null
    private var preview: Preview? = null
    private val camera2Manager = context.getSystemService(Context.CAMERA_SERVICE) as Camera2Manager

    /** True if the device has at least one usable camera. */
    val isAvailable: Boolean
        get() = try {
            camera2Manager.cameraIdList.isNotEmpty()
        } catch (_: Exception) {
            false
        }

    private val _lenses = MutableStateFlow<List<PhoneLens>>(emptyList())
    val lenses: StateFlow<List<PhoneLens>> = _lenses

    private val _selectedLens = MutableStateFlow(0)
    val selectedLens: StateFlow<Int> = _selectedLens

    private val _isCapturing = MutableStateFlow(false)
    val isCapturing: StateFlow<Boolean> = _isCapturing

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError

    private val _photoCount = MutableStateFlow(0)
    val photoCount: StateFlow<Int> = _photoCount

    // ── Manual exposure controls ─────────────────────────────────────────
    private val _manualIso = MutableStateFlow<Int?>(null)  // null = auto
    val manualIso: StateFlow<Int?> = _manualIso

    private val _manualExposureNs = MutableStateFlow<Long?>(null)  // null = auto
    val manualExposureNs: StateFlow<Long?> = _manualExposureNs

    private val _manualFocusDist = MutableStateFlow<Float?>(null)  // null = auto, 0 = infinity
    val manualFocusDist: StateFlow<Float?> = _manualFocusDist

    // ── Star focus state ─────────────────────────────────────────────────
    private val _starFocusRunning = MutableStateFlow(false)
    val starFocusRunning: StateFlow<Boolean> = _starFocusRunning

    private val _starFocusProgress = MutableStateFlow(0f)
    val starFocusProgress: StateFlow<Float> = _starFocusProgress

    private var boundCamera: Camera? = null

    fun setManualIso(iso: Int?) {
        _manualIso.value = iso
        applyManualSettings()
    }

    fun setManualExposureNs(ns: Long?) {
        _manualExposureNs.value = ns
        applyManualSettings()
    }

    fun setManualFocusDist(dist: Float?) {
        _manualFocusDist.value = dist
        applyManualSettings()
    }

    @androidx.annotation.OptIn(androidx.camera.camera2.interop.ExperimentalCamera2Interop::class)
    private fun applyManualSettings() {
        val camera = boundCamera ?: return
        val ctrl = camera.cameraControl

        val builder = androidx.camera.camera2.interop.CaptureRequestOptions.Builder()

        val iso = _manualIso.value
        val expNs = _manualExposureNs.value
        if (iso != null && expNs != null) {
            builder.setCaptureRequestOption(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
            builder.setCaptureRequestOption(CaptureRequest.SENSOR_SENSITIVITY, iso)
            builder.setCaptureRequestOption(CaptureRequest.SENSOR_EXPOSURE_TIME, expNs)
        } else {
            builder.setCaptureRequestOption(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
        }

        val focusDist = _manualFocusDist.value
        if (focusDist != null) {
            builder.setCaptureRequestOption(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
            builder.setCaptureRequestOption(CaptureRequest.LENS_FOCUS_DISTANCE, focusDist)
        } else {
            builder.setCaptureRequestOption(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
        }

        val cam2Ctrl = androidx.camera.camera2.interop.Camera2CameraControl.from(ctrl)
        cam2Ctrl.setCaptureRequestOptions(builder.build())
    }

    // Saved state for restoring after capture sequence
    private var savedIso: Int? = null
    private var savedExpNs: Long? = null
    private var exposureOverridden = false

    /**
     * Configure the sensor exposure time for a capture sequence.
     * Returns the actual exposure time (ns) that was set, or 0 if manual exposure is not supported
     * (meaning the caller should use a delay to simulate the exposure time).
     */
    fun setSensorExposureForCapture(requestedMs: Long): Long {
        val lens = _lenses.value.getOrNull(_selectedLens.value) ?: return 0L
        val caps = lens.capabilities
        if (!caps.supportsManualExposure || caps.exposureTimeRange == null || caps.isoRange == null) {
            return 0L
        }

        // Save current state so we can restore after the sequence
        savedIso = _manualIso.value
        savedExpNs = _manualExposureNs.value
        exposureOverridden = true

        val requestedNs = requestedMs * 1_000_000L
        val clampedNs = requestedNs.coerceIn(caps.exposureTimeRange!!.lower, caps.exposureTimeRange!!.upper)

        // If user hasn't set manual ISO, use a sensible default for long exposures
        if (_manualIso.value == null) {
            val defaultIso = caps.isoRange!!.lower.coerceAtLeast(400)
                .coerceAtMost(caps.isoRange!!.upper)
            _manualIso.value = defaultIso
        }
        _manualExposureNs.value = clampedNs
        applyManualSettings()

        return clampedNs
    }

    /** Restore exposure settings to what the user had before the capture sequence. */
    fun restoreExposureSettings() {
        if (!exposureOverridden) return
        _manualIso.value = savedIso
        _manualExposureNs.value = savedExpNs
        exposureOverridden = false
        applyManualSettings()
    }

    /**
     * Star auto-focus: sweep focus from infinity to near, measuring sharpness at each step.
     * Locks focus at the distance with maximum sharpness.
     */
    suspend fun starAutoFocus() {
        val lens = _lenses.value.getOrNull(_selectedLens.value) ?: return
        if (!lens.capabilities.supportsManualFocus) return
        val maxDist = lens.capabilities.minFocusDistance
        if (maxDist <= 0f) return

        _starFocusRunning.value = true
        _starFocusProgress.value = 0f

        val steps = 20
        var bestSharpness = -1.0
        var bestDist = 0f  // start at infinity

        try {
            for (i in 0..steps) {
                val dist = (maxDist * i.toFloat() / steps)
                _starFocusProgress.value = i.toFloat() / steps

                // Set focus distance
                setManualFocusDist(dist)
                // Let the camera settle
                delay(300)

                // Measure sharpness from preview
                val sharpness = measureSharpness()
                Log.d(TAG, "Star focus: dist=%.3f sharpness=%.1f".format(dist, sharpness))

                if (sharpness > bestSharpness) {
                    bestSharpness = sharpness
                    bestDist = dist
                }
            }

            // Lock at best focus distance
            Log.i(TAG, "Star focus: best distance=%.3f sharpness=%.1f".format(bestDist, bestSharpness))
            setManualFocusDist(bestDist)
            _starFocusProgress.value = 1f
        } finally {
            _starFocusRunning.value = false
        }
    }

    /**
     * Measure image sharpness using Laplacian variance on the current preview frame.
     * Higher values = sharper image (stars in focus are sharp point sources).
     */
    private suspend fun measureSharpness(): Double = suspendCancellableCoroutine { cont ->
        val capture = imageCapture
        if (capture == null) {
            cont.resume(0.0)
            return@suspendCancellableCoroutine
        }

        // Use ImageAnalysis to grab a frame for sharpness measurement
        // Since we may not have ImageAnalysis bound, we'll use a lightweight approach:
        // take a quick capture to memory and analyze it
        capture.takePicture(
            ContextCompat.getMainExecutor(context),
            object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(image: ImageProxy) {
                    val sharpness = computeLaplacianVariance(image)
                    image.close()
                    cont.resume(sharpness)
                }

                override fun onError(exception: ImageCaptureException) {
                    Log.w(TAG, "Sharpness capture failed", exception)
                    cont.resume(0.0)
                }
            },
        )
    }

    /** Compute Laplacian variance as a sharpness metric on an ImageProxy. */
    private fun computeLaplacianVariance(image: ImageProxy): Double {
        val plane = image.planes[0]
        val buffer = plane.buffer
        val width = image.width
        val height = image.height
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride

        // Sample a center crop for speed (quarter resolution)
        val cropSize = minOf(width, height) / 2
        val startX = (width - cropSize) / 2
        val startY = (height - cropSize) / 2

        var sum = 0.0
        var sumSq = 0.0
        var count = 0

        // Laplacian kernel: center pixel * 4 - four neighbors
        for (y in (startY + 1) until (startY + cropSize - 1) step 2) {
            for (x in (startX + 1) until (startX + cropSize - 1) step 2) {
                val center = getPixelLuminance(buffer, x, y, rowStride, pixelStride)
                val top = getPixelLuminance(buffer, x, y - 1, rowStride, pixelStride)
                val bottom = getPixelLuminance(buffer, x, y + 1, rowStride, pixelStride)
                val left = getPixelLuminance(buffer, x - 1, y, rowStride, pixelStride)
                val right = getPixelLuminance(buffer, x + 1, y, rowStride, pixelStride)
                val laplacian = (4 * center - top - bottom - left - right).toDouble()
                sum += laplacian
                sumSq += laplacian * laplacian
                count++
            }
        }

        if (count == 0) return 0.0
        val mean = sum / count
        return sumSq / count - mean * mean  // variance
    }

    private fun getPixelLuminance(
        buffer: java.nio.ByteBuffer,
        x: Int,
        y: Int,
        rowStride: Int,
        pixelStride: Int,
    ): Int {
        val offset = y * rowStride + x * pixelStride
        return if (offset < buffer.capacity()) buffer.get(offset).toInt() and 0xFF else 0
    }

    /** Enumerate physical cameras with Camera2 metadata. */
    private fun enumerateLenses(provider: ProcessCameraProvider): List<PhoneLens> {
        val available = mutableListOf<PhoneLens>()
        var idx = 0
        for (cameraId in camera2Manager.cameraIdList) {
            try {
                val chars = camera2Manager.getCameraCharacteristics(cameraId)
                val facing = chars.get(CameraCharacteristics.LENS_FACING) ?: continue
                // Only keep back-facing cameras
                if (facing != CameraMetadata.LENS_FACING_BACK) continue

                val selector = CameraSelector.Builder()
                    .addCameraFilter { cameraInfos ->
                        cameraInfos.filter {
                            val id = androidx.camera.camera2.interop.Camera2CameraInfo
                                .from(it).cameraId
                            id == cameraId
                        }
                    }
                    .build()

                // Check if CameraX can actually use this camera
                if (!provider.hasCamera(selector)) continue

                val focalLengths = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
                val apertures = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES)
                val sensorSize = chars.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
                val pixelArray = chars.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE)

                val focalLength = focalLengths?.firstOrNull() ?: 0f
                val aperture = apertures?.firstOrNull() ?: 0f
                val sensorW = sensorSize?.width ?: 0f
                val sensorH = sensorSize?.height ?: 0f
                val mp = if (pixelArray != null) {
                    (pixelArray.width.toLong() * pixelArray.height / 1_000_000f)
                } else 0f

                // Query manual control capabilities
                val hwLevel = chars.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL)
                    ?: CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY
                val supportsManual = hwLevel == CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_FULL
                    || hwLevel == CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_3

                val isoRange = if (supportsManual) {
                    chars.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
                } else null
                val exposureTimeRange = if (supportsManual) {
                    chars.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
                } else null

                val minFocusDist = chars.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f
                val supportsManualFocus = minFocusDist > 0f

                val capabilities = LensCapabilities(
                    supportsManualExposure = supportsManual,
                    isoRange = isoRange,
                    exposureTimeRange = exposureTimeRange,
                    supportsManualFocus = supportsManualFocus,
                    minFocusDistance = minFocusDist,
                    cameraId = cameraId,
                )

                // Build a descriptive label
                val facingStr = if (facing == CameraMetadata.LENS_FACING_FRONT) "Front" else "Back"
                val eqFl = if (focalLength > 0 && sensorW > 0) {
                    (focalLength * 36f / sensorW).roundToInt()
                } else 0
                val label = if (eqFl > 0) "$facingStr ${eqFl}mm" else facingStr

                available.add(
                    PhoneLens(
                        id = idx++,
                        label = label,
                        selector = selector,
                        focalLength = focalLength,
                        aperture = aperture,
                        sensorWidth = sensorW,
                        sensorHeight = sensorH,
                        megapixels = mp,
                        facing = facing,
                        capabilities = capabilities,
                    )
                )
            } catch (e: Exception) {
                Log.w(TAG, "Failed to query camera $cameraId", e)
            }
        }
        // Sort: back cameras first (by focal length ascending), then front cameras
        return available.sortedWith(compareBy<PhoneLens> { it.facing }.thenBy { it.focalLength })
    }

    /** Initialize without preview — for headless capture in trigger modes. */
    fun initializeHeadless(
        lifecycleOwner: LifecycleOwner,
        onReady: () -> Unit = {},
    ) {
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            val provider = future.get()
            cameraProvider = provider
            _lenses.value = enumerateLenses(provider)

            val lensList = _lenses.value
            if (lensList.isNotEmpty()) {
                provider.unbindAll()
                imageCapture = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                    .build()
                try {
                    boundCamera = provider.bindToLifecycle(lifecycleOwner, lensList[0].selector, imageCapture!!)
                    _lastError.value = null
                    Log.i(TAG, "Camera bound headless: ${lensList[0].label}")
                    applyManualSettings()
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to bind camera headless", e)
                    _lastError.value = e.message
                }
            }
            onReady()
        }, ContextCompat.getMainExecutor(context))
    }

    fun initialize(
        lifecycleOwner: LifecycleOwner,
        previewView: PreviewView,
        onReady: () -> Unit = {},
    ) {
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            val provider = future.get()
            cameraProvider = provider
            _lenses.value = enumerateLenses(provider)

            val lensList = _lenses.value
            if (lensList.isNotEmpty()) {
                bindCamera(provider, lifecycleOwner, previewView, lensList[0].selector)
            }
            onReady()
        }, ContextCompat.getMainExecutor(context))
    }

    fun selectLens(index: Int, lifecycleOwner: LifecycleOwner, previewView: PreviewView) {
        val lensList = _lenses.value
        if (index < 0 || index >= lensList.size) return
        _selectedLens.value = index
        val provider = cameraProvider ?: return
        bindCamera(provider, lifecycleOwner, previewView, lensList[index].selector)
    }

    /** Select lens with preview — used by the integrated camera screen. */
    fun selectLensWithPreview(index: Int, lifecycleOwner: LifecycleOwner, previewView: PreviewView) {
        selectLens(index, lifecycleOwner, previewView)
    }

    /** Select lens without preview — for headless mode. */
    fun selectLensHeadless(index: Int, lifecycleOwner: LifecycleOwner) {
        val lensList = _lenses.value
        if (index < 0 || index >= lensList.size) return
        _selectedLens.value = index
        val provider = cameraProvider ?: return
        provider.unbindAll()
        imageCapture = ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
            .build()
        try {
            boundCamera = provider.bindToLifecycle(lifecycleOwner, lensList[index].selector, imageCapture!!)
            _lastError.value = null
            applyManualSettings()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to bind camera headless", e)
            _lastError.value = e.message
        }
    }

    private fun bindCamera(
        provider: ProcessCameraProvider,
        lifecycleOwner: LifecycleOwner,
        previewView: PreviewView,
        selector: CameraSelector,
    ) {
        provider.unbindAll()

        preview = Preview.Builder().build().also {
            it.surfaceProvider = previewView.surfaceProvider
        }

        imageCapture = ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
            .build()

        try {
            boundCamera = provider.bindToLifecycle(lifecycleOwner, selector, preview, imageCapture)
            _lastError.value = null
            Log.i(TAG, "Camera bound: ${selector}")
            applyManualSettings()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to bind camera", e)
            _lastError.value = e.message
        }
    }

    /** Fire a single capture and suspend until the photo is saved (or fails). */
    suspend fun captureAndWait(): Boolean = suspendCancellableCoroutine { cont ->
        val capture = imageCapture
        if (capture == null) {
            _lastError.value = "Camera not ready"
            cont.resume(false)
            return@suspendCancellableCoroutine
        }
        _isCapturing.value = true
        _lastError.value = null

        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US)
            .format(System.currentTimeMillis())

        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "PULSAR_$timestamp")
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.MediaColumns.RELATIVE_PATH, "DCIM/Pulsar")
            }
        }

        val outputOptions = ImageCapture.OutputFileOptions.Builder(
            context.contentResolver,
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            contentValues,
        ).build()

        capture.takePicture(
            outputOptions,
            ContextCompat.getMainExecutor(context),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    _isCapturing.value = false
                    _photoCount.value += 1
                    Log.i(TAG, "Photo saved: ${output.savedUri}")
                    cont.resume(true)
                }

                override fun onError(exception: ImageCaptureException) {
                    _isCapturing.value = false
                    _lastError.value = exception.message
                    Log.e(TAG, "Capture failed", exception)
                    cont.resume(false)
                }
            },
        )
    }

    fun capture() {
        val capture = imageCapture ?: run {
            _lastError.value = "Camera not ready"
            return
        }
        _isCapturing.value = true
        _lastError.value = null

        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US)
            .format(System.currentTimeMillis())

        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "PULSAR_$timestamp")
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.MediaColumns.RELATIVE_PATH, "DCIM/Pulsar")
            }
        }

        val outputOptions = ImageCapture.OutputFileOptions.Builder(
            context.contentResolver,
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            contentValues,
        ).build()

        capture.takePicture(
            outputOptions,
            ContextCompat.getMainExecutor(context),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    _isCapturing.value = false
                    _photoCount.value += 1
                    Log.i(TAG, "Photo saved: ${output.savedUri}")
                }

                override fun onError(exception: ImageCaptureException) {
                    _isCapturing.value = false
                    _lastError.value = exception.message
                    Log.e(TAG, "Capture failed", exception)
                }
            },
        )
    }

    fun release() {
        cameraProvider?.unbindAll()
        cameraProvider = null
        imageCapture = null
        preview = null
        boundCamera = null
    }
}
