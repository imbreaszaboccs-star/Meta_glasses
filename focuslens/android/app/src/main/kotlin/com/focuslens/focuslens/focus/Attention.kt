package com.focuslens.focuslens.focus

enum class AttState { ENGAGED, DISTRACTED, STUCK, DOZING, AWAY }

/**
 * Layer 2: turns the activity windows (one every 2 s) into a long-term state with transparent rules. Pure, clock injected.
 *  - AWAY: walking dominates the last [AWAY_SPAN_MS].
 *  - DOZING: >= 2 nods within [DOZE_SPAN_MS], or head dropped below [DROP_REL] and then still for [DROP_STILL_MS].
 *  - STUCK: no "activity" window for [stuckAfterMs] (homework/desk/auto: notes with micro-motion or switching, no pinch;
 *    lecture: not (still or looking around) while the lecturer speaks).
 *  - DISTRACTED: looking around in >= half of the last [DISTRACT_SPAN_MS].
 * A candidate must hold [HOLD_MS] before it becomes the state (not DOZING, and leaving STUCK/DOZING is immediate);
 * STUCK/DOZING also need a mean confidence >= [MIN_CONF] of the deciding windows.
 */
class AttentionMachine(private val now: () -> Long) {
    @Volatile var stuckAfterMs = STUCK_MS
    @Volatile var state = AttState.ENGAGED
        private set

    private class W(val t: Long, val dom: Act, val conf: Float)

    private val ws = ArrayDeque<W>()
    private var lastActive = now()
    private val nods = ArrayList<Long>()
    private var lastNodWin = NONE
    private var dropped = false
    private var stillSince = NONE
    private var pending: AttState? = null
    private var pendingSince = 0L

    private val iRel = Features.IDX.getValue("pitch_rel_mean")
    private val iSpeech = Features.IDX.getValue("speech_ratio")

    @Synchronized
    fun onWindow(p: FloatArray, f: FloatArray, mode: String) {
        val t = now()
        var d = 0
        for (i in p.indices) if (p[i] > p[d]) d = i
        val dom = Act.entries[d]
        val micro = !Thr.isStill(f)
        val speaking = f[iSpeech] > SPEECH_MIN
        val active = dom == Act.WALKING || when (mode) {
            "lecture" -> if (speaking) dom != Act.STILL && dom != Act.LOOKING_AROUND else dom == Act.SWITCHING || (dom == Act.NOTES && micro) || (dom == Act.BOARD && micro)
            "homework" -> dom == Act.SWITCHING || (dom == Act.NOTES && micro)
            "desk" -> dom == Act.SWITCHING || (dom == Act.NOTES && micro) || (dom == Act.BOARD && micro)
            else -> dom == Act.SWITCHING || (dom == Act.NOTES && micro) || (dom == Act.BOARD && (micro || speaking))
        }
        if (active) lastActive = t
        ws.addLast(W(t, dom, p[d]))
        while (ws.first().t < t - KEEP_MS) ws.removeFirst()

        if (dom == Act.NOD) {
            if (lastNodWin == NONE || t - lastNodWin > NOD_GAP_MS) {
                nods += t
                lastNodWin = t
            }
        }
        nods.removeAll { it < t - DOZE_SPAN_MS }
        if (f[iRel] >= DROP_REL) { dropped = false; stillSince = NONE }
        else if (!dropped) dropped = true // the drop itself; stillness has to follow
        else if (dom == Act.STILL) { if (stillSince == NONE) stillSince = t } else stillSince = NONE

        val cand = candidate(t)
        if (cand == state) { pending = null; return }
        if (cand != pending) { pending = cand; pendingSince = t }
        val hold = if (cand == AttState.DOZING || state == AttState.STUCK || state == AttState.DOZING) 0L else HOLD_MS
        if (t - pendingSince >= hold) { state = cand; pending = null }
    }

    /** The user is clearly present and doing something (pinch, hint, nudge answered). */
    @Synchronized
    fun onPinch() {
        lastActive = now()
        nods.clear(); lastNodWin = NONE; dropped = false; stillSince = NONE; pending = null
        if (state == AttState.STUCK || state == AttState.DOZING) state = AttState.ENGAGED
    }

    private fun candidate(t: Long): AttState {
        val away = ws.filter { it.t >= t - AWAY_SPAN_MS }
        if (away.size >= MIN_WINDOWS && away.count { it.dom == Act.WALKING } * 2 >= away.size) return AttState.AWAY

        if (nods.size >= 2 && conf(ws.filter { it.dom == Act.NOD && it.t >= t - DOZE_SPAN_MS }) >= MIN_CONF) return AttState.DOZING
        if (stillSince != NONE && t - stillSince >= DROP_STILL_MS && conf(ws.filter { it.t >= stillSince }) >= MIN_CONF) return AttState.DOZING

        if (t - lastActive >= stuckAfterMs && conf(ws.filter { it.t > lastActive }) >= MIN_CONF) return AttState.STUCK

        val look = ws.filter { it.t >= t - DISTRACT_SPAN_MS }
        if (look.size >= MIN_WINDOWS && look.count { it.dom == Act.LOOKING_AROUND } * 2 >= look.size) return AttState.DISTRACTED
        return AttState.ENGAGED
    }

    private fun conf(l: List<W>) = if (l.isEmpty()) 1f else l.sumOf { it.conf.toDouble() }.toFloat() / l.size

    companion object {
        // ponytail: calibration knobs
        const val STUCK_MS = 270_000L
        const val HOLD_MS = 30_000L
        const val MIN_CONF = 0.8f
        const val SPEECH_MIN = 0.3f
        const val AWAY_SPAN_MS = 30_000L
        const val DOZE_SPAN_MS = 180_000L
        const val DISTRACT_SPAN_MS = 120_000L
        const val DROP_REL = -0.3f
        const val DROP_STILL_MS = 20_000L
        const val NOD_GAP_MS = 5_000L   // NOD windows closer than this are the same nod (windows overlap)
        const val MIN_WINDOWS = 5
        private const val KEEP_MS = 900_000L
        private const val NONE = -1L
    }
}
