/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Copyright (C) 2026 Pulsar Trigger contributors
 */

package com.ehrocha.pulsar.camera

import android.content.ContentValues
import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CameraManager as Camera2Manager
import android.hardware.camera2.CameraMetadata
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import android.util.Range
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
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

/** Per-lens capabilities queried from Camera2. */
data class LensCapabilities(
    val supportsManualExposure: Boolean = false,
    val isoRange: Range<Int>? = null,
    val exposureTimeRange: Range<Long>? = null,  // nanoseconds
    val supportsManualFocus: Boolean = false,
    val minFocusDistance: Float = 0f,  // diopters (0 = infinity)
    val cameraId: String = "",
    val maxDigitalZoom: Float = 1f,  // from SCALER_AVAILABLE_MAX_DIGITAL_ZOOM
    val supportsRaw: Boolean = false,
    val supportsOis: Boolean = false,
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

    private val _cameraDebugLog = MutableStateFlow<List<String>>(emptyList())
    val cameraDebugLog: StateFlow<List<String>> = _cameraDebugLog

    private val _selectedLens = MutableStateFlow(0)
    val selectedLens: StateFlow<Int> = _selectedLens

    private val _isCapturing = MutableStateFlow(false)
    val isCapturing: StateFlow<Boolean> = _isCapturing

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError

    private val _photoCount = MutableStateFlow(0)
    val photoCount: StateFlow<Int> = _photoCount

    /** URI of the most recently saved photo — used by the gallery shortcut. */
    private val _lastSavedUri = MutableStateFlow<android.net.Uri?>(null)
    val lastSavedUri: StateFlow<android.net.Uri?> = _lastSavedUri

    /** When non-null, captures land in DCIM/Pulsar/<this folder> instead of DCIM/Pulsar. */
    private val _sequenceFolder = MutableStateFlow<String?>(null)
    val sequenceFolder: StateFlow<String?> = _sequenceFolder

    /** Begin a capture sequence — all subsequent saves go into a fresh subfolder. */
    fun beginSequenceFolder() {
        val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
            .format(System.currentTimeMillis())
        _sequenceFolder.value = "Sequence_$ts"
    }

    /** Full DCIM/... path for the active sequence folder, or null if none active. */
    fun activeSequencePath(): String? =
        _sequenceFolder.value?.let { "DCIM/Pulsar/$it" }

    /** End the active sequence — subsequent saves go back into DCIM/Pulsar. */
    fun endSequenceFolder() {
        _sequenceFolder.value = null
    }

    /** Resolves the MediaStore relative path for the next save. */
    private fun currentRelativePath(): String {
        val folder = _sequenceFolder.value
        return if (folder != null) "DCIM/Pulsar/$folder" else "DCIM/Pulsar"
    }

    // ── Manual exposure controls ─────────────────────────────────────────
    private val _manualIso = MutableStateFlow<Int?>(null)  // null = auto
    val manualIso: StateFlow<Int?> = _manualIso

    private val _manualExposureNs = MutableStateFlow<Long?>(null)  // null = auto
    val manualExposureNs: StateFlow<Long?> = _manualExposureNs

    private val _manualFocusDist = MutableStateFlow<Float?>(null)  // null = auto, 0 = infinity
    val manualFocusDist: StateFlow<Float?> = _manualFocusDist

    private var boundCamera: Camera? = null

    /** Guard against concurrent bind operations (e.g. rapid lens switch during init). */
    @Volatile
    private var binding = false

    // ── Zoom ────────────────────────────────────────────────────────────
    private val _zoomRatio = MutableStateFlow(1f)
    val zoomRatio: StateFlow<Float> = _zoomRatio

    fun setZoomRatio(ratio: Float) {
        val camera = boundCamera ?: return
        val zoomState = camera.cameraInfo.zoomState.value ?: return
        val clamped = ratio.coerceIn(zoomState.minZoomRatio, zoomState.maxZoomRatio)
        camera.cameraControl.setZoomRatio(clamped)
        _zoomRatio.value = clamped
    }

    fun getMaxZoomRatio(): Float {
        return boundCamera?.cameraInfo?.zoomState?.value?.maxZoomRatio ?: 1f
    }

    fun getMinZoomRatio(): Float {
        return boundCamera?.cameraInfo?.zoomState?.value?.minZoomRatio ?: 1f
    }

    // ── RAW capture ─────────────────────────────────────────────────────
    private val _saveAsRaw = MutableStateFlow(false)
    val saveAsRaw: StateFlow<Boolean> = _saveAsRaw

    fun setSaveAsRaw(enabled: Boolean) {
        _saveAsRaw.value = enabled
    }

    // ── OIS (Optical Image Stabilization) ───────────────────────────────
    private val _oisEnabled = MutableStateFlow(true)  // default on
    val oisEnabled: StateFlow<Boolean> = _oisEnabled

    fun setOisEnabled(enabled: Boolean) {
        _oisEnabled.value = enabled
        applyManualSettings()
    }

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
        // Manual ISO + exposure flow straight to the preview pipeline — what you
        // see is what you get. (ExpSim toggle removed; users wanted WYSIWYG.)
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

        // OIS
        builder.setCaptureRequestOption(
            CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE,
            if (_oisEnabled.value)
                CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON
            else
                CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_OFF,
        )

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
        // Don't clamp — many Camera2 implementations honor exposures beyond the
        // advertised SENSOR_INFO_EXPOSURE_TIME_RANGE (the reported range is the
        // "guaranteed" range, not necessarily the hard upper bound). Pre-v0.157
        // builds passed the raw value through and long exposures worked on real
        // hardware. The driver will silently clamp internally if it has to.
        val sensorMinNs = caps.exposureTimeRange!!.lower
        val expNs = requestedNs.coerceAtLeast(sensorMinNs)

        if (_manualIso.value == null) {
            val defaultIso = caps.isoRange!!.lower.coerceAtLeast(400)
                .coerceAtMost(caps.isoRange!!.upper)
            _manualIso.value = defaultIso
        }
        _manualExposureNs.value = expNs
        applyManualSettings()

        return expNs
    }

    /** Restore exposure settings to what the user had before the capture sequence. */
    fun restoreExposureSettings() {
        if (!exposureOverridden) return
        // Restore all at once before applying to avoid partial state
        val iso = savedIso
        val expNs = savedExpNs
        exposureOverridden = false
        savedIso = null
        savedExpNs = null
        _manualIso.value = iso
        _manualExposureNs.value = expNs
        applyManualSettings()
    }

    /** Enumerate physical cameras with Camera2 metadata. */
    private fun enumerateLenses(provider: ProcessCameraProvider): List<PhoneLens> {
        val available = mutableListOf<PhoneLens>()
        val debugLog = mutableListOf<String>()
        var idx = 0

        // Collect physical camera IDs that belong to logical cameras so we can
        // expose them as selectable lenses via the logical camera's CameraSelector.
        data class PhysicalCamInfo(
            val logicalCameraId: String,
            val physicalCameraId: String,
            val chars: CameraCharacteristics,
        )
        val physicalCams = mutableListOf<PhysicalCamInfo>()

        for (cameraId in camera2Manager.cameraIdList) {
            try {
                val chars = camera2Manager.getCameraCharacteristics(cameraId)
                val facing = chars.get(CameraCharacteristics.LENS_FACING) ?: continue
                // Only keep back-facing cameras
                if (facing != CameraMetadata.LENS_FACING_BACK) continue

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

                val hwLevel = chars.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL)
                    ?: CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY

                // Check for MANUAL_SENSOR capability (works on LIMITED devices too)
                val caps2 = chars.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: intArrayOf()
                val hasManualSensor = CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR in caps2
                    || hwLevel == CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_FULL
                    || hwLevel == CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_3

                // Try reading ranges regardless — some devices expose them even without the capability flag
                val isoRange = chars.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
                val exposureTimeRange = chars.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
                val supportsManualExposure = hasManualSensor || (isoRange != null && exposureTimeRange != null)

                val minFocusDist = chars.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f
                val supportsManualFocus = minFocusDist > 0f
                val maxDigZoom = chars.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM) ?: 1f
                val hasRaw = CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_RAW in caps2
                val oisModes = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION)
                val hasOis = oisModes != null && CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON in oisModes

                val capabilities = LensCapabilities(
                    supportsManualExposure = supportsManualExposure,
                    isoRange = isoRange,
                    exposureTimeRange = exposureTimeRange,
                    supportsManualFocus = supportsManualFocus,
                    minFocusDistance = minFocusDist,
                    cameraId = cameraId,
                    maxDigitalZoom = maxDigZoom,
                    supportsRaw = hasRaw,
                    supportsOis = hasOis,
                )

                val selector = CameraSelector.Builder()
                    .addCameraFilter { cameraInfos ->
                        cameraInfos.filter {
                            Camera2CameraInfo.from(it).cameraId == cameraId
                        }
                    }
                    .build()

                // Check if CameraX can use this camera directly
                val cameraXAvailable = provider.hasCamera(selector)

                val eqFl = if (focalLength > 0 && sensorW > 0) {
                    (focalLength * 36f / sensorW).roundToInt()
                } else 0
                val label = if (eqFl > 0) "Back ${eqFl}mm" else "Back"

                val hwLevelName = when (hwLevel) {
                    CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY -> "LEGACY"
                    CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED -> "LIMITED"
                    CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_FULL -> "FULL"
                    CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_3 -> "LEVEL_3"
                    CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_EXTERNAL -> "EXTERNAL"
                    else -> "UNKNOWN($hwLevel)"
                }
                val info = "Camera $cameraId: ${eqFl}mm f/$aperture ${mp.roundToInt()}MP" +
                    " hw=$hwLevelName manual=$supportsManualExposure" +
                    " iso=${isoRange ?: "none"} focus=$supportsManualFocus" +
                    " cameraX=$cameraXAvailable"
                Log.d(TAG, info)
                debugLog.add(if (cameraXAvailable) "\u2705 $info" else "\u274C $info")

                if (cameraXAvailable) {
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
                }

                // Discover physical sub-cameras of logical multi-camera devices
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    val physicalIds = chars.physicalCameraIds
                    if (physicalIds.size > 1) {
                        val logicalInfo = "Logical $cameraId has physical IDs: $physicalIds"
                        Log.d(TAG, logicalInfo)
                        debugLog.add(logicalInfo)
                        for (physId in physicalIds) {
                            // Skip if we already added this as a top-level camera
                            if (camera2Manager.cameraIdList.contains(physId)) {
                                val alreadyAdded = available.any { it.capabilities.cameraId == physId }
                                if (alreadyAdded) {
                                    debugLog.add("  Physical $physId: already top-level")
                                    Log.d(TAG, "  Physical $physId already added as top-level")
                                    continue
                                }
                            }
                            try {
                                val physChars = camera2Manager.getCameraCharacteristics(physId)
                                physicalCams.add(PhysicalCamInfo(cameraId, physId, physChars))
                                val physFl = physChars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
                                    ?.firstOrNull() ?: 0f
                                val physSensorSize = physChars.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
                                val physEqFl = if (physFl > 0 && physSensorSize != null && physSensorSize.width > 0) {
                                    (physFl * 36f / physSensorSize.width).roundToInt()
                                } else 0
                                val physInfo = "  Physical $physId: ${physEqFl}mm"
                                Log.d(TAG, physInfo)
                                debugLog.add(physInfo)
                            } catch (e: Exception) {
                                Log.w(TAG, "  Failed to query physical camera $physId", e)
                                debugLog.add("  Physical $physId: error — ${e.message}")
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to query camera $cameraId", e)
            }
        }

        // Add physical sub-cameras that weren't already discovered as top-level cameras.
        // These are accessed via the logical camera's selector — CameraX handles the routing.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            for ((logicalId, physId, physChars) in physicalCams) {
                // Skip if a top-level camera with the same focal length already exists
                val physFl = physChars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
                    ?.firstOrNull() ?: 0f
                val physSensorSize = physChars.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
                val physSensorW = physSensorSize?.width ?: 0f
                val physEqFl = if (physFl > 0 && physSensorW > 0) {
                    (physFl * 36f / physSensorW).roundToInt()
                } else 0

                // Deduplicate: skip if we already have a lens within 3mm equivalent focal length
                if (available.any { existing ->
                    val existingEqFl = if (existing.focalLength > 0 && existing.sensorWidth > 0) {
                        (existing.focalLength * 36f / existing.sensorWidth).roundToInt()
                    } else 0
                    kotlin.math.abs(existingEqFl - physEqFl) < 3
                }) {
                    val skipMsg = "Physical $physId (${physEqFl}mm) skipped — similar lens exists"
                    Log.d(TAG, skipMsg)
                    debugLog.add(skipMsg)
                    continue
                }

                val physApertures = physChars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES)
                val physPixelArray = physChars.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE)
                val physAperture = physApertures?.firstOrNull() ?: 0f
                val physSensorH = physSensorSize?.height ?: 0f
                val physMp = if (physPixelArray != null) {
                    (physPixelArray.width.toLong() * physPixelArray.height / 1_000_000f)
                } else 0f

                val physCaps2 = physChars.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: intArrayOf()
                val physHasManualSensor = CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR in physCaps2
                val physIsoRange = physChars.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
                val physExpRange = physChars.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
                val physSupportsManual = physHasManualSensor || (physIsoRange != null && physExpRange != null)
                val physMinFocus = physChars.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f
                val physMaxDigZoom = physChars.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM) ?: 1f
                val physHasRaw = CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_RAW in physCaps2
                val physOisModes = physChars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION)
                val physHasOis = physOisModes != null && CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON in physOisModes

                // Use the logical camera's selector — CameraX routes to the physical camera
                // based on zoom ratio / focal length
                val selector = CameraSelector.Builder()
                    .addCameraFilter { cameraInfos ->
                        cameraInfos.filter {
                            Camera2CameraInfo.from(it).cameraId == logicalId
                        }
                    }
                    .build()

                val label = if (physEqFl > 0) "Back ${physEqFl}mm" else "Back"
                val addMsg = "\u2705 Physical $physId via logical $logicalId: $label f/$physAperture ${physMp.roundToInt()}MP"
                Log.d(TAG, addMsg)
                debugLog.add(addMsg)

                available.add(
                    PhoneLens(
                        id = idx++,
                        label = label,
                        selector = selector,
                        focalLength = physFl,
                        aperture = physAperture,
                        sensorWidth = physSensorW,
                        sensorHeight = physSensorH,
                        megapixels = physMp,
                        facing = CameraMetadata.LENS_FACING_BACK,
                        capabilities = LensCapabilities(
                            supportsManualExposure = physSupportsManual,
                            isoRange = physIsoRange,
                            exposureTimeRange = physExpRange,
                            supportsManualFocus = physMinFocus > 0f,
                            minFocusDistance = physMinFocus,
                            cameraId = physId,
                            maxDigitalZoom = physMaxDigZoom,
                            supportsRaw = physHasRaw,
                            supportsOis = physHasOis,
                        ),
                    )
                )
            }
        }

        val summary = "Total: ${available.size} lenses"
        Log.d(TAG, summary)
        debugLog.add(summary)
        _cameraDebugLog.value = debugLog
        // Sort by focal length ascending (wide → tele)
        return available.sortedBy { it.focalLength }
    }

    /** Initialize without preview — for headless capture in trigger modes. */
    fun initializeHeadless(
        lifecycleOwner: LifecycleOwner,
        onReady: () -> Unit = {},
    ) {
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            val provider = try {
                future.get()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to get CameraProvider", e)
                _lastError.value = e.message
                onReady()
                return@addListener
            }
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
            val provider = try {
                future.get()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to get CameraProvider", e)
                _lastError.value = e.message
                onReady()
                return@addListener
            }
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
        // Reset state that's lens-specific
        resetLensState()
        _selectedLens.value = index
        val provider = cameraProvider ?: return
        bindCamera(provider, lifecycleOwner, previewView, lensList[index].selector)
    }

    /** Select lens without preview — for headless mode. */
    fun selectLensHeadless(index: Int, lifecycleOwner: LifecycleOwner) {
        val lensList = _lenses.value
        if (index < 0 || index >= lensList.size) return
        resetLensState()
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

    /** Reset zoom and manual controls when switching lenses. */
    private fun resetLensState() {
        _zoomRatio.value = 1f
        _manualIso.value = null
        _manualExposureNs.value = null
        _manualFocusDist.value = null
    }

    private fun bindCamera(
        provider: ProcessCameraProvider,
        lifecycleOwner: LifecycleOwner,
        previewView: PreviewView,
        selector: CameraSelector,
    ) {
        if (binding) return
        binding = true

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
        } finally {
            binding = false
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
                put(MediaStore.MediaColumns.RELATIVE_PATH, currentRelativePath())
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
                    output.savedUri?.let { _lastSavedUri.value = it }
                    Log.i(TAG, "Photo saved: ${output.savedUri}")
                    if (cont.isActive) cont.resume(true)
                }

                override fun onError(exception: ImageCaptureException) {
                    _isCapturing.value = false
                    _lastError.value = exception.message
                    Log.e(TAG, "Capture failed", exception)
                    if (cont.isActive) cont.resume(false)
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
                put(MediaStore.MediaColumns.RELATIVE_PATH, currentRelativePath())
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
                    output.savedUri?.let { _lastSavedUri.value = it }
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
