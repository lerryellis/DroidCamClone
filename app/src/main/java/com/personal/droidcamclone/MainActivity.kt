package com.personal.droidcamclone

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.graphics.SurfaceTexture
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.text.format.Formatter
import android.view.ScaleGestureDetector
import android.view.TextureView
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.util.concurrent.LinkedBlockingQueue

class MainActivity : AppCompatActivity(), TextureView.SurfaceTextureListener {

    private lateinit var textureView:      TextureView
    private lateinit var tvIpAddress:      TextView
    private lateinit var tvStatus:         TextView
    private lateinit var tvMjpegUrl:       TextView
    private lateinit var tvNdiName:        TextView
    private lateinit var btnStartStop:     Button
    private lateinit var btnSwitchCamera:  Button
    private lateinit var btnMic:           Button
    private lateinit var btnTorch:         Button
    private lateinit var spinnerResolution: Spinner

    private var cameraHelper: CameraHelper? = null
    private var mjpegServer:  MjpegServer?  = null
    private var ndiSender:    NdiSender?    = null
    private var audioCapture: AudioCapture? = null
    private var frameQueue:   LinkedBlockingQueue<ByteArray>? = null

    private var isStreaming  = false
    private var isMicOn      = false
    private var isTorchOn    = false
    private var serviceBound = false
    private var useFrontCamera = false
    private var currentZoom  = 1f

    private lateinit var scaleGestureDetector: ScaleGestureDetector

    // ── Service connection ────────────────────────────────────────────────────
    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            val binder  = service as StreamService.LocalBinder
            mjpegServer = binder.getServer()
            ndiSender   = binder.getNdiSender()
            frameQueue  = binder.getFrameQueue()

            // Wire camera control callbacks from the web UI
            mjpegServer?.cameraAction = { action ->
                runOnUiThread { handleCameraAction(action) }
            }

            serviceBound = true
            reopenCameraWithService()
            updateStreamingUi()
        }
        override fun onServiceDisconnected(name: ComponentName) {
            serviceBound = false
            mjpegServer  = null
            ndiSender    = null
            frameQueue   = null
        }
    }

    // ── onCreate ──────────────────────────────────────────────────────────────
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        textureView      = findViewById(R.id.textureView)
        tvIpAddress      = findViewById(R.id.tvIpAddress)
        tvStatus         = findViewById(R.id.tvStatus)
        tvMjpegUrl       = findViewById(R.id.tvMjpegUrl)
        tvNdiName        = findViewById(R.id.tvNdiName)
        btnStartStop     = findViewById(R.id.btnStartStop)
        btnSwitchCamera  = findViewById(R.id.btnSwitchCamera)
        btnMic           = findViewById(R.id.btnMic)
        btnTorch         = findViewById(R.id.btnTorch)
        spinnerResolution = findViewById(R.id.spinnerResolution)

        // Pinch-to-zoom
        scaleGestureDetector = ScaleGestureDetector(this,
            object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                override fun onScale(detector: ScaleGestureDetector): Boolean {
                    currentZoom = (currentZoom * detector.scaleFactor).coerceIn(1f, 10f)
                    cameraHelper?.setZoom(currentZoom)
                    return true
                }
            })
        textureView.setOnTouchListener { _, event -> scaleGestureDetector.onTouchEvent(event); true }
        textureView.surfaceTextureListener = this

        setupResolutionSpinner()
        displayIpAddress()
        checkPermissions()

        btnStartStop.setOnClickListener    { if (isStreaming) stopStream() else startStream() }
        btnSwitchCamera.setOnClickListener { useFrontCamera = !useFrontCamera; currentZoom = 1f; restartCamera() }
        btnMic.setOnClickListener          { toggleMic() }
        btnTorch.setOnClickListener        { toggleTorch() }
    }

    // ── Resolution spinner ────────────────────────────────────────────────────
    private fun setupResolutionSpinner() {
        val adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_item,
            Resolution.values().map { it.label }
        )
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerResolution.adapter = adapter
        spinnerResolution.setSelection(1) // 720p default

        spinnerResolution.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>, view: View?, pos: Int, id: Long) {
                val selected = Resolution.values()[pos]
                if (cameraHelper?.resolution != selected) {
                    cameraHelper?.closeCamera()
                    cameraHelper = null
                    if (textureView.isAvailable) openCamera()
                }
                mjpegServer?.currentResolution = selected.label
            }
            override fun onNothingSelected(parent: AdapterView<*>) {}
        }
    }

    // ── IP display ────────────────────────────────────────────────────────────
    private fun getWifiIp(): String = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val cm = applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val network = cm.activeNetwork ?: return "0.0.0.0"
            val caps = cm.getNetworkCapabilities(network) ?: return "0.0.0.0"
            if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return "0.0.0.0"
            cm.getLinkProperties(network)
                ?.linkAddresses
                ?.firstOrNull { it.address is java.net.Inet4Address }
                ?.address?.hostAddress ?: "0.0.0.0"
        } else {
            val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            @Suppress("DEPRECATION")
            Formatter.formatIpAddress(wm.connectionInfo.ipAddress)
        }
    } catch (_: Exception) { "0.0.0.0" }

    private fun displayIpAddress() {
        tvIpAddress.text = "WiFi: ${getWifiIp()}"
    }

    // ── Permissions ───────────────────────────────────────────────────────────
    private fun checkPermissions() {
        val needed = mutableListOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            needed.add(Manifest.permission.POST_NOTIFICATIONS)
        val missing = needed.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty())
            ActivityCompat.requestPermissions(this, missing.toTypedArray(), 100)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (grantResults.all { it == PackageManager.PERMISSION_GRANTED })
            if (textureView.isAvailable && cameraHelper == null) openCamera()
    }

    // ── Camera ────────────────────────────────────────────────────────────────
    private fun openCamera() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED) return

        val queue = frameQueue ?: LinkedBlockingQueue(1)
        val res   = Resolution.values()[spinnerResolution.selectedItemPosition]
        cameraHelper = CameraHelper(this, queue, ndiSender).apply {
            resolution     = res
            useFrontCamera = this@MainActivity.useFrontCamera
        }
        cameraHelper?.openCamera(textureView)
        mjpegServer?.currentResolution = res.label
    }

    private fun restartCamera() {
        isTorchOn = false
        btnTorch.text = getString(R.string.torch_off)
        btnTorch.backgroundTintList = getColorStateList(android.R.color.darker_gray)
        cameraHelper?.closeCamera(); cameraHelper = null
        if (textureView.isAvailable) openCamera()
    }

    private fun reopenCameraWithService() {
        cameraHelper?.closeCamera(); cameraHelper = null
        if (textureView.isAvailable) openCamera()
    }

    // ── Mic toggle ────────────────────────────────────────────────────────────
    private fun toggleMic() {
        if (!isMicOn) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 101)
                return
            }
            audioCapture = AudioCapture().also { it.start() }
            mjpegServer?.audioCapture = audioCapture
            isMicOn = true
            btnMic.text = "Mic: ON"
            btnMic.backgroundTintList = getColorStateList(android.R.color.holo_orange_dark)
        } else {
            audioCapture?.stop(); audioCapture = null
            mjpegServer?.audioCapture = null
            isMicOn = false
            btnMic.text = getString(R.string.mic_off)
            btnMic.backgroundTintList = getColorStateList(android.R.color.darker_gray)
        }
    }

    // ── Torch toggle ──────────────────────────────────────────────────────────
    private fun toggleTorch() {
        isTorchOn = !isTorchOn
        cameraHelper?.setTorch(isTorchOn)
        btnTorch.text = if (isTorchOn) "Torch: ON" else getString(R.string.torch_off)
        btnTorch.backgroundTintList = getColorStateList(
            if (isTorchOn) android.R.color.holo_orange_light else android.R.color.darker_gray
        )
    }

    // ── Camera actions from web UI ────────────────────────────────────────────
    private fun handleCameraAction(action: String) {
        when {
            action == "torch_on"           -> { isTorchOn = true;  cameraHelper?.setTorch(true)  }
            action == "torch_off"          -> { isTorchOn = false; cameraHelper?.setTorch(false) }
            action == "exposure_lock_on"   -> cameraHelper?.setExposureLock(true)
            action == "exposure_lock_off"  -> cameraHelper?.setExposureLock(false)
            action.startsWith("wb_")       -> cameraHelper?.setWhiteBalance(action.removePrefix("wb_"))
            action.startsWith("zoom_")     -> {
                currentZoom = action.removePrefix("zoom_").toFloatOrNull() ?: 1f
                cameraHelper?.setZoom(currentZoom)
            }
        }
    }

    // ── Stream start / stop ───────────────────────────────────────────────────
    private fun startStream() {
        val serviceIntent = Intent(this, StreamService::class.java)
        ContextCompat.startForegroundService(this, serviceIntent)
        bindService(serviceIntent, serviceConnection, Context.BIND_AUTO_CREATE)
        isStreaming = true
        btnStartStop.text = getString(R.string.stop_stream)
        tvStatus.text     = getString(R.string.status_streaming)
    }

    private fun updateStreamingUi() {
        val ip  = getWifiIp()
        val url = "http://$ip:${MjpegServer.PORT}/mjpegfeed"
        tvMjpegUrl.text       = "MJPEG: $url"
        tvMjpegUrl.visibility = View.VISIBLE

        if (BuildConfig.NDI_ENABLED && ndiSender?.isActive == true) {
            tvNdiName.text       = "NDI: ${StreamService.NDI_SOURCE_NAME}"
            tvNdiName.visibility = View.VISIBLE
        } else {
            tvNdiName.visibility = View.GONE
        }
    }

    private fun stopStream() {
        // Stop mic
        if (isMicOn) {
            audioCapture?.stop(); audioCapture = null
            mjpegServer?.audioCapture = null
            isMicOn = false
            btnMic.text = getString(R.string.mic_off)
            btnMic.backgroundTintList = getColorStateList(android.R.color.darker_gray)
        }
        // Reset torch state
        isTorchOn = false
        btnTorch.text = getString(R.string.torch_off)
        btnTorch.backgroundTintList = getColorStateList(android.R.color.darker_gray)

        if (serviceBound) { unbindService(serviceConnection); serviceBound = false }
        stopService(Intent(this, StreamService::class.java))
        mjpegServer = null; ndiSender = null; frameQueue = null

        cameraHelper?.closeCamera(); cameraHelper = null
        currentZoom = 1f
        if (textureView.isAvailable) openCamera()

        isStreaming = false
        btnStartStop.text     = getString(R.string.start_stream)
        tvStatus.text         = getString(R.string.status_idle)
        tvMjpegUrl.visibility = View.INVISIBLE
        tvNdiName.visibility  = View.GONE
    }

    // ── TextureView callbacks ─────────────────────────────────────────────────
    override fun onSurfaceTextureAvailable(surface: SurfaceTexture, w: Int, h: Int) {
        if (cameraHelper == null) openCamera()
    }
    override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, w: Int, h: Int) {}
    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean = true
    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {}

    override fun onDestroy() {
        audioCapture?.stop()
        cameraHelper?.closeCamera()
        if (serviceBound) unbindService(serviceConnection)
        super.onDestroy()
    }
}
