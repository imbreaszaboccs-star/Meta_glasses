package com.focuslens.focuslens.focus

enum class NudgeKind { KEY, STUCK }

/**
 * Decides WHEN to nudge; FocusEngine decides whether the HUD is free and shows it. Pure, clock injected.
 *  - KEY: a lecture keyword was heard while the head was up (not writing) and it stays up for [KEY_HEAD_UP_MS].
 *  - STUCK: quest running, no step change / completed check for [STUCK_MS] (and, if the head signal exists, mostly down).
 * At most one nudge per [COOLDOWN_MS]. Call [tick] once a second, only while the HUD is free.
 */
class Nudges(private val now: () -> Long) {
    var enabled = true
    private var head: Boolean? = null // true = down, null = no signal
    private var keywordAt = NONE
    private var lastShownAt = NONE
    private var questAt = NONE        // quest start / last progress
    private var ticks = 0             // ticks since questAt, and how many had the head down
    private var downTicks = 0

    fun onHead(down: Boolean?) {
        head = down
        if (down != false) keywordAt = NONE // writing (or unknown) cancels a pending key moment
    }

    /** Only counts when the head is known to be up. */
    fun onKeyword() { if (head == false) keywordAt = now() }

    fun onQuestStart() = onQuestProgress()
    fun onQuestProgress() { questAt = now(); ticks = 0; downTicks = 0 }
    fun onQuestEnd() { questAt = NONE }

    fun onMicPaused() { keywordAt = NONE }

    fun reset() { keywordAt = NONE; questAt = NONE; lastShownAt = NONE }

    fun tick(): NudgeKind? {
        val t = now()
        if (questAt != NONE) { ticks++; if (head == true) downTicks++ }
        if (!enabled || (lastShownAt != NONE && t - lastShownAt < COOLDOWN_MS)) return null
        if (keywordAt != NONE && t - keywordAt > KEY_TTL_MS) keywordAt = NONE
        val kind = when {
            keywordAt != NONE && head == false && t - keywordAt >= KEY_HEAD_UP_MS -> NudgeKind.KEY
            questAt != NONE && t - questAt >= STUCK_MS && (head == null || downTicks * 2 >= ticks) -> NudgeKind.STUCK
            else -> return null
        }
        lastShownAt = t
        keywordAt = NONE
        questAt = if (questAt != NONE) t else NONE // a declined STUCK waits another full period
        ticks = 0; downTicks = 0
        return kind
    }

    companion object {
        // ponytail: calibration knobs
        const val KEY_HEAD_UP_MS = 8_000L
        const val KEY_TTL_MS = 30_000L
        const val STUCK_MS = 120_000L
        const val COOLDOWN_MS = 180_000L
        const val SHOW_MS = 10_000L
        private const val NONE = -1L
    }
}
