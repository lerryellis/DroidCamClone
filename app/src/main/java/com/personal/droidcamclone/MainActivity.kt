package com.personal.droidcamclone

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.graphics.SurfaceTexture
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.text.format.Formatter
import android.view.TextureView
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity(), TextureView.SurfaceTextureListener {

    private lateinit var textureView: TextureView
    private lateinit var tvIpAddress: TextView
    private lateinit var tvStatus: TextView
    private lateinit var tvMjpegUrl: TextView
    private lateinit var tvNdiName: TextView
    private lateinit var btnStartStop: Button
    private lateinit var btnSwitchCamera: Button
    private lateinit var spinnerResolution: Spinner

    private var cameraHelper: CameraHelper? = null
    private var mjpegServer: MjpegServer? = null
    private var ndiSender: NdiSender? = null
    private var isStreaming = false
    private var serviceBound = false
    private var useFrontCamera = false

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            val binder = service as StreamService.LocalBinder
            mjpegServer = binder.getServer()
            ndiSender = binder.getNdiSender()
            serviceBound = true
            reopenCameraWithService()
            updateStreamingUi()
        }
        override fun onServiceDisconnected(name: ComponentName) {
            serviceBound = false
            mjpegServer = null
            ndiSender = null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        textureView = findViewById(R.id.textureView)
        tvIpAddress = findViewById(R.id.tvIpAddress)
        tvStatus = findViewById(R.id.tvStatus)
        tvMjpegUrl = findViewById(R.id.tvMjpegUrl)
        tvNdiName = findViewById(R.id.tvNdiName)
        btnStartStop = findViewById(R.id.btnStartStop)
        btnSwitchCamera = findViewById(R.id.btnSwitchCamera)
        spinnerResolution = findViewById(R.id.spinnerResolution)

        textureView.surfaceTextureListener = this
        setupResolutionSpinner()
        displayIpAddress()
        checkPermissions()

        btnStartStop.setOnClickListener {
            if (isStreaming) stopStream() else startStream()
        }
        btnSwitchCamera.setOnClickListener {
            useFrontCamera = !useFrontCamera
            restartCamera()
        }
    }

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

    private fun getWifiIp(): String {
        val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        @Suppress("DEPRECATION")
        return Formatter.formatIpAddress(wm.connectionInfo.ipAddress)
    }

    private fun displayIpAddress() {
        val ip = getWifiIp()
        tvIpAddress.text = "WiFi: $ip"
    }

    private fun checkPermissions() {
        val needed = mutableListOf(Manifest.permission.CAMERA)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            needed.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        val missing = needed.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, missing.toTypedArray(), 100)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 100 && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
            if (textureView.isAvailable) openCamera()
        }
    }

    private fun openCamera() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED) return

        val server = mjpegServer
        val frameRef = server?.latestFrame
            ?: java.util.concurrent.atomic.AtomicReference()

        val res = Resolution.values()[spinnerResolution.selectedItemPosition]
        cameraHelper = CameraHelper(this, frameRef, ndiSender).apply {
            resolution = res
            useFrontCamera = this@MainActivity.useFrontCamera
        }
        cameraHelper?.openCamera(textureView)
        mjpegServer?.currentResolution = res.label
    }

    private fun restartCamera() {
        cameraHelper?.closeCamera()
        cameraHelper = null
        if (textureView.isAvailable) openCamera()
    }

    private fun reopenCameraWithService() {
        cameraHelper?.closeCamera()
        cameraHelper = null
        if (textureView.isAvailable) openCamera()
    }

    private fun startStream() {
        val serviceIntent = Intent(this, StreamService::class.java)
        ContextCompat.startForegroundService(this, serviceIntent)
        bindService(serviceIntent, serviceConnection, Context.BIND_AUTO_CREATE)
        isStreaming = true
        btnStartStop.text = getString(R.string.stop_stream)
        tvStatus.text = getString(R.string.status_streaming)
    }

    private fun updateStreamingUi() {
        val ip = getWifiIp()
        val mjpegUrl = "http://$ip:${MjpegServer.PORT}/video"
        tvMjpegUrl.text = "MJPEG: $mjpegUrl"
        tvMjpegUrl.visibility = View.VISIBLE

        if (BuildConfig.NDI_ENABLED && ndiSender?.isActive == true) {
            tvNdiName.text = "NDI: ${StreamService.NDI_SOURCE_NAME}"
            tvNdiName.visibility = View.VISIBLE
        } else {
            tvNdiName.visibility = View.GONE
        }
    }

    private fun stopStream() {
        if (serviceBound) {
            unbindService(serviceConnection)
            serviceBound = false
        }
        stopService(Intent(this, StreamService::class.java))
        mjpegServer = null
        ndiSender = null

        cameraHelper?.closeCamera()
        cameraHelper = null
        if (textureView.isAvailable) openCamera()

        isStreaming = false
        btnStartStop.text = getString(R.string.start_stream)
        tvStatus.text = getString(R.string.status_idle)
        tvMjpegUrl.visibility = View.INVISIBLE
        tvNdiName.visibility = View.GONE
    }

    override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
        openCamera()
    }
    override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {}
    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean = true
    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {}

    override fun onDestroy() {
        cameraHelper?.closeCamera()
        if (serviceBound) unbindService(serviceConnection)
        super.onDestroy()
    }
}
