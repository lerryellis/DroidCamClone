package com.personal.droidcamclone

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.graphics.Rect
import android.hardware.camera2.*
import android.media.Image
import android.media.ImageReader
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import android.view.TextureView
import java.util.concurrent.LinkedBlockingQueue

enum class Resolution(val width: Int, val height: Int, val label: String) {
    P480(640, 480, "480p"),
    P720(1280, 720, "720p"),
    P1080(1920, 1080, "1080p")
}

class CameraHelper(
    private val context: Context,
    val frameQueue: LinkedBlockingQueue<ByteArray>,   // 1-slot queue — latest frame only
    private val ndiSender: NdiSender? = null
) {
    private val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var captureRequestBuilder: CaptureRequest.Builder? = null
    private var jpegReader: ImageReader? = null
    private var backgroundThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null
    private var currentCameraId: String? = null
    private var currentZoom = 1f

    var useFrontCamera = false
    var resolution = Resolution.P720

    // ── Camera ID ────────────────────────────────────────────────────────────
    private fun getCameraId(): String {
        return cameraManager.cameraIdList.first { id ->
            val facing = cameraManager.getCameraCharacteristics(id)
                .get(CameraCharacteristics.LENS_FACING)
            if (useFrontCamera) facing == CameraCharacteristics.LENS_FACING_FRONT
            else facing == CameraCharacteristics.LENS_FACING_BACK
        }
    }

    private fun startBackgroundThread() {
        backgroundThread = HandlerThread("CameraBackground").also { it.start() }
        backgroundHandler = Handler(backgroundThread!!.looper)
    }

    private fun stopBackgroundThread() {
        backgroundThread?.quitSafely()
        try { backgroundThread?.join() } catch (_: InterruptedException) {}
        backgroundThread = null
        backgroundHandler = null
    }

    // ── Open camera ──────────────────────────────────────────────────────────
    @SuppressLint("MissingPermission")
    fun openCamera(textureView: TextureView) {
        startBackgroundThread()

        // Use JPEG reader — acquireLatestImage drops stale frames automatically
        jpegReader = ImageReader.newInstance(
            resolution.width, resolution.height, ImageFormat.JPEG, 2
        ).apply {
            setOnImageAvailableListener({ reader ->
                val image: Image? = reader.acquireLatestImage()
                image?.use {
                    val buf = it.planes[0].buffer
                    val bytes = ByteArray(buf.remaining()).also { b -> buf.get(b) }
                    // Drop any queued frame so server always gets the newest
                    frameQueue.poll()
                    frameQueue.offer(bytes)
                    ndiSender?.pushFrame(bytes, resolution.width, resolution.height)
                }
            }, backgroundHandler)
        }

        val cameraId = getCameraId()
        currentCameraId = cameraId
        cameraManager.openCamera(cameraId, object : CameraDevice.StateCallback() {
            override fun onOpened(camera: CameraDevice) {
                cameraDevice = camera
                createCaptureSession(textureView)
            }
            override fun onDisconnected(camera: CameraDevice) { camera.close() }
            override fun onError(camera: CameraDevice, error: Int) { camera.close() }
        }, backgroundHandler)
    }

    private fun createCaptureSession(textureView: TextureView) {
        val texture = textureView.surfaceTexture ?: return
        texture.setDefaultBufferSize(resolution.width, resolution.height)
        val previewSurface = Surface(texture)

        captureRequestBuilder = cameraDevice!!
            .createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                addTarget(previewSurface)
                addTarget(jpegReader!!.surface)
                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
                set(CaptureRequest.CONTROL_AE_MODE,  CaptureRequest.CONTROL_AE_MODE_ON)
            }

        @Suppress("DEPRECATION")
        cameraDevice!!.createCaptureSession(
            listOf(previewSurface, jpegReader!!.surface),
            object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    captureSession = session
                    session.setRepeatingRequest(captureRequestBuilder!!.build(), null, backgroundHandler)
                }
                override fun onConfigureFailed(session: CameraCaptureSession) {}
            },
            backgroundHandler
        )
    }

    // ── Zoom ─────────────────────────────────────────────────────────────────
    fun setZoom(ratio: Float) {
        val id      = currentCameraId ?: return
        val builder = captureRequestBuilder ?: return
        val session = captureSession ?: return
        val chars   = cameraManager.getCameraCharacteristics(id)
        val maxZoom = chars.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM) ?: 1f
        currentZoom = ratio.coerceIn(1f, maxZoom)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            builder.set(CaptureRequest.CONTROL_ZOOM_RATIO, currentZoom)
        } else {
            val sensor = chars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE) ?: return
            val cx = sensor.width() / 2;  val cy = sensor.height() / 2
            val hw = (sensor.width()  / (2f * currentZoom)).toInt()
            val hh = (sensor.height() / (2f * currentZoom)).toInt()
            builder.set(CaptureRequest.SCALER_CROP_REGION, Rect(cx-hw, cy-hh, cx+hw, cy+hh))
        }
        applyRequest()
    }

    // ── Torch ─────────────────────────────────────────────────────────────────
    fun setTorch(on: Boolean) {
        captureRequestBuilder?.set(
            CaptureRequest.FLASH_MODE,
            if (on) CaptureRequest.FLASH_MODE_TORCH else CaptureRequest.FLASH_MODE_OFF
        )
        applyRequest()
    }

    // ── Exposure lock ─────────────────────────────────────────────────────────
    fun setExposureLock(lock: Boolean) {
        captureRequestBuilder?.set(
            CaptureRequest.CONTROL_AE_LOCK, lock
        )
        applyRequest()
    }

    // ── White balance ─────────────────────────────────────────────────────────
    fun setWhiteBalance(mode: String) {
        val wbMode = when (mode) {
            "auto"            -> CaptureRequest.CONTROL_AWB_MODE_AUTO
            "incandescent"    -> CaptureRequest.CONTROL_AWB_MODE_INCANDESCENT
            "warm-fluorescent"-> CaptureRequest.CONTROL_AWB_MODE_WARM_FLUORESCENT
            "fluorescent"     -> CaptureRequest.CONTROL_AWB_MODE_FLUORESCENT
            "daylight"        -> CaptureRequest.CONTROL_AWB_MODE_DAYLIGHT
            "cloudy-daylight" -> CaptureRequest.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT
            "twilight"        -> CaptureRequest.CONTROL_AWB_MODE_TWILIGHT
            "shade"           -> CaptureRequest.CONTROL_AWB_MODE_SHADE
            else              -> CaptureRequest.CONTROL_AWB_MODE_AUTO
        }
        captureRequestBuilder?.set(CaptureRequest.CONTROL_AWB_MODE, wbMode)
        applyRequest()
    }

    private fun applyRequest() {
        try {
            val session = captureSession ?: return
            val builder = captureRequestBuilder ?: return
            session.setRepeatingRequest(builder.build(), null, backgroundHandler)
        } catch (_: Exception) {}
    }

    // ── Close ─────────────────────────────────────────────────────────────────
    fun closeCamera() {
        captureSession?.close();         captureSession = null
        captureRequestBuilder = null
        cameraDevice?.close();           cameraDevice = null
        jpegReader?.close();             jpegReader = null
        currentCameraId = null;          currentZoom = 1f
        stopBackgroundThread()
        frameQueue.clear()
    }
}
