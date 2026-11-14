package com.focuslens.focuslens.focus

enum class NudgeKind { KEY, STUCK, LAPSE, DOZING }

/**
 * Decides WHEN to nudge; FocusEngine decides whether the HUD is free and shows it. Pure, clock injected.
 *  - KEY: a lecture keyword was heard while the head was up (not writing) and it stays up for [KEY_HEAD_UP_MS].
 * STUCK / DOZING come from AttentionMachine via NudgePolicy, which also owns the budget; this class is the KEY part only.
 * At most one nudge per [COOLDOWN_MS]. Call [tick] once a second, only while the HUD is free and NudgePolicy allows it.
 */
class Nudges(private val now: () -> Long) {
    var enabled = true
    private var head: Boolean? = null // true = down, null = no signal
    private var keywordAt = NONE
    private var lastShownAt = NONE

    fun onHead(down: Boolean?) {
        head = down
        if (down != false) keywordAt = NONE // writing (or unknown) cancels a pending key moment
    }

    /** Only counts when the head is known to be up. */
    fun onKeyword() { if (head == false) keywordAt = now() }

    fun onMicPaused() { keywordAt = NONE }

    fun reset() { keywordAt = NONE; lastShownAt = NONE }

    fun tick(): NudgeKind? {
        val t = now()
        if (!enabled || (lastShownAt != NONE && t - lastShownAt < COOLDOWN_MS)) return null
        if (keywordAt != NONE && t - keywordAt > KEY_TTL_MS) keywordAt = NONE
        if (keywordAt == NONE || head != false || t - keywordAt < KEY_HEAD_UP_MS) return null
        lastShownAt = t
        keywordAt = NONE
        return NudgeKind.KEY
    }

    companion object {
        // ponytail: calibration knobs
        const val KEY_HEAD_UP_MS = 8_000L
        const val KEY_TTL_MS = 30_000L
        const val COOLDOWN_MS = 180_000L
        const val SHOW_MS = 10_000L
        private const val NONE = -1L
    }
}
