package com.personal.droidcamclone

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.text.format.Formatter
import android.util.Log
import androidx.core.app.NotificationCompat

class StreamService : Service() {

    companion object {
        const val CHANNEL_ID = "droidcam_stream"
        const val NOTIFICATION_ID = 1
        private const val TAG = "StreamService"
        const val NDI_SOURCE_NAME = "DroidCam Clone (Android)"
    }

    inner class LocalBinder : Binder() {
        fun getServer(): MjpegServer = server
        fun getNdiSender(): NdiSender = ndiSender
    }

    private val binder = LocalBinder()
    val server = MjpegServer()
    val ndiSender = NdiSender(NDI_SOURCE_NAME)

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val ip = getWifiIp()
        server.currentIp = ip

        startForeground(NOTIFICATION_ID, buildNotification(ip))

        // Start MJPEG server
        if (!server.isAlive) {
            try { server.start() } catch (e: Exception) {
                Log.e(TAG, "Failed to start MJPEG server", e)
            }
        }

        // Start NDI sender (no-op if NDI_ENABLED=false or native lib not present)
        val ndiStarted = ndiSender.start()
        server.ndiActive = ndiStarted
        server.ndiSourceName = NDI_SOURCE_NAME
        if (ndiStarted) {
            Log.i(TAG, "NDI sender started: $NDI_SOURCE_NAME")
        } else {
            Log.i(TAG, "NDI not active (NDI_ENABLED=${BuildConfig.NDI_ENABLED})")
        }

        return START_STICKY
    }

    override fun onBind(intent: Intent): IBinder = binder

    override fun onDestroy() {
        ndiSender.stop()
        server.stop()
        super.onDestroy()
    }

    private fun getWifiIp(): String {
        return try {
            val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            @Suppress("DEPRECATION")
            Formatter.formatIpAddress(wm.connectionInfo.ipAddress)
        } catch (_: Exception) { "0.0.0.0" }
    }

    private fun buildNotification(ip: String): Notification {
        val openIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        val ndiStatus = if (BuildConfig.NDI_ENABLED && ndiSender.isActive) " | NDI: $NDI_SOURCE_NAME" else ""
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("DroidCam Clone — Streaming")
            .setContentText("MJPEG: http://$ip:${MjpegServer.PORT}/video$ndiStatus")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentIntent(openIntent)
            .setOngoing(true)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "Stream Service", NotificationManager.IMPORTANCE_LOW
            ).apply { description = "DroidCam Clone MJPEG/NDI streaming service" }
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        }
    }
}
