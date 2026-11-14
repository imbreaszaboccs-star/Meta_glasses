package com.focuslens.focuslens.focus

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.exp

enum class Act { BOARD, NOTES, SWITCHING, STILL, LOOKING_AROUND, NOD, WALKING }

interface ActivityClassifier {
    /** Probabilities (size = Act count, sum 1) for one feature vector in [Features.NAMES] order. */
    fun probs(f: FloatArray): FloatArray
}

/** Thresholds shared by the heuristic and by AttentionMachine's "micro-motion" check. */
object Thr {
    // ponytail: calibration knobs
    const val STEP_SCORE = 0.5f        // walking: autocorrelation peak at 1.5-2.5 Hz ...
    const val WALK_ACC_HIGH = 0.1f     // ... and this much impact energy (m/s2)^2
    const val NOD_DEG = 15f
    const val YAW_ENERGY = 0.05f       // looking around: (rad/s)^2
    const val STILL_GYRO = 0.001f      // still: below both (rad/s)^2 / (m/s2)^2
    const val STILL_ACC_HIGH = 0.002f
    const val BOARD_REL = 0.5f

    fun isStill(f: FloatArray) =
        f[Features.IDX.getValue("gyro_energy")] < STILL_GYRO && f[Features.IDX.getValue("acc_high_energy")] < STILL_ACC_HIGH
}

/** Rules on the same features; also weakly labels data for the trained model. */
class HeuristicClassifier : ActivityClassifier {
    private val iStep = Features.IDX.getValue("step_score")
    private val iHigh = Features.IDX.getValue("acc_high_energy")
    private val iNod = Features.IDX.getValue("nod_score")
    private val iYaw = Features.IDX.getValue("yaw_rate_energy")
    private val iTr = Features.IDX.getValue("transitions")
    private val iPitch = Features.IDX.getValue("pitch_rel_mean")

    override fun probs(f: FloatArray): FloatArray {
        val win = when {
            f[iStep] > Thr.STEP_SCORE && f[iHigh] > Thr.WALK_ACC_HIGH -> Act.WALKING
            f[iNod] > Thr.NOD_DEG -> Act.NOD
            f[iYaw] > Thr.YAW_ENERGY -> Act.LOOKING_AROUND
            f[iTr] >= 1f -> Act.SWITCHING
            Thr.isStill(f) -> Act.STILL
            f[iPitch] > Thr.BOARD_REL -> Act.BOARD
            else -> Act.NOTES
        }
        val rest = 0.1f / (Act.entries.size - 1)
        return FloatArray(Act.entries.size) { if (it == win.ordinal) 0.9f else rest }
    }
}

/** Tiny MLP from `{"features","mean","std","layers":[{"W":[[in x out]],"b":[]}],"classes"}`; ReLU hidden, softmax out. */
class MlpClassifier(json: String) : ActivityClassifier {
    private val mean: FloatArray
    private val std: FloatArray
    private val w: List<Array<FloatArray>>
    private val b: List<FloatArray>
    private val classIdx: IntArray

    init {
        val o = JSONObject(json)
        val names = o.getJSONArray("features").let { a -> List(a.length()) { a.getString(it) } }
        require(names == Features.NAMES) { "feature names differ from Features.NAMES" }
        mean = floats(o.getJSONArray("mean")); std = floats(o.getJSONArray("std"))
        val layers = o.getJSONArray("layers")
        w = List(layers.length()) { l -> layers.getJSONObject(l).getJSONArray("W").let { r -> Array(r.length()) { floats(r.getJSONArray(it)) } } }
        b = List(layers.length()) { floats(layers.getJSONObject(it).getJSONArray("b")) }
        classIdx = o.getJSONArray("classes").let { a -> IntArray(a.length()) { Act.valueOf(a.getString(it).uppercase()).ordinal } }
        require(w.last()[0].size == classIdx.size) { "output size != classes" }
    }

    override fun probs(f: FloatArray): FloatArray {
        var x = FloatArray(f.size) { (f[it] - mean[it]) / (if (std[it] == 0f) 1f else std[it]) }
        for (l in w.indices) {
            val y = b[l].copyOf()
            for (i in x.indices) { val xi = x[i]; if (xi != 0f) for (j in y.indices) y[j] += xi * w[l][i][j] }
            if (l < w.size - 1) for (j in y.indices) if (y[j] < 0f) y[j] = 0f
            x = y
        }
        val mx = x.max()
        val e = FloatArray(x.size) { exp(x[it] - mx) }
        val s = e.sum()
        val out = FloatArray(Act.entries.size)
        for (k in classIdx.indices) out[classIdx[k]] = e[k] / s
        return out
    }

    private fun floats(a: JSONArray) = FloatArray(a.length()) { a.getDouble(it).toFloat() }
}

object Classifiers {
    /** [modelJson] = contents of assets/activity_mlp.json, or null = heuristic. A broken model falls back too. */
    fun create(modelJson: String?, onError: (String) -> Unit = {}): ActivityClassifier =
        modelJson?.let { runCatching { MlpClassifier(it) }.onFailure { e -> onError("activity model rejected: ${e.message}") }.getOrNull() }
            ?: HeuristicClassifier()
}
