package com.focuslens.focuslens.focus

/**
 * Decides WHEN to disturb, from the attention state (AttentionMachine) and the KEY nudge (Nudges). Pure, clock injected.
 * Call [tick] once a second. Levels: 0 nothing, 1 quiet attention dot (STUCK started), 2 a Nudge screen (returned for one tick).
 *  - STUCK: L1 when it starts, L2 if still STUCK [ESCALATE_MS] later. In a lecture STUCK and DISTRACTED are one LAPSE episode
 *    (zoning out) with the same flow. DOZING: straight to L2.
 *  - Budget for L2: one per [L2_GAP_MS], [L2_PER_HOUR] per hour. Quiet: first [QUIET_START_MS], [QUIET_USER_MS] after pinch/hint, whole AWAY.
 *  - Feedback: a dismissal stretches the machine's STUCK time and that kind's own escalation time by [STRETCH]; [OFF_AFTER] dismissals in a row switch that kind off until [onSessionStart].
 */
class NudgePolicy(private val now: () -> Long, private val machine: AttentionMachine) {
    class Out(val level: Int, val kind: NudgeKind?)

    private var startAt = now()
    private var userAt = NONE
    private var stuckSince = NONE
    private var l2Done = false // the current STUCK / DOZING episode already got its L2
    private var prevKind: NudgeKind? = null
    private val escalate = LongArray(NudgeKind.entries.size) { ESCALATE_MS }
    private val l2 = ArrayList<Long>()
    private val consecutive = IntArray(NudgeKind.entries.size)
    private val off = BooleanArray(NudgeKind.entries.size)

    fun onSessionStart() {
        startAt = now(); userAt = NONE; stuckSince = NONE; l2Done = false; prevKind = null
        l2.clear(); consecutive.fill(0); off.fill(false); escalate.fill(ESCALATE_MS)
    }

    /** Pinch, hint shown or nudge answered: the user is here. Also resets the machine's idle clock. */
    fun onUser() { userAt = now(); machine.onPinch() }

    fun onAccepted(k: NudgeKind) { consecutive[k.ordinal] = 0 }

    fun onDismissed(k: NudgeKind) {
        if (++consecutive[k.ordinal] >= OFF_AFTER) off[k.ordinal] = true
        machine.stuckAfterMs = (machine.stuckAfterMs * STRETCH).toLong()
        escalate[k.ordinal] = (escalate[k.ordinal] * STRETCH).toLong()
        onUser()
    }

    fun isOff(k: NudgeKind) = off[k.ordinal]

    /** [free]: the HUD can show a Nudge now. [lecture]: a lecture is running (DISTRACTED / STUCK = LAPSE). [key] is only called when a KEY nudge would be allowed. */
    fun tick(state: AttState, free: Boolean, lecture: Boolean, key: () -> NudgeKind?): Out {
        val t = now()
        val episode = when {
            // a lecture lapse only while the lecturer talks; staring / looking around in a break is fine
            lecture && (state == AttState.STUCK || state == AttState.DISTRACTED) -> if (machine.speaking()) NudgeKind.LAPSE else null
            state == AttState.STUCK -> NudgeKind.STUCK
            state == AttState.DOZING -> NudgeKind.DOZING
            else -> null
        }
        if (episode != prevKind) { prevKind = episode; stuckSince = NONE; l2Done = false }
        val quiet = t - startAt < QUIET_START_MS || (userAt != NONE && t - userAt < QUIET_USER_MS) || state == AttState.AWAY
        if (quiet) return Out(0, null)
        val canL2 = free && budget(t)
        fun fire(k: NudgeKind): Out { l2 += t; if (k != NudgeKind.KEY) l2Done = true; return Out(2, k) }

        var level = 0
        when {
            (episode == NudgeKind.STUCK || episode == NudgeKind.LAPSE) && !off[episode.ordinal] -> {
                if (stuckSince == NONE) stuckSince = t
                if (!l2Done && canL2 && t - stuckSince >= escalate[episode.ordinal]) return fire(episode)
                level = 1
            }
            episode == NudgeKind.DOZING && !off[NudgeKind.DOZING.ordinal] -> if (!l2Done && canL2) return fire(NudgeKind.DOZING)
        }
        if (canL2 && !off[NudgeKind.KEY.ordinal]) key()?.let { return fire(it) }
        return Out(level, if (level == 1) episode else null)
    }

    private fun budget(t: Long): Boolean {
        l2.removeAll { t - it >= HOUR_MS }
        return l2.none { t - it < L2_GAP_MS } && l2.size < L2_PER_HOUR
    }

    companion object {
        // ponytail: calibration knobs
        const val ESCALATE_MS = 120_000L
        const val L2_GAP_MS = 600_000L
        const val L2_PER_HOUR = 4
        const val QUIET_START_MS = 300_000L
        const val QUIET_USER_MS = 180_000L
        const val STRETCH = 1.2
        const val OFF_AFTER = 3
        private const val HOUR_MS = 3_600_000L
        private const val NONE = -1L
    }
}
