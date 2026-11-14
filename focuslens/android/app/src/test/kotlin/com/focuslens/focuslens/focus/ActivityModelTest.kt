package com.focuslens.focuslens.focus

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ActivityModelTest {
    private fun json(features: List<String> = Features.NAMES): String {
        val n = Features.NAMES.size
        // W1: 16 x 2, only feature 0 (pitch_rel_mean) feeds h0 = x and h1 = -x
        val w1 = (0 until n).joinToString(",", "[", "]") { if (it == 0) "[1,-1]" else "[0,0]" }
        return """{"features":${features.joinToString(",", "[", "]") { "\"$it\"" }},
            "mean":${List(n) { 0 }}, "std":${List(n) { 1 }},
            "layers":[{"W":$w1,"b":[0,0]},{"W":[[2,-2],[-2,2]],"b":[0,0]}],
            "classes":["board","notes"]}"""
    }

    @Test fun mlpForwardPassAndClassMapping() {
        val m = MlpClassifier(json())
        val up = m.probs(FloatArray(Features.NAMES.size).also { it[0] = 1f })
        val down = m.probs(FloatArray(Features.NAMES.size).also { it[0] = -1f })
        assertEquals(Act.entries.size, up.size)
        assertEquals(1f, up.sum(), 1e-5f)
        assertTrue(up[Act.BOARD.ordinal] > 0.95f)
        assertTrue(down[Act.NOTES.ordinal] > 0.95f)
        assertEquals(0f, up[Act.NOD.ordinal], 0f)
    }

    @Test fun mlpRejectsOtherFeatureNames() {
        try { MlpClassifier(json(Features.NAMES.reversed())); fail() } catch (e: IllegalArgumentException) { /* expected */ }
        assertTrue(Classifiers.create(json(Features.NAMES.reversed())) is HeuristicClassifier)
        assertTrue(Classifiers.create(null) is HeuristicClassifier)
        assertTrue(Classifiers.create(json()) is MlpClassifier)
    }

    @Test fun calibrateFindsTwoPostures() {
        val p = FloatArray(200) { if (it % 2 == 0) -45f + (it % 7) * 0.3f else -5f - (it % 5) * 0.3f }
        val c = calibrate(p)
        assertEquals(-5f, c.board, 3f)
        assertEquals(-45f, c.notes, 3f)
        assertEquals(0f, Features.rel(c.notes, c), 1e-4f)
        assertEquals(1f, Features.rel(c.board, c), 1e-4f)
    }

    @Test fun calibrateSinglePostureAdds25() {
        val c = calibrate(FloatArray(100) { -30f + (it % 3) * 0.5f })
        assertEquals(25f, c.board - c.notes, 1e-3f)
        assertEquals(-29.5f, c.notes, 1f)
    }

    @Test fun uncalibratedMapping() {
        assertEquals(0f, Features.rel(-60f, null), 1e-6f)
        assertEquals(1f, Features.rel(0f, null), 1e-6f)
    }
}
