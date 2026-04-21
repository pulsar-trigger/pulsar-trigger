/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Copyright (C) 2026 Pulsar Trigger contributors
 */

package com.ehrocha.pulsar.camera

import android.content.ContentValues
import android.content.Context
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.text.SimpleDateFormat
import java.util.Locale

data class PhoneLens(
    val id: Int,
    val label: String,
    val selector: CameraSelector,
)

class PhoneCameraManager(private val context: Context) {

    companion object {
        private const val TAG = "PhoneCamera"
    }

    private var cameraProvider: ProcessCameraProvider? = null
    private var imageCapture: ImageCapture? = null
    private var preview: Preview? = null

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

    fun initialize(
        lifecycleOwner: LifecycleOwner,
        previewView: PreviewView,
        onReady: () -> Unit = {},
    ) {
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            val provider = future.get()
            cameraProvider = provider

            // Enumerate available lenses
            val available = mutableListOf<PhoneLens>()
            var idx = 0
            if (provider.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA)) {
                available.add(PhoneLens(idx++, "Back", CameraSelector.DEFAULT_BACK_CAMERA))
            }
            if (provider.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA)) {
                available.add(PhoneLens(idx++, "Front", CameraSelector.DEFAULT_FRONT_CAMERA))
            }
            _lenses.value = available

            if (available.isNotEmpty()) {
                bindCamera(provider, lifecycleOwner, previewView, available[0].selector)
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
