package com.focuslens.focuslens.focus

import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** One IMU reading. t = ms (monotonic), acc m/s², gyro rad/s (y = up/yaw axis), angles in degrees (pitch > 0 = head up). */
data class ImuSample(
    val t: Long,
    val ax: Float, val ay: Float, val az: Float,
    val gx: Float, val gy: Float, val gz: Float,
    val pitch: Float, val roll: Float, val yaw: Float,
)

/** The two dominant head postures of a session (pitch degrees). */
class Calib(val board: Float, val notes: Float)

// ponytail: calibration knobs
const val MIN_GAP_DEG = 8f
const val SINGLE_POSTURE_GAP_DEG = 25f
const val SINGLE_POSTURE_SPLIT_DEG = -18f

/** 1-D k-means, k = 2 (board = the higher pitch). Two centers closer than [MIN_GAP_DEG] = one posture (board if looking up, notes if down). */
fun calibrate(pitches: FloatArray): Calib {
    if (pitches.isEmpty()) return Calib(0f, -60f) // same as the uncalibrated mapping
    val s = pitches.sortedArray()
    var lo = s[(s.size * 0.1).toInt()]
    var hi = s[min(s.size - 1, (s.size * 0.9).toInt())]
    repeat(20) {
        var sl = 0.0; var nl = 0; var sh = 0.0; var nh = 0
        for (p in s) if (abs(p - lo) <= abs(p - hi)) { sl += p; nl++ } else { sh += p; nh++ }
        if (nl > 0) lo = (sl / nl).toFloat()
        if (nh > 0) hi = (sh / nh).toFloat()
    }
    if (hi - lo < MIN_GAP_DEG) {
        val m = s.average().toFloat()
        return if (m > SINGLE_POSTURE_SPLIT_DEG) Calib(m, m - SINGLE_POSTURE_GAP_DEG) else Calib(m + SINGLE_POSTURE_GAP_DEG, m)
    }
    return Calib(hi, lo)
}

object Features {
    val NAMES = listOf(
        "pitch_rel_mean", "pitch_std", "roll_mean", "roll_std", "yaw_rate_energy", "gyro_energy",
        "acc_low_energy", "acc_high_energy", "transitions", "hist_low", "hist_mid", "hist_high",
        "nod_score", "step_score", "speech_ratio", "since_pinch_log",
    )
    val IDX: Map<String, Int> = NAMES.withIndex().associate { it.value to it.index }

    // ponytail: calibration knobs
    const val HYST = 0.15f           // transitions: cross 0.5 ± HYST
    const val NOD_FALL_MS = 1_500L
    const val NOD_BACK_MS = 600L
    const val NOD_RETURN = 0.7f
    const val MA_FAST = 6
    const val MA_SLOW = 60
    const val STEP_HZ_LO = 1.5f
    const val STEP_HZ_HI = 2.5f

    fun rel(pitch: Float, c: Calib?): Float = if (c == null) (pitch + 60f) / 60f else (pitch - c.notes) / (c.board - c.notes)

    fun compute(window: List<ImuSample>, calib: Calib?, speechRatio: Float, secsSincePinch: Float): FloatArray {
        val f = FloatArray(NAMES.size)
        val n = window.size
        f[14] = speechRatio
        f[15] = ln(1f + min(secsSincePinch, 3600f))
        if (n < 2) return f
        val pr = FloatArray(n) { rel(window[it].pitch, calib) }
        val pm = pr.average().toFloat()
        f[0] = pm
        f[1] = std(pr, pm)
        val roll = FloatArray(n) { window[it].roll }
        val rm = roll.average().toFloat()
        f[2] = rm; f[3] = std(roll, rm)
        f[4] = window.sumOf { (it.gy * it.gy).toDouble() }.toFloat() / n
        f[5] = window.sumOf { (it.gx * it.gx + it.gy * it.gy + it.gz * it.gz).toDouble() }.toFloat() / n
        val m = FloatArray(n) { val w = window[it]; sqrt(w.ax * w.ax + w.ay * w.ay + w.az * w.az) }
        val fast = movingAvg(m, MA_FAST); val slow = movingAvg(m, MA_SLOW)
        f[6] = variance(FloatArray(n) { fast[it] - slow[it] })
        f[7] = variance(FloatArray(n) { m[it] - fast[it] })
        var high = pr[0] > 0.5f; var tr = 0
        for (p in pr) {
            if (high && p < 0.5f - HYST) { high = false; tr++ } else if (!high && p > 0.5f + HYST) { high = true; tr++ }
        }
        f[8] = tr.toFloat()
        f[9] = pr.count { it < 0.33f }.toFloat() / n
        f[11] = pr.count { it > 0.66f }.toFloat() / n
        f[10] = 1f - f[9] - f[11]
        f[12] = nodScore(window)
        f[13] = stepScore(window, m)
        return f
    }

    /** Largest pitch drop (deg) within [NOD_FALL_MS] that comes back by >= [NOD_RETURN] within [NOD_BACK_MS]. */
    private fun nodScore(w: List<ImuSample>): Float {
        var best = 0f
        for (i in w.indices) {
            var j = i; var minJ = i
            while (j + 1 < w.size && w[j + 1].t - w[i].t <= NOD_FALL_MS) { j++; if (w[j].pitch < w[minJ].pitch) minJ = j }
            val drop = w[i].pitch - w[minJ].pitch
            if (drop <= best) continue
            var k = minJ
            while (k < w.size && w[k].t - w[minJ].t <= NOD_BACK_MS) {
                if (w[k].pitch - w[minJ].pitch >= NOD_RETURN * drop) { best = drop; break }
                k++
            }
        }
        return best
    }

    /** Normalized autocorrelation peak of the acc magnitude at lags of 1.5-2.5 Hz. */
    private fun stepScore(w: List<ImuSample>, m: FloatArray): Float {
        val n = m.size
        val rate = (n - 1) * 1000f / max(1L, w.last().t - w.first().t)
        val mean = m.average().toFloat()
        val d = FloatArray(n) { m[it] - mean }
        val e0 = d.sumOf { (it * it).toDouble() }
        if (e0 < 1e-6) return 0f
        var best = 0.0
        for (lag in max(1, (rate / STEP_HZ_HI).toInt())..(rate / STEP_HZ_LO).toInt()) {
            if (lag >= n) break
            var s = 0.0
            for (i in 0 until n - lag) s += d[i] * d[i + lag]
            best = max(best, s / e0)
        }
        return best.toFloat()
    }

    private fun variance(a: FloatArray): Float { val m = a.average().toFloat(); return a.sumOf { ((it - m) * (it - m)).toDouble() }.toFloat() / a.size }
    private fun std(a: FloatArray, m: Float) = sqrt(a.sumOf { ((it - m) * (it - m)).toDouble() }.toFloat() / a.size)

    /** Trailing average over up to [k] samples (shorter at the start). */
    private fun movingAvg(a: FloatArray, k: Int): FloatArray {
        val out = FloatArray(a.size); var s = 0.0
        for (i in a.indices) { s += a[i]; if (i >= k) s -= a[i - k]; out[i] = (s / min(k, i + 1)).toFloat() }
        return out
    }
}
