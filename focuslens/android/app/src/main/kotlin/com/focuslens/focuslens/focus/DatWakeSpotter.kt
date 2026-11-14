package com.focuslens.focuslens.focus

/**
 * Wake phrase + lecture keywords from the glasses' on-device speech recognition (mwdat-speech: text only, no audio crosses
 * the link). Same phrases, cooldowns and callbacks as [WakeWord] (Vosk). Feed it every transcription result; partial results
 * repeat the phrase while the sentence grows, so a hit re-arms only after a final result or once the phrase is gone.
 * Pure, clock injected. Hits carry no text.
 */
class DatWakeSpotter(
    private val now: () -> Long,
    private val language: String,
    private val wake: Boolean,
    private val keywords: Boolean,
    private val onWake: () -> Unit,
    private val onKeyword: () -> Unit,
) {
    private class Hit(val phrases: List<String>, val cooldownMs: Long, val action: () -> Unit) {
        var cooldownUntil = 0L
        var armed = true
    }

    private val hits = buildList {
        if (wake) add(Hit(listOf(WakeWord.WAKE_PHRASE), WakeWord.WAKE_COOLDOWN_MS, onWake))
        if (keywords) add(Hit(if (language == "cs") WakeWord.CS_KEYWORDS else WakeWord.EN_KEYWORDS, WakeWord.KEYWORD_COOLDOWN_MS, onKeyword))
    }

    fun onText(text: String, isFinal: Boolean) {
        val t = text.lowercase()
        for (h in hits) {
            val found = h.phrases.any { t.contains(it) }
            if (!found) { h.armed = true; continue }
            if (!h.armed) { if (isFinal) h.armed = true; continue }
            h.armed = isFinal
            val n = now()
            if (n < h.cooldownUntil) continue
            h.cooldownUntil = n + h.cooldownMs
            h.action()
        }
    }
}
