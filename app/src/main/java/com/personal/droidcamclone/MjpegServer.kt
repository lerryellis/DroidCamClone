package com.personal.droidcamclone

import fi.iki.elonen.NanoHTTPD
import org.json.JSONObject
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.concurrent.atomic.AtomicReference

class MjpegServer(private val serverIp: String = "0.0.0.0") : NanoHTTPD(PORT) {

    companion object {
        const val PORT = 8080
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
            .pill{display:inline-block;background:#263238;border-radius:4px;padding:3px 8px;font-family:monospace;font-size:.85rem;color:#80DEEA}
            img.preview{width:100%;max-width:640px;border-radius:6px;border:2px solid #333;display:block;margin:12px 0}
            .badge{display:inline-block;background:#4CAF50;color:#fff;border-radius:3px;padding:2px 7px;font-size:.75rem;margin-left:6px;vertical-align:middle}
            .badge.ndi{background:#E91E63}
            nav a{color:#90CAF9;margin-right:16px;font-size:.9rem}
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

    val latestFrame = AtomicReference<ByteArray?>(null)

    // Set by StreamService after NDI sender is started
    var ndiSourceName: String = "DroidCam Clone (Android)"
    var ndiActive: Boolean = false
    var currentResolution: String = "720p"
    var currentIp: String = serverIp

    @Volatile private var streaming = false

    override fun serve(session: IHTTPSession): Response {
        return when (session.uri) {
            "/video" -> serveVideo()
            "/info" -> serveInfo()
            "/downloads" -> serveDownloads()
            else -> serveHome()
        }
    }

    private fun serveHome(): Response {
        val mjpegUrl = "http://$currentIp:$PORT/video"
        val ndiSection = if (ndiActive) """
            <div class="card">
            <h2>NDI Source <span class="badge ndi">NDI</span></h2>
            <p>Source name visible in OBS NDI, vMix, Wirecast:</p>
            <p class="pill" style="margin-top:8px">$ndiSourceName</p>
            <h3>OBS Setup</h3>
            <ul>
            <li>Install <a href="https://github.com/obs-project/obs-ndi/releases" target="_blank">OBS NDI Plugin</a></li>
            <li>Sources → Add → <strong>NDI Source</strong></li>
            <li>Select <strong>$ndiSourceName</strong> from the dropdown</li>
            </ul>
            </div>
        """ else """
            <div class="card">
            <h2>NDI Source</h2>
            <p>NDI is not active. See <a href="/downloads">Downloads</a> to set up the NDI SDK and OBS plugin.</p>
            </div>
        """

        val html = """
            <!DOCTYPE html><html lang="en"><head>
            <meta charset="UTF-8"><meta name="viewport" content="width=device-width,initial-scale=1">
            <title>DroidCam Clone</title>$CSS</head><body>
            <header><h1>📷 DroidCam Clone</h1><p style="color:#90CAF9;font-size:.85rem">Wireless webcam from your Android phone</p></header>
            $NAV
            <div class="card">
            <h2>Live Preview <span class="badge">MJPEG</span></h2>
            <img class="preview" src="/video" alt="Live Stream">
            <p>Stream URL: <a href="$mjpegUrl" target="_blank" class="pill">$mjpegUrl</a></p>
            </div>
            <div class="card">
            <h2>OBS — MJPEG via Media Source</h2>
            <ul>
            <li>Sources → Add → <strong>Media Source</strong></li>
            <li>Uncheck <strong>Local File</strong></li>
            <li>Input: <span class="pill">$mjpegUrl</span></li>
            <li>Input Format: <span class="pill">mjpeg</span></li>
            <li>Uncheck Loop &amp; Reconnect → OK</li>
            </ul>
            </div>
            $ndiSection
            <p style="margin-top:24px;color:#555;font-size:.8rem">DroidCam Clone — personal use only</p>
            </body></html>
        """.trimIndent()
        return newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", html)
    }

    private fun serveDownloads(): Response {
        val html = """
            <!DOCTYPE html><html lang="en"><head>
            <meta charset="UTF-8"><meta name="viewport" content="width=device-width,initial-scale=1">
            <title>Downloads — DroidCam Clone</title>$CSS</head><body>
            <header><h1>📦 Downloads &amp; Setup</h1></header>
            $NAV

            <div class="card">
            <h2>1. OBS Studio NDI Plugin</h2>
            <p>Required to receive NDI sources in OBS.</p>
            <p style="margin-top:8px"><a href="https://github.com/obs-project/obs-ndi/releases" target="_blank">→ github.com/obs-project/obs-ndi/releases</a></p>
            <ul style="margin-top:8px">
            <li>Download the installer matching your OBS version</li>
            <li>Install and restart OBS</li>
            <li>Sources → Add → <strong>NDI Source</strong></li>
            </ul>
            </div>

            <div class="card">
            <h2>2. NDI Tools (Free)</h2>
            <p>Includes NDI Studio Monitor, NDI Virtual Input (use phone as webcam in Zoom/Teams), and test tools.</p>
            <p style="margin-top:8px"><a href="https://ndi.video/tools/" target="_blank">→ ndi.video/tools/</a></p>
            <ul style="margin-top:8px">
            <li><strong>NDI Studio Monitor</strong> — verify your NDI source is on the network</li>
            <li><strong>NDI Virtual Input</strong> — Windows/Mac: makes NDI appear as a webcam device</li>
            </ul>
            </div>

            <div class="card">
            <h2>3. NDI SDK for Android (Developer)</h2>
            <p>Required to enable NDI output from this app. Free account required.</p>
            <p style="margin-top:8px"><a href="https://ndi.video/for-developers/ndi-sdk/" target="_blank">→ ndi.video/for-developers/ndi-sdk/</a></p>
            <ul style="margin-top:8px">
            <li>Download → Android</li>
            <li>Place <span class="pill">libndi.so</span> files in <span class="pill">app/libs/jni/</span></li>
            <li>Set <span class="pill">NDI_ENABLED=true</span> in <span class="pill">app/build.gradle</span></li>
            <li>See <span class="pill">NDI_SETUP.md</span> in the project root for full steps</li>
            </ul>
            </div>

            <div class="card">
            <h2>4. VLC — MJPEG Testing</h2>
            <p>Quick way to verify the MJPEG stream on any OS.</p>
            <p style="margin-top:8px"><a href="https://www.videolan.org/vlc/" target="_blank">→ videolan.org/vlc/</a></p>
            <ul style="margin-top:8px">
            <li>Media → Open Network Stream</li>
            <li>Paste: <span class="pill">http://$currentIp:$PORT/video</span></li>
            </ul>
            </div>

            <div class="card">
            <h2>5. OBS Studio</h2>
            <p><a href="https://obsproject.com/" target="_blank">→ obsproject.com/</a> — free, open-source streaming/recording software</p>
            </div>

            </body></html>
        """.trimIndent()
        return newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", html)
    }

    private fun serveInfo(): Response {
        val json = JSONObject().apply {
            put("ip", currentIp)
            put("port", PORT)
            put("mjpeg_url", "http://$currentIp:$PORT/video")
            put("ndi_enabled", ndiActive)
            put("ndi_name", if (ndiActive) ndiSourceName else JSONObject.NULL)
            put("resolution", currentResolution)
            put("fps_target", 15)
            put("app", "DroidCam Clone")
        }
        val response = newFixedLengthResponse(Response.Status.OK, "application/json", json.toString(2))
        response.addHeader("Access-Control-Allow-Origin", "*")
        return response
    }

    private fun serveVideo(): Response {
        val pipedOut = PipedOutputStream()
        val pipedIn = PipedInputStream(pipedOut, 1024 * 1024)
        streaming = true

        Thread {
            try {
                while (streaming) {
                    val frame = latestFrame.get()
                    if (frame != null && frame.isNotEmpty()) {
                        val header = "--$BOUNDARY\r\nContent-Type: image/jpeg\r\nContent-Length: ${frame.size}\r\n\r\n"
                        pipedOut.write(header.toByteArray())
                        pipedOut.write(frame)
                        pipedOut.write("\r\n".toByteArray())
                        pipedOut.flush()
                    }
                    Thread.sleep(66) // ~15fps
                }
            } catch (_: Exception) {
            } finally {
                try { pipedOut.close() } catch (_: Exception) {}
            }
        }.also { it.isDaemon = true }.start()

        val response = newChunkedResponse(
            Response.Status.OK,
            "multipart/x-mixed-replace; boundary=$BOUNDARY",
            pipedIn
        )
        response.addHeader("Cache-Control", "no-cache, no-store, must-revalidate")
        response.addHeader("Pragma", "no-cache")
        response.addHeader("Expires", "0")
        return response
    }

    override fun stop() {
        streaming = false
        super.stop()
    }
}
