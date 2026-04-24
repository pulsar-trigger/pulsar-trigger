/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Copyright (C) 2026 Pulsar Trigger contributors
 */

package com.ehrocha.pulsar.camera

import android.content.ContentValues
import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager as Camera2Manager
import android.hardware.camera2.CameraMetadata
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
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

    /** Enumerate physical cameras with Camera2 metadata. */
    private fun enumerateLenses(provider: ProcessCameraProvider): List<PhoneLens> {
        val available = mutableListOf<PhoneLens>()
        var idx = 0
        for (cameraId in camera2Manager.cameraIdList) {
            try {
                val chars = camera2Manager.getCameraCharacteristics(cameraId)
                val facing = chars.get(CameraCharacteristics.LENS_FACING) ?: continue
                // Skip external cameras
                if (facing == CameraMetadata.LENS_FACING_EXTERNAL) continue

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
                    provider.bindToLifecycle(lifecycleOwner, lensList[0].selector, imageCapture!!)
                    _lastError.value = null
                    Log.i(TAG, "Camera bound headless: ${lensList[0].label}")
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
            provider.bindToLifecycle(lifecycleOwner, lensList[index].selector, imageCapture!!)
            _lastError.value = null
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
            provider.bindToLifecycle(lifecycleOwner, selector, preview, imageCapture)
            _lastError.value = null
            Log.i(TAG, "Camera bound: ${selector}")
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
    }
}
