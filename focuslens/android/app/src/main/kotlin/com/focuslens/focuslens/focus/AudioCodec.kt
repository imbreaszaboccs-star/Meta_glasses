package com.focuslens.focuslens.focus

import android.media.MediaCodec
import android.media.MediaFormat
import android.media.MediaMuxer
import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Encoded audio ready for upload / the card. */
class EncodedAudio(val bytes: ByteArray, val mime: String, val ext: String)

object AudioCodec {
    private const val TAG = "FocusLens"

    /**
     * PCM16 mono → OGG/Opus (~24 kbps, 180 s ≈ 0.5 MB) using the platform encoder.
     * Falls back to WAV (5.8 MB for 180 s) if the device's encoder or muxer refuses; the backend accepts both.
     */
    fun encode(pcm: ByteArray, sampleRate: Int, tmpDir: File, bitrate: Int = 24_000): EncodedAudio {
        if (!opusBroken) try {
            return EncodedAudio(opus(pcm, sampleRate, tmpDir, bitrate), "audio/ogg", "ogg")
        } catch (e: Exception) {
            opusBroken = true // e.g. motorola edge 50 fusion: the encoder stalls; don't pay the timeout on every hint
            Log.w(TAG, "opus encode failed, WAV from now on: $e")
        }
        return EncodedAudio(wav(pcm, sampleRate), "audio/wav", "wav")
    }

    @Volatile private var opusBroken = false

    fun wav(pcm: ByteArray, sampleRate: Int): ByteArray {
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(36 + pcm.size); put("WAVE".toByteArray())
            put("fmt ".toByteArray()); putInt(16); putShort(1); putShort(1)
            putInt(sampleRate); putInt(sampleRate * 2); putShort(2); putShort(16)
            put("data".toByteArray()); putInt(pcm.size)
        }.array()
        return ByteArrayOutputStream(44 + pcm.size).apply { write(header); write(pcm) }.toByteArray()
    }

    private fun opus(pcm: ByteArray, sampleRate: Int, tmpDir: File, bitrate: Int): ByteArray {
        require(pcm.isNotEmpty()) { "empty pcm" }
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_OPUS, sampleRate, 1).apply {
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16_384)
        }
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_OPUS)
        tmpDir.mkdirs()
        val out = File.createTempFile("snap", ".ogg", tmpDir)
        var muxer: MediaMuxer? = null
        try {
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
            muxer = MediaMuxer(out.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_OGG)
            var track = -1
            var inPos = 0
            var inputDone = false
            val info = MediaCodec.BufferInfo()
            val deadline = System.nanoTime() + 4_000_000_000L // a healthy encoder needs < 1 s for 150 s
            while (true) {
                check(System.nanoTime() < deadline) { "opus encode timeout" }
                if (!inputDone) {
                    val i = codec.dequeueInputBuffer(5_000)
                    if (i >= 0) {
                        val buf = codec.getInputBuffer(i)!!
                        buf.clear()
                        val n = minOf(buf.remaining(), pcm.size - inPos) and 1.inv() // whole samples
                        val ptsUs = inPos / 2 * 1_000_000L / sampleRate
                        if (n <= 0) {
                            codec.queueInputBuffer(i, 0, 0, ptsUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            buf.put(pcm, inPos, n)
                            codec.queueInputBuffer(i, 0, n, ptsUs, 0)
                            inPos += n
                        }
                    }
                }
                val o = codec.dequeueOutputBuffer(info, 5_000)
                when {
                    o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        track = muxer.addTrack(codec.outputFormat)
                        muxer.start()
                    }
                    o >= 0 -> {
                        val buf = codec.getOutputBuffer(o)!!
                        val isConfig = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                        if (!isConfig && info.size > 0 && track >= 0) {
                            buf.position(info.offset).limit(info.offset + info.size)
                            muxer.writeSampleData(track, buf, info)
                        }
                        codec.releaseOutputBuffer(o, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                    }
                }
            }
            check(track >= 0) { "encoder produced no format" }
            muxer.stop()
            return out.readBytes()
        } finally {
            runCatching { muxer?.release() }
            runCatching { codec.stop() }
            codec.release()
            out.delete()
        }
    }
}
