package com.personal.droidcamclone

import android.annotation.SuppressLint
import android.media.*
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Captures microphone audio and encodes it as AAC-LC in ADTS frames.
 * Listeners receive complete ADTS frames ready to stream over HTTP.
 * VLC: vlc http://IP:4747/audio
 * ffplay: ffplay -f aac http://IP:4747/audio
 */
class AudioCapture {

    companion object {
        const val SAMPLE_RATE = 44100
        private const val BIT_RATE   = 64_000  // 64kbps — good voice quality
        private const val MIME       = MediaFormat.MIMETYPE_AUDIO_AAC
        // Sampling frequency index for 44100 Hz = 4
        private const val FREQ_IDX   = 4
    }

    @Volatile var isCapturing = false

    private var audioRecord: AudioRecord? = null
    private var codec: MediaCodec? = null
    private var encodeThread: Thread? = null
    private val listeners = CopyOnWriteArrayList<(ByteArray) -> Unit>()

    fun addListener(l: (ByteArray) -> Unit) = listeners.add(l)
    fun removeListener(l: (ByteArray) -> Unit) = listeners.remove(l)

    @SuppressLint("MissingPermission")
    fun start() {
        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )

        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.VOICE_COMMUNICATION,  // echo-cancelled mic
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT, minBuf * 4
        )

        val format = MediaFormat.createAudioFormat(MIME, SAMPLE_RATE, 1).apply {
            setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE)
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, minBuf)
        }

        codec = MediaCodec.createEncoderByType(MIME).apply {
            configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            start()
        }
        audioRecord?.startRecording()
        isCapturing = true

        encodeThread = Thread {
            val pcm = ByteArray(minBuf)
            val info = MediaCodec.BufferInfo()
            val c = codec ?: return@Thread

            while (isCapturing) {
                // Feed PCM into encoder
                val inIdx = c.dequeueInputBuffer(10_000L)
                if (inIdx >= 0) {
                    val read = audioRecord?.read(pcm, 0, pcm.size) ?: -1
                    if (read > 0) {
                        c.getInputBuffer(inIdx)?.run { clear(); put(pcm, 0, read) }
                        c.queueInputBuffer(inIdx, 0, read, System.nanoTime() / 1000L, 0)
                    } else {
                        c.queueInputBuffer(inIdx, 0, 0, 0, 0)
                    }
                }

                // Drain encoded ADTS frames
                var outIdx = c.dequeueOutputBuffer(info, 0)
                while (outIdx >= 0) {
                    val codecConfig = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                    if (info.size > 0 && !codecConfig) {
                        val outBuf = c.getOutputBuffer(outIdx)!!
                        val frame = ByteArray(7 + info.size)
                        adtsHeader(info.size, frame)
                        outBuf.get(frame, 7, info.size)
                        listeners.forEach { it(frame) }
                    }
                    c.releaseOutputBuffer(outIdx, false)
                    outIdx = c.dequeueOutputBuffer(info, 0)
                }
            }
        }.also { it.isDaemon = true; it.start() }
    }

    fun stop() {
        isCapturing = false
        encodeThread?.join(500)
        try { audioRecord?.stop(); audioRecord?.release() } catch (_: Exception) {}
        try { codec?.stop(); codec?.release() } catch (_: Exception) {}
        audioRecord = null
        codec = null
        listeners.clear()
    }

    // 7-byte ADTS header: AAC-LC, 44100 Hz, mono
    private fun adtsHeader(payloadSize: Int, out: ByteArray) {
        val len = payloadSize + 7
        out[0] = 0xFF.toByte()
        out[1] = 0xF1.toByte()                                              // MPEG-4, no CRC
        out[2] = ((1 shl 6) or (FREQ_IDX shl 2) or 0).toByte()            // LC profile, 44100, mono MSB
        out[3] = ((1 shl 6) or (len shr 11 and 0x03)).toByte()             // mono LSBs, frame length[12:11]
        out[4] = (len shr 3 and 0xFF).toByte()                             // frame length[10:3]
        out[5] = ((len and 0x07 shl 5) or 0x1F).toByte()                  // frame length[2:0], VBR fullness hi
        out[6] = 0xFC.toByte()                                              // VBR fullness lo, 1 AAC frame
    }
}
