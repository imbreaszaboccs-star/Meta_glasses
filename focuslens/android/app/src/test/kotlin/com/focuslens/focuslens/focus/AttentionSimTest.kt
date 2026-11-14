package com.focuslens.focuslens.focus

import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Synthetic 30 Hz IMU through the whole chain: ImuPipeline (heuristic) -> AttentionMachine -> NudgePolicy. */
class AttentionSimTest {
    private enum class Kind { WRITE, BOARD, STILL, LOOK, WALK, NOD }

    private class Sim(val mode: String = "homework", seed: Int = 1) {
        var t = 0L
        private var n = 0L
        private var tickSec = -1L
        private var cur = NOTES_P
        private val rnd = Random(seed)
        val machine = AttentionMachine { t }
        val policy = NudgePolicy({ t }, machine)
        private val pipe = ImuPipeline(machine, HeuristicClassifier(), { mode }, { 0.5f }, { t / 1000f })
        var l1At = -1L
        val l2 = ArrayList<Pair<Long, NudgeKind?>>()

        private fun r() = rnd.nextFloat() * 2f - 1f

        private fun sample(k: Kind, tk: Double): ImuSample {
            val ts = t / 1000.0
            val target = if (k == Kind.WRITE || k == Kind.STILL && cur < -25f || k == Kind.NOD) NOTES_P else if (k == Kind.STILL) cur else BOARD_P
            cur += (target - cur).coerceIn(-2f, 2f) // 60 deg/s
            var pitch = cur
            var ay = 9.81f + 0.02f * r(); var gx = 0.01f * r(); var gy = 0.01f * r(); var gz = 0.01f * r()
            when (k) {
                Kind.WRITE -> {
                    pitch += 0.5f * r()
                    ay += (0.15 * (sin(2 * PI * 6.3 * ts) + 0.7 * sin(2 * PI * 8.1 * ts + 1))).toFloat()
                    gx = 0.08f * r(); gz = 0.08f * r(); gy = 0.03f * r()
                }
                Kind.BOARD -> { pitch += 0.8f * r(); gx = 0.07f * r(); gz = 0.07f * r() }
                Kind.STILL -> pitch += 0.1f * r()
                Kind.LOOK -> { pitch += 3f * r(); gy = (1.0 * sin(2 * PI * 0.5 * ts)).toFloat() }
                Kind.WALK -> {
                    pitch += (3 * sin(2 * PI * 2 * ts)).toFloat()
                    val phase = (ts * 2) % 1.0
                    ay += (2 * sin(2 * PI * 2 * ts)).toFloat() + (if (phase < 0.07) 4f else 0f) + 0.3f * r()
                    gx = 0.3f * r(); gy = 0.3f * r(); gz = 0.3f * r()
                }
                Kind.NOD -> if (tk < 1.2) pitch += (if (tk < 0.7) -22 * tk / 0.7 else -22 * (1 - (tk - 0.7) / 0.4)).coerceAtMost(0.0).toFloat()
            }
            return ImuSample(t, 0.02f * r(), ay, 0.02f * r(), gx, gy, gz, pitch, 0f, 0f)
        }

        fun seg(k: Kind, sec: Double) {
            val count = (sec * 30).toInt()
            for (i in 0 until count) {
                t = n * 1000 / 30; n++
                pipe.add(sample(k, i / 30.0))
                if (t / 1000 != tickSec) {
                    tickSec = t / 1000
                    val out = policy.tick(machine.state, true) { null }
                    if (out.level == 1 && l1At < 0) l1At = t
                    if (out.level == 2) l2 += t to out.kind
                }
            }
        }

        fun seg(k: Kind, sec: IntRange) = seg(k, sec.random(rnd).toDouble())

        fun copy(totalSec: Int) { var el = 0; while (el < totalSec) { val a = (5..15).random(rnd); seg(Kind.WRITE, a.toDouble()); val b = (5..15).random(rnd); seg(Kind.BOARD, b.toDouble()); el += a + b } }

        fun normalWork(totalSec: Int) {
            copy(120)
            var el = 120
            while (el < totalSec) {
                when (rnd.nextInt(10)) {
                    in 0..3 -> { copy(60); el += 60 }
                    in 4..6 -> { seg(Kind.WRITE, 20..60); seg(Kind.STILL, 20..100); el += 120 } // think pause
                    7 -> { seg(Kind.WRITE, 20..40); seg(Kind.LOOK, 3..5); el += 45 }
                    else -> { seg(Kind.WRITE, 30..60); el += 60 }
                }
            }
        }
    }

    private companion object {
        const val NOTES_P = -45f
        const val BOARD_P = -5f
    }

    @Test fun thirtyMinutesOfNormalWorkNeverDisturbs() {
        val s = Sim()
        s.normalWork(30 * 60)
        assertEquals(emptyList<Any>(), s.l2)
        assertEquals("no attention dot either", -1L, s.l1At)
    }

    @Test fun motionlessHeadDownGetsDotThenNudge() {
        val s = Sim()
        s.normalWork(6 * 60)
        s.seg(Kind.WRITE, 20.0)
        val t0 = s.t // writing stops here
        s.seg(Kind.STILL, 12.0 * 60)
        val tMs = s.machine.stuckAfterMs
        assertTrue("L1 by T+30s (+5 s window slack): ${(s.l1At - t0) / 1000}", s.l1At in 1..(t0 + tMs + 30_000 + 5_000))
        assertTrue("not before T: ${(s.l1At - t0) / 1000}", s.l1At >= t0 + tMs)
        assertEquals(1, s.l2.size)
        val (l2At, kind) = s.l2[0]
        assertEquals(NudgeKind.STUCK, kind)
        assertTrue("L2 by T+30s+2min (+6 s): ${(l2At - t0) / 1000}", l2At <= t0 + tMs + 30_000 + 120_000 + 6_000)
        assertTrue(l2At - s.l1At >= NudgePolicy.ESCALATE_MS)
    }

    @Test fun twoNodsGiveDozingNudgeWithin10s() {
        val s = Sim()
        s.normalWork(6 * 60)
        s.seg(Kind.WRITE, 10.0)
        s.seg(Kind.STILL, 40.0)
        s.seg(Kind.NOD, 1.2)
        s.seg(Kind.STILL, 25.0)
        val t2 = s.t
        s.seg(Kind.NOD, 1.2)
        s.seg(Kind.STILL, 10.0)
        assertEquals(AttState.DOZING, s.machine.state)
        assertEquals(1, s.l2.size)
        assertEquals(NudgeKind.DOZING, s.l2[0].second)
        assertTrue("within 10 s of the 2nd nod: ${(s.l2[0].first - t2) / 1000}", s.l2[0].first - t2 <= 10_000)
    }

    @Test fun oneNodIsNotDozing() {
        val s = Sim()
        s.normalWork(6 * 60)
        s.seg(Kind.WRITE, 10.0); s.seg(Kind.NOD, 1.2); s.seg(Kind.WRITE, 60.0)
        assertTrue(s.l2.isEmpty())
        assertNotNull(s.machine.state.takeIf { it != AttState.DOZING })
    }

    @Test fun walkingIsAwayWithoutNudges() {
        val s = Sim()
        s.normalWork(6 * 60)
        s.seg(Kind.WALK, 3.0 * 60)
        assertEquals(AttState.AWAY, s.machine.state)
        assertTrue(s.l2.isEmpty())
        assertEquals(-1L, s.l1At)
    }

    @Test fun lookingAroundIsDistractedButQuiet() {
        val s = Sim()
        s.normalWork(6 * 60)
        s.seg(Kind.LOOK, 3.0 * 60)
        assertEquals(AttState.DISTRACTED, s.machine.state)
        assertTrue(s.l2.isEmpty())
    }

    @Test fun dismissalsStretchTAndSwitchTheKindOff() {
        var t = 0L
        val m = AttentionMachine { t }
        val p = NudgePolicy({ t }, m)
        val t0 = m.stuckAfterMs
        p.onDismissed(NudgeKind.STUCK)
        assertEquals((t0 * 1.2).toLong(), m.stuckAfterMs)
        p.onDismissed(NudgeKind.STUCK); assertEquals(false, p.isOff(NudgeKind.STUCK))
        p.onAccepted(NudgeKind.STUCK)
        p.onDismissed(NudgeKind.STUCK); p.onDismissed(NudgeKind.STUCK); assertEquals(false, p.isOff(NudgeKind.STUCK))
        p.onDismissed(NudgeKind.STUCK); assertEquals(true, p.isOff(NudgeKind.STUCK))
        p.onSessionStart(); assertEquals(false, p.isOff(NudgeKind.STUCK))
    }

    @Test fun budgetAndQuietPeriods() {
        var t = 0L
        val m = AttentionMachine { t }
        val p = NudgePolicy({ t }, m)
        val key = { NudgeKind.KEY }
        t = 60_000; assertEquals(0, p.tick(AttState.ENGAGED, true, key).level)            // first 5 min are quiet
        t = 301_000; assertEquals(2, p.tick(AttState.ENGAGED, true, key).level)
        t = 302_000; assertEquals(0, p.tick(AttState.ENGAGED, true, key).level)           // one L2 per 10 min
        t = 1_000_000; p.onUser(); t = 1_100_000
        assertEquals(0, p.tick(AttState.ENGAGED, true, key).level)                        // 3 min after a pinch
        t = 1_200_000; assertEquals(2, p.tick(AttState.ENGAGED, true, key).level)
        t = 1_900_000; assertEquals(2, p.tick(AttState.ENGAGED, true, key).level)
        t = 2_600_000; assertEquals(2, p.tick(AttState.ENGAGED, true, key).level)
        t = 3_300_000; assertEquals(0, p.tick(AttState.ENGAGED, true, key).level)         // 4 per hour used up (301 s is still inside)
        t = 4_000_000; assertEquals(2, p.tick(AttState.ENGAGED, true, key).level)
        t = 4_700_000; assertEquals(0, p.tick(AttState.AWAY, true, key).level)            // whole AWAY quiet
    }

    @Test fun autoModeLectureListeningOnBoardNeverTriggersStuck() {
        val s = Sim(mode = "auto")
        s.speech = 0.6f // lecturer speaking
        s.copy(120)     // calibrate board + notes
        s.seg(Kind.BOARD, 15.0 * 60) // 15 min watching the board in a lecture
        assertEquals(AttState.ENGAGED, s.machine.state)
        assertEquals(-1L, s.l1At)
        assertTrue(s.l2.isEmpty())
    }

    @Test fun autoModeSilentMotionlessTriggersStuck() {
        val s = Sim(mode = "auto")
        s.speech = 0.0f // silent room (homework/desk)
        s.copy(120)
        s.seg(Kind.WRITE, 60.0)
        s.seg(Kind.STILL, 10.0 * 60) // frozen staring at notes
        assertEquals(AttState.STUCK, s.machine.state)
        assertTrue(s.l1At > 0)
    }

    @Test fun repeatedNodsEveryFewSecondsTriggerDozing() {
        var t = 0L
        val m = AttentionMachine { t }
        val f = FloatArray(Features.NAMES.size)
        val nodProbs = FloatArray(Act.entries.size).also { it[Act.NOD.ordinal] = 0.9f }
        // Nod windows arriving every 3 seconds: 1st at 1s, 2nd at 4s (<5s gap, ignored), 3rd at 7s (>=5s from 1s, counted!)
        t = 1_000L; m.onWindow(nodProbs, f, "auto", 300f)
        assertEquals(AttState.ENGAGED, m.state)
        t = 4_000L; m.onWindow(nodProbs, f, "auto", 300f)
        assertEquals(AttState.ENGAGED, m.state)
        t = 7_000L; m.onWindow(nodProbs, f, "auto", 300f)
        assertEquals(AttState.DOZING, m.state)
    }
}
