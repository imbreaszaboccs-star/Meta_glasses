package com.focuslens.focuslens.focus

import kotlin.math.sqrt

/** One analysed window, for logging / the data recorder. */
class ImuWindow(val t: Long, val features: FloatArray, val heuristic: Act, val model: Act, val state: AttState)

/**
 * Samples in, attention out: 4 s window / 2 s hop, calibration from the first [CALIB_MS] (pitches), features → classifier → [machine].
 * Not thread-safe: feed it from ONE thread (the engine uses a single-thread executor). [onWindow] runs on that thread too.
 */
class ImuPipeline(
    private val machine: AttentionMachine,
    private val classifier: ActivityClassifier,
    private val mode: () -> String,
    private val speech: () -> Float,
    private val secsSinceUser: () -> Float,
    private val onWindow: (ImuWindow) -> Unit = {},
) {
    private val buf = ArrayDeque<ImuSample>()
    private val heuristic = HeuristicClassifier()
    private val pitches = ArrayList<Float>()
    private var calib: Calib? = null
    private var t0 = -1L
    private var nextAt = -1L

    fun add(s: ImuSample) {
        if (t0 < 0) { t0 = s.t; nextAt = s.t + WINDOW_MS }
        buf.addLast(s)
        if (calib == null) {
            if (buf.size % 3 == 0) pitches += s.pitch
            if (s.t - t0 >= CALIB_MS) calib = calibrate(pitches.toFloatArray())
        }
        if (s.t < nextAt) return
        val w = buf.filter { it.t > nextAt - WINDOW_MS && it.t <= nextAt }
        nextAt += HOP_MS
        while (buf.isNotEmpty() && buf.first().t <= nextAt - WINDOW_MS - HOP_MS) buf.removeFirst()
        if (w.size < MIN_SAMPLES) return
        val f = Features.compute(w, calib, speech(), secsSinceUser())
        val p = classifier.probs(f)
        val h = if (classifier is HeuristicClassifier) p else heuristic.probs(f)
        machine.onWindow(p, f, mode())
        onWindow(ImuWindow(s.t, f, Act.entries[argmax(h)], Act.entries[argmax(p)], machine.state))
    }

    private fun argmax(a: FloatArray): Int { var m = 0; for (i in a.indices) if (a[i] > a[m]) m = i; return m }

    companion object {
        // ponytail: calibration knobs
        const val WINDOW_MS = 4_000L
        const val HOP_MS = 2_000L
        const val CALIB_MS = 120_000L
        const val MIN_SAMPLES = 20
    }
}

/** Fraction of the last [FRAMES] mic chunks (100 ms each) whose RMS is over [RMS_MIN]. Cheap: one pass over the bytes. */
class SpeechMeter {
    private val loud = BooleanArray(FRAMES)
    private var i = 0
    @Volatile private var count = 0

    @Synchronized
    fun feed(pcm16: ByteArray, n: Int) {
        var sum = 0.0
        val m = n / 2
        if (m == 0) return
        for (k in 0 until m) {
            val v = ((pcm16[2 * k + 1].toInt() shl 8) or (pcm16[2 * k].toInt() and 0xFF)).toShort().toInt()
            sum += v.toDouble() * v
        }
        val old = loud[i]
        loud[i] = sqrt(sum / m) > RMS_MIN
        i = (i + 1) % FRAMES
        count += (if (loud[(i + FRAMES - 1) % FRAMES]) 1 else 0) - (if (old) 1 else 0)
    }

    fun ratio(): Float = count.toFloat() / FRAMES

    /** True when none of the last [frames] 100 ms frames (≤ [FRAMES]) was loud. */
    @Synchronized
    fun quietFor(frames: Int): Boolean = (1..frames).none { loud[(i - it + FRAMES) % FRAMES] }

    @Synchronized
    fun reset() { loud.fill(false); count = 0 }

    companion object {
        // ponytail: calibration knobs
        const val FRAMES = 40       // 4 s
        const val RMS_MIN = 500.0   // of 32768
    }
}
