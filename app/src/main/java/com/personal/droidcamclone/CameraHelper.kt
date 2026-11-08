package com.personal.droidcamclone

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.graphics.PixelFormat
import android.hardware.camera2.*
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import android.view.TextureView
import java.util.concurrent.atomic.AtomicReference

enum class Resolution(val width: Int, val height: Int, val label: String) {
    P480(640, 480, "480p"),
    P720(1280, 720, "720p"),
    P1080(1920, 1080, "1080p")
}

class CameraHelper(
    private val context: Context,
    private val jpegFrameRef: AtomicReference<ByteArray?>,
    private val ndiSender: NdiSender? = null
) {
    private val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var jpegReader: ImageReader? = null
    private var backgroundThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null

    var useFrontCamera = false
    var resolution = Resolution.P720

    private fun getCameraId(): String {
        return cameraManager.cameraIdList.first { id ->
            val chars = cameraManager.getCameraCharacteristics(id)
            val facing = chars.get(CameraCharacteristics.LENS_FACING)
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
        backgroundThread?.join()
        backgroundThread = null
        backgroundHandler = null
    }

    @SuppressLint("MissingPermission")
    fun openCamera(textureView: TextureView, onOpened: () -> Unit = {}) {
        startBackgroundThread()

        // JPEG reader for MJPEG server
        jpegReader = ImageReader.newInstance(
            resolution.width, resolution.height, ImageFormat.JPEG, 2
        ).apply {
            setOnImageAvailableListener({ reader ->
                val image: Image? = reader.acquireLatestImage()
                image?.use {
                    val buffer = it.planes[0].buffer
                    val bytes = ByteArray(buffer.remaining())
                    buffer.get(bytes)
                    jpegFrameRef.set(bytes)
                }
            }, backgroundHandler)
        }

        val cameraId = getCameraId()
        cameraManager.openCamera(cameraId, object : CameraDevice.StateCallback() {
            override fun onOpened(camera: CameraDevice) {
                cameraDevice = camera
                createCaptureSession(textureView)
                onOpened()
            }
            override fun onDisconnected(camera: CameraDevice) { camera.close() }
            override fun onError(camera: CameraDevice, error: Int) { camera.close() }
        }, backgroundHandler)
    }

    private fun createCaptureSession(textureView: TextureView) {
        val texture = textureView.surfaceTexture ?: return
        texture.setDefaultBufferSize(resolution.width, resolution.height)
        val previewSurface = Surface(texture)

        val outputs = mutableListOf(previewSurface, jpegReader!!.surface)

        val captureRequest = cameraDevice!!.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
            addTarget(previewSurface)
            addTarget(jpegReader!!.surface)
            set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
            set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
        }

        cameraDevice!!.createCaptureSession(
            outputs,
            object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    captureSession = session
                    session.setRepeatingRequest(captureRequest.build(), null, backgroundHandler)
                }
                override fun onConfigureFailed(session: CameraCaptureSession) {}
            },
            backgroundHandler
        )
    }

    fun closeCamera() {
        captureSession?.close()
        captureSession = null
        cameraDevice?.close()
        cameraDevice = null
        jpegReader?.close()
        jpegReader = null
        stopBackgroundThread()
        jpegFrameRef.set(null)
    }
}
