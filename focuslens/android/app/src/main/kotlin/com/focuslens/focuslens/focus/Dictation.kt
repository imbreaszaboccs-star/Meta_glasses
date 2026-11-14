package com.focuslens.focuslens.focus

import java.io.ByteArrayOutputStream
import kotlin.math.sqrt

/**
 * Energy-based end-of-speech detector over PCM16 chunks. The threshold adapts to the first 300 ms.
 * Ends after 1.2 s of silence that follows speech, after 8 s, or after 4 s without any speech.
 * Feed it from the mic thread; [result] is the PCM (or null = nothing heard) once [feed] returned true.
 */
class Dictation {
    private val pcm = ByteArrayOutputStream()
    private var ms = 0
    private var noise = 0.0
    private var threshold = Double.MAX_VALUE
    private var loudRun = 0
    private var quietMs = 0
    private var speech = false
    private var finished = false
    var result: ByteArray? = null
        private set

    fun feed(b: ByteArray, n: Int): Boolean {
        if (finished) return false
        pcm.write(b, 0, n)
        val dt = n / 32 // 16 kHz × 2 bytes = 32 bytes per ms
        val r = rms(b, n)
        if (ms < CALIBRATE_MS) {
            noise = maxOf(noise, r)
            ms += dt
            if (ms >= CALIBRATE_MS) threshold = (noise * 3).coerceIn(300.0, 3000.0)
            return false
        }
        ms += dt
        if (r > threshold) { loudRun++; quietMs = 0; if (loudRun >= 2) speech = true } else { loudRun = 0; if (speech) quietMs += dt }
        val end = (speech && quietMs >= SILENCE_MS) || ms >= MAX_MS || (!speech && ms >= NO_SPEECH_MS)
        if (end) { finished = true; result = if (speech) pcm.toByteArray() else null }
        return end
    }

    private fun rms(b: ByteArray, n: Int): Double {
        if (n < 2) return 0.0
        var sum = 0.0
        var i = 0
        while (i + 1 < n) {
            val s = (b[i].toInt() and 0xFF) or (b[i + 1].toInt() shl 8) // little-endian, sign from the high byte
            sum += s.toDouble() * s
            i += 2
        }
        return sqrt(sum / (n / 2))
    }

    private companion object {
        const val CALIBRATE_MS = 300
        const val SILENCE_MS = 1_200
        const val MAX_MS = 8_000
        const val NO_SPEECH_MS = 4_000
    }
}
