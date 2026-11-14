package com.focuslens.focuslens.focus

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log

/**
 * Phone microphone (or, via [startExternal]/[feed], the glasses microphone) → [PcmRing]. Audio lives only in RAM and is overwritten continuously.
 * Nothing is written to disk until the user asks for help.
 */
class MicRing(seconds: Int) {
    val ring = PcmRing(seconds * BYTES_PER_SECOND)
    /** Share of the last 4 s that was loud (speech context for the attention engine). */
    val speech = SpeechMeter()

    @Volatile private var running = false

    /** Gets every 100 ms chunk (buffer reused; copy what you keep). Must not block. */
    @Volatile var listener: ((ByteArray, Int) -> Unit)? = null
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
                    if (n > 0) { ring.add(chunk, n); speech.feed(chunk, n); listener?.invoke(chunk, n) } else if (n < 0) { Log.w(TAG, "read error $n"); break }
                }
            } finally {
                runCatching { rec.stop() }
                rec.release()
                running = false
            }
        }, "focuslens-mic").apply { isDaemon = true; start() }
        return true
    }

    /** No recorder of its own: someone else (the glasses stream) pushes audio through [feed]. */
    fun startExternal() { running = true }

    /** PCM16 16 kHz mono from outside; same path as the recorder thread. */
    fun feed(chunk: ByteArray, n: Int) {
        if (!running) return
        ring.add(chunk, n)
        speech.feed(chunk, n)
        listener?.invoke(chunk, n)
    }

    /** Stops recording AND wipes the buffer. */
    fun stop() {
        running = false
        thread?.join(500)
        thread = null
        ring.clear()
        speech.reset()
    }

    fun snapshot(): ByteArray = ring.snapshot()

    companion object {
        const val SAMPLE_RATE = 16_000
        const val BYTES_PER_SECOND = SAMPLE_RATE * 2
        private const val TAG = "FocusLens"
    }
}
