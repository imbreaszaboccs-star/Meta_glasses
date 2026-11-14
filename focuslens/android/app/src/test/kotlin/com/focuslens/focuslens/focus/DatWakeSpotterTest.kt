package com.focuslens.focuslens.focus

import org.junit.Assert.assertEquals
import org.junit.Test

class DatWakeSpotterTest {
    private var t = 0L
    private var wakes = 0
    private var keys = 0
    private fun spotter(lang: String = "en", wake: Boolean = true, kw: Boolean = true) =
        DatWakeSpotter({ t }, lang, wake, kw, { wakes++ }, { keys++ })

    @Test fun wakePhraseOncePerUtteranceDespitePartials() {
        val s = spotter()
        s.onText("hey", false); s.onText("Hey lens", false); t += 4_000
        s.onText("hey lens what is", false) // same utterance, cooldown over: still one hit
        s.onText("hey lens what is this", true)
        assertEquals(1, wakes)
        t += 5_000; s.onText("hey lens", false)
        assertEquals(2, wakes)
    }

    @Test fun keywordsFollowLanguageAndCooldown() {
        val s = spotter("en")
        s.onText("this is important for the exam", true)
        assertEquals(1, keys)
        t += 1_000; s.onText("a formula", true)
        assertEquals(1, keys) // 10 s cooldown
        t += 10_000; s.onText("a formula", true)
        assertEquals(2, keys)
        val cs = spotter("cs")
        cs.onText("tohle je důležité", true); cs.onText("important", true)
        assertEquals(3, keys)
    }

    @Test fun disabledPartsStaySilent() {
        spotter(wake = false, kw = false).onText("hey lens important", true)
        assertEquals(0, wakes + keys)
    }
}
