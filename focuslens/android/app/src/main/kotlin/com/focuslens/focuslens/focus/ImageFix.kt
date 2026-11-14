package com.focuslens.focuslens.focus

import kotlin.math.pow

/** Pure pixel maths for very bright photos (strong light, glare). Dark or normal images are left alone. */
object ImageFix {
    class Stats(val mean: Double, val clipped: Double, val p2: Int, val p995: Int) {
        val bright: Boolean get() = mean > 165 || clipped > 0.06
        override fun toString() = "mean=%.0f clipped=%.1f%% p2=%d p99.5=%d".format(mean, clipped * 100, p2, p995)
    }

    private fun luma(c: Int) = ((c shr 16 and 255) * 299 + (c shr 8 and 255) * 587 + (c and 255) * 114) / 1000

    /** Luma stats on a ~50k-pixel subsample of ARGB [px]. */
    fun stats(px: IntArray): Stats {
        val step = maxOf(1, px.size / 50_000)
        val hist = IntArray(256)
        var n = 0
        var sum = 0L
        var i = 0
        while (i < px.size) { val l = luma(px[i]); hist[l]++; sum += l; n++; i += step }
        if (n == 0) return Stats(0.0, 0.0, 0, 255)
        fun pct(p: Double): Int {
            var acc = 0
            for (v in 0..255) { acc += hist[v]; if (acc >= p * n) return v }
            return 255
        }
        var over = 0
        for (v in 251..255) over += hist[v]
        return Stats(sum.toDouble() / n, over.toDouble() / n, pct(0.02), pct(0.995))
    }

    /** If [px] is bright: stretch [p2, p99.5] to [0, 245] per channel, then gamma 1.5. In place. Returns the stats it saw, or null if untouched. */
    fun correct(px: IntArray): Stats? {
        val s = stats(px)
        if (!s.bright) return null
        val lo = minOf(s.p2, 223)
        val hi = maxOf(s.p995, lo + 32)
        val lut = IntArray(256) { v ->
            val stretched = ((v - lo).toDouble() / (hi - lo)).coerceIn(0.0, 1.0) * 245
            (255 * (stretched / 255).pow(1.5)).toInt().coerceIn(0, 255)
        }
        for (i in px.indices) {
            val c = px[i]
            px[i] = (c and 0xFF000000.toInt()) or (lut[c shr 16 and 255] shl 16) or (lut[c shr 8 and 255] shl 8) or lut[c and 255]
        }
        return s
    }
}
