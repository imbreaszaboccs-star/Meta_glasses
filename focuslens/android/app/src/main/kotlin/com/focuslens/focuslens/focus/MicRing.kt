package com.focuslens.focuslens.focus

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log

/**
 * Phone microphone → [PcmRing]. Audio lives only in RAM and is overwritten continuously.
 * Nothing is written to disk until the user asks for help.
 */
class MicRing(seconds: Int) {
    val ring = PcmRing(seconds * BYTES_PER_SECOND)

    @Volatile private var running = false
    private var thread: Thread? = null

    val isListening: Boolean get() = running
    val bufferedSeconds: Int get() = ring.size / BYTES_PER_SECOND

    /** Returns false when the mic could not be opened (no permission, mic busy). */
    @SuppressLint("MissingPermission") // checked by the caller (FocusEngine)
    fun start(): Boolean {
        if (running) return true
        val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val bufSize = maxOf(minBuf, BYTES_PER_SECOND / 5) // ≥ 200 ms
        val rec = try {
            AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufSize)
        } catch (e: Exception) {
            Log.w(TAG, "AudioRecord: $e"); return false
        }
        if (rec.state != AudioRecord.STATE_INITIALIZED) { rec.release(); return false }
        try { rec.startRecording() } catch (e: Exception) { Log.w(TAG, "startRecording: $e"); rec.release(); return false }
        running = true
        thread = Thread({
            val chunk = ByteArray(BYTES_PER_SECOND / 10) // 100 ms
            try {
                while (running) {
                    val n = rec.read(chunk, 0, chunk.size)
                    if (n > 0) ring.add(chunk, n) else if (n < 0) { Log.w(TAG, "read error $n"); break }
                }
            } finally {
                runCatching { rec.stop() }
                rec.release()
                running = false
            }
        }, "focuslens-mic").apply { isDaemon = true; start() }
        return true
    }

    /** Stops recording AND wipes the buffer. */
    fun stop() {
        running = false
        thread?.join(500)
        thread = null
        ring.clear()
    }

    fun snapshot(): ByteArray = ring.snapshot()

    companion object {
        const val SAMPLE_RATE = 16_000
        const val BYTES_PER_SECOND = SAMPLE_RATE * 2
        private const val TAG = "FocusLens"
    }
}
