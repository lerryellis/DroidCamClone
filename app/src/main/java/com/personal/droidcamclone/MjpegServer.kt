package com.personal.droidcamclone

import fi.iki.elonen.NanoHTTPD
import org.json.JSONObject
import java.io.IOException
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

class MjpegServer(private val serverIp: String = "0.0.0.0") : NanoHTTPD(PORT) {

    companion object {
        const val PORT = 4747
        private const val BOUNDARY = "mjpegframe"

        private val CSS = """
            <style>
            *{box-sizing:border-box;margin:0;padding:0}
            body{background:#111;color:#eee;font-family:'Segoe UI',sans-serif;padding:0 16px 32px}
            header{background:#1a1a2e;padding:16px;margin:0 -16px 24px;border-bottom:2px solid #4FC3F7}
            h1{color:#4FC3F7;font-size:1.4rem}
            h2{color:#90CAF9;font-size:1.1rem;margin:20px 0 8px}
            h3{color:#B0BEC5;font-size:.95rem;margin:14px 0 6px}
            p,li{color:#ccc;font-size:.9rem;line-height:1.6}
            ul{padding-left:20px}
            a{color:#4FC3F7;text-decoration:none}
            a:hover{text-decoration:underline}
            .card{background:#1e1e2e;border-radius:8px;padding:16px;margin:12px 0;border:1px solid #333}
            .pill{display:inline-block;background:#263238;border-radius:4px;padding:3px 8px;
                  font-family:monospace;font-size:.85rem;color:#80DEEA}
            img.preview{width:100%;max-width:640px;border-radius:6px;border:2px solid #333;
                        display:block;margin:12px 0}
            .badge{display:inline-block;background:#4CAF50;color:#fff;border-radius:3px;
                   padding:2px 7px;font-size:.75rem;margin-left:6px;vertical-align:middle}
            .badge.ndi{background:#E91E63}
            nav a{color:#90CAF9;margin-right:16px;font-size:.9rem}
            .btn{display:inline-block;padding:6px 14px;border-radius:4px;background:#263238;
                 color:#80DEEA;cursor:pointer;border:1px solid #37474F;margin:4px 2px;
                 text-decoration:none;font-size:.85rem}
            .btn:hover{background:#37474F}
            .btn.on{background:#1B5E20;border-color:#4CAF50;color:#A5D6A7}
            </style>
        """.trimIndent()

        private val NAV = """
            <nav style="margin-bottom:16px">
            <a href="/">Home</a>
            <a href="/downloads">Downloads</a>
            <a href="/info">JSON Info</a>
            </nav>
        """.trimIndent()
    }

    // 1-slot queue — the MJPEG thread blocks here instead of sleep-polling
    val frameQueue = LinkedBlockingQueue<ByteArray>(1)

    var ndiSourceName: String = "DroidCam Clone (Android)"
    var ndiActive: Boolean = false
    var currentResolution: String = "720p"
    var currentIp: String = serverIp
    var audioCapture: AudioCapture? = null

    // Camera control callback — wired by MainActivity after service bind
    var cameraAction: ((String) -> Unit)? = null

    @Volatile private var streaming = false

    override fun serve(session: IHTTPSession): Response {
        return when {
            session.uri == "/mjpegfeed" || session.uri == "/video" -> serveVideo()
            session.uri == "/audio"               -> serveAudio()
            session.uri == "/info"                -> serveInfo()
            session.uri == "/downloads"           -> serveDownloads()
            session.uri.startsWith("/cam/")       -> handleCamControl(session.uri)
            else                                  -> serveHome()
        }
    }

    // ── MJPEG video ──────────────────────────────────────────────────────────
    private fun serveVideo(): Response {
        val pipedOut = PipedOutputStream()
        val pipedIn  = PipedInputStream(pipedOut, 2 * 1024 * 1024)
        streaming = true

        Thread {
            try {
                while (streaming) {
                    // Block until a fresh frame arrives — zero polling delay
                    val frame = frameQueue.poll(300, TimeUnit.MILLISECONDS) ?: continue
                    val header = "--$BOUNDARY\r\nContent-Type: image/jpeg\r\n" +
                                 "Content-Length: ${frame.size}\r\n\r\n"
                    pipedOut.write(header.toByteArray())
                    pipedOut.write(frame)
                    pipedOut.write("\r\n".toByteArray())
                    pipedOut.flush()
                }
            } catch (_: IOException) {
            } finally {
                try { pipedOut.close() } catch (_: Exception) {}
            }
        }.also { it.isDaemon = true }.start()

        return newChunkedResponse(
            Response.Status.OK,
            "multipart/x-mixed-replace; boundary=$BOUNDARY",
            pipedIn
        ).also {
            it.addHeader("Cache-Control", "no-cache, no-store, must-revalidate")
            it.addHeader("Pragma",        "no-cache")
            it.addHeader("Expires",       "0")
            it.addHeader("Connection",    "keep-alive")
        }
    }

    // ── AAC audio (ADTS frames) ───────────────────────────────────────────────
    private fun serveAudio(): Response {
        val capture = audioCapture
            ?: return newFixedLengthResponse(
                Response.Status.SERVICE_UNAVAILABLE, "text/plain",
                "Mic not enabled — tap Mic button in the app first.\n" +
                "VLC: vlc http://$currentIp:$PORT/audio\n" +
                "ffplay: ffplay -f aac http://$currentIp:$PORT/audio"
            )

        val pipedOut = PipedOutputStream()
        val pipedIn  = PipedInputStream(pipedOut, 128 * 1024)

        val listener = object : (ByteArray) -> Unit {
            override fun invoke(frame: ByteArray) {
                try {
                    pipedOut.write(frame)
                    pipedOut.flush()
                } catch (_: IOException) {
                    capture.removeListener(this)   // self-remove on disconnect
                }
            }
        }
        capture.addListener(listener)

        return newChunkedResponse(Response.Status.OK, "audio/aac", pipedIn).also {
            it.addHeader("Cache-Control", "no-cache")
            it.addHeader("Connection",    "keep-alive")
        }
    }

    // ── Camera HTTP controls (match real DroidCam API) ────────────────────────
    private fun handleCamControl(uri: String): Response {
        val action = uri.removePrefix("/cam/1/")
        when {
            action == "torch" || action == "set_torch"       -> cameraAction?.invoke("torch_on")
            action == "unset_torch"                          -> cameraAction?.invoke("torch_off")
            action == "set_exposure_lock"                    -> cameraAction?.invoke("exposure_lock_on")
            action == "unset_exposure_lock"                  -> cameraAction?.invoke("exposure_lock_off")
            action.startsWith("set_wb")                      -> {
                val mode = action.substringAfter("set_wb/", "auto")
                cameraAction?.invoke("wb_$mode")
            }
            action == "fpslimit"                             -> cameraAction?.invoke("fpslimit")
            action.startsWith("set_zoom")                    -> {
                val z = action.substringAfter("set_zoom/").toFloatOrNull() ?: 1f
                cameraAction?.invoke("zoom_$z")
            }
        }
        return newFixedLengthResponse(Response.Status.OK, "text/plain", "ok")
    }

    // ── Info JSON ─────────────────────────────────────────────────────────────
    private fun serveInfo(): Response {
        val hasAudio = audioCapture?.isCapturing == true
        val json = JSONObject().apply {
            put("ip",                currentIp)
            put("port",              PORT)
            put("mjpeg_url",         "http://$currentIp:$PORT/mjpegfeed")
            put("audio_url",         if (hasAudio) "http://$currentIp:$PORT/audio" else JSONObject.NULL)
            put("audio_codec",       if (hasAudio) "aac-lc/adts" else JSONObject.NULL)
            put("audio_sample_rate", if (hasAudio) AudioCapture.SAMPLE_RATE else JSONObject.NULL)
            put("ndi_enabled",       ndiActive)
            put("ndi_name",          if (ndiActive) ndiSourceName else JSONObject.NULL)
            put("resolution",        currentResolution)
            put("app",               "DroidCam Clone")
        }
        return newFixedLengthResponse(Response.Status.OK, "application/json", json.toString(2))
            .also { it.addHeader("Access-Control-Allow-Origin", "*") }
    }

    // ── Home page ─────────────────────────────────────────────────────────────
    private fun serveHome(): Response {
        val mjpegUrl = "http://$currentIp:$PORT/mjpegfeed"
        val audioUrl = "http://$currentIp:$PORT/audio"
        val hasAudio = audioCapture?.isCapturing == true

        val audioSection = if (hasAudio) """
            <div class="card">
            <h2>Audio Stream <span class="badge">AAC</span></h2>
            <p>Live microphone — encoded AAC-LC/ADTS, 44100 Hz mono.</p>
            <p style="margin-top:8px">URL: <span class="pill">$audioUrl</span></p>
            <h3>Play with VLC</h3>
            <p class="pill">vlc $audioUrl</p>
            <h3>Play with ffplay</h3>
            <p class="pill" style="margin-top:4px">ffplay -f aac $audioUrl</p>
            </div>
        """ else """
            <div class="card">
            <h2>Audio Stream</h2>
            <p>Mic not active. Tap the <strong>Mic</strong> button in the app to enable audio streaming.</p>
            </div>
        """

        val camControls = """
            <div class="card">
            <h2>Camera Controls</h2>
            <h3>Torch</h3>
            <a class="btn" href="#" onclick="$.get('/cam/1/torch');return false;">ON</a>
            <a class="btn" href="#" onclick="$.get('/cam/1/unset_torch');return false;">OFF</a>
            <h3 style="margin-top:12px">Exposure Lock</h3>
            <a class="btn" href="#" onclick="$.get('/cam/1/set_exposure_lock');return false;">Lock</a>
            <a class="btn" href="#" onclick="$.get('/cam/1/unset_exposure_lock');return false;">Unlock</a>
            <h3 style="margin-top:12px">White Balance</h3>
            ${listOf("auto","daylight","cloudy-daylight","fluorescent","incandescent","twilight","shade").joinToString(" ") {
                """<a class="btn" href="#" onclick="$.get('/cam/1/set_wb/$it');return false;">$it</a>"""
            }}
            </div>
        """

        val ndiSection = if (ndiActive) """
            <div class="card">
            <h2>NDI Source <span class="badge ndi">NDI</span></h2>
            <p>Source name: <span class="pill">$ndiSourceName</span></p>
            </div>
        """ else ""

        val html = """
            <!DOCTYPE html><html lang="en"><head>
            <meta charset="UTF-8"><meta name="viewport" content="width=device-width,initial-scale=1">
            <title>DroidCam Clone</title>$CSS
            <script src="/js/jquery-3.4.1.min.js"></script></head><body>
            <header><h1>📷 DroidCam Clone</h1>
            <p style="color:#90CAF9;font-size:.85rem">Port $PORT · $currentResolution · $currentIp</p></header>
            $NAV
            <div class="card">
            <h2>Live Preview <span class="badge">MJPEG</span></h2>
            <img class="preview" src="/mjpegfeed" alt="Live Stream">
            <p>OBS Media Source URL: <span class="pill">$mjpegUrl</span></p>
            </div>
            <div class="card">
            <h2>OBS — MJPEG via Media Source</h2>
            <ul>
            <li>Sources → Add → <strong>Media Source</strong></li>
            <li>Uncheck <strong>Local File</strong></li>
            <li>Input: <span class="pill">$mjpegUrl</span></li>
            <li>Input Format: <span class="pill">mjpeg</span></li>
            </ul>
            </div>
            $audioSection
            $camControls
            $ndiSection
            <p style="margin-top:24px;color:#555;font-size:.8rem">DroidCam Clone — personal use only</p>
            </body></html>
        """.trimIndent()
        return newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", html)
    }

    // ── Downloads page ────────────────────────────────────────────────────────
    private fun serveDownloads(): Response {
        val html = """
            <!DOCTYPE html><html lang="en"><head>
            <meta charset="UTF-8"><meta name="viewport" content="width=device-width,initial-scale=1">
            <title>Downloads — DroidCam Clone</title>$CSS</head><body>
            <header><h1>📦 Downloads &amp; Setup</h1></header>
            $NAV
            <div class="card">
            <h2>1. OBS Studio NDI Plugin</h2>
            <p><a href="https://github.com/obs-project/obs-ndi/releases" target="_blank">→ github.com/obs-project/obs-ndi/releases</a></p>
            </div>
            <div class="card">
            <h2>2. NDI Tools (Free)</h2>
            <p><a href="https://ndi.video/tools/" target="_blank">→ ndi.video/tools/</a></p>
            </div>
            <div class="card">
            <h2>3. OBS Studio</h2>
            <p><a href="https://obsproject.com/" target="_blank">→ obsproject.com/</a></p>
            </div>
            <div class="card">
            <h2>4. VLC — test streams</h2>
            <p>Video: <span class="pill">vlc http://$currentIp:$PORT/mjpegfeed</span></p>
            <p style="margin-top:6px">Audio: <span class="pill">vlc http://$currentIp:$PORT/audio</span></p>
            <p><a href="https://www.videolan.org/vlc/" target="_blank">→ videolan.org/vlc/</a></p>
            </div>
            </body></html>
        """.trimIndent()
        return newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", html)
    }

    override fun stop() {
        streaming = false
        super.stop()
    }
}
