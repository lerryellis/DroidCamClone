package com.personal.droidcamclone

import android.util.Log
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean

/**
 * NDI sender wrapper. Guards all NDI calls behind BuildConfig.NDI_ENABLED so the app
 * compiles and runs MJPEG-only without the NDI native libraries present.
 *
 * When NDI_ENABLED=true and the NDI SDK .so files are in app/libs/jni/:
 *   - NDIlib_initialize() is called once
 *   - An NDI send instance is created named "DroidCam Clone (Android)"
 *   - RGBA frames pushed via pushFrame() are forwarded to NDI on a background thread
 *
 * Frame format expected: RGBA byte array, width × height × 4 bytes.
 */
class NdiSender(private val sourceName: String = "DroidCam Clone (Android)") {

    companion object {
        private const val TAG = "NdiSender"
        private const val QUEUE_CAPACITY = 4

        // Load the NDI native library — only present if SDK was downloaded and placed in app/libs/
        private var ndiLibLoaded = false
        init {
            try {
                System.loadLibrary("ndi")
                ndiLibLoaded = true
                Log.i(TAG, "NDI native library loaded")
            } catch (e: UnsatisfiedLinkError) {
                Log.w(TAG, "NDI native library not found — NDI disabled at runtime")
            }
        }
    }

    // Native method declarations — implemented by libndi.so via JNI bridge (NdiJniBridge.cpp)
    private external fun nativeInit(sourceName: String): Long
    private external fun nativeSendFrame(handle: Long, rgba: ByteArray, width: Int, height: Int, frameRateN: Int, frameRateD: Int)
    private external fun nativeRelease(handle: Long)

    private var ndiHandle: Long = 0L
    private val running = AtomicBoolean(false)
    private val frameQueue = ArrayBlockingQueue<FrameData>(QUEUE_CAPACITY)
    private var senderThread: Thread? = null

    var width: Int = 1280
    var height: Int = 720

    data class FrameData(val rgba: ByteArray, val width: Int, val height: Int)

    fun start(): Boolean {
        if (!BuildConfig.NDI_ENABLED) {
            Log.i(TAG, "NDI_ENABLED=false, skipping NDI init")
            return false
        }
        if (!ndiLibLoaded) {
            Log.w(TAG, "NDI library not loaded, cannot start")
            return false
        }
        return try {
            ndiHandle = nativeInit(sourceName)
            if (ndiHandle == 0L) {
                Log.e(TAG, "nativeInit returned null handle")
                return false
            }
            running.set(true)
            senderThread = Thread(::senderLoop, "NdiSenderThread").also {
                it.isDaemon = true
                it.start()
            }
            Log.i(TAG, "NDI sender started: $sourceName")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start NDI sender", e)
            false
        }
    }

    fun stop() {
        running.set(false)
        senderThread?.interrupt()
        senderThread?.join(2000)
        senderThread = null
        frameQueue.clear()
        if (ndiHandle != 0L && ndiLibLoaded) {
            try { nativeRelease(ndiHandle) } catch (_: Exception) {}
            ndiHandle = 0L
        }
        Log.i(TAG, "NDI sender stopped")
    }

    fun pushFrame(rgba: ByteArray, width: Int, height: Int) {
        if (!running.get()) return
        // Drop oldest if full to avoid blocking camera thread
        if (frameQueue.remainingCapacity() == 0) frameQueue.poll()
        frameQueue.offer(FrameData(rgba.copyOf(), width, height))
    }

    private fun senderLoop() {
        while (running.get()) {
            try {
                val frame = frameQueue.poll(100, java.util.concurrent.TimeUnit.MILLISECONDS)
                    ?: continue
                if (ndiHandle != 0L && ndiLibLoaded) {
                    nativeSendFrame(ndiHandle, frame.rgba, frame.width, frame.height, 30000, 1001)
                }
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                break
            } catch (e: Exception) {
                Log.e(TAG, "NDI send error", e)
            }
        }
    }

    val isActive: Boolean get() = running.get()
}
