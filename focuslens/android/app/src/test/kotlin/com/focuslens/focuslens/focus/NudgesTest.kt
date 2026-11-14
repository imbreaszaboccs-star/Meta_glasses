package com.focuslens.focuslens.focus

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NudgesTest {
    private var t = 1_000L
    private val n = Nudges { t }

    private fun advance(sec: Int) { repeat(sec) { t += 1_000; n.tick().also { last = it ?: last; if (it != null) fired += it } } }
    private var last: NudgeKind? = null
    private val fired = mutableListOf<NudgeKind>()

    @Test fun keywordThenHeadUp8sGivesKey() {
        n.onHead(false); n.onKeyword()
        advance(7); assertEquals(0, fired.size)
        advance(1); assertEquals(listOf(NudgeKind.KEY), fired)
    }

    @Test fun keywordWhileHeadDownGivesNothing() {
        n.onHead(true); n.onKeyword()
        advance(20); assertEquals(0, fired.size)
    }

    @Test fun headDownAgainCancelsKey() {
        n.onHead(false); n.onKeyword(); advance(3)
        n.onHead(true); advance(3); n.onHead(false); advance(20)
        assertEquals(0, fired.size)
    }

    @Test fun noHeadSignalNoKey() {
        n.onHead(null); n.onKeyword(); advance(20)
        assertEquals(0, fired.size)
    }

    @Test fun questIdle120sGivesStuck() {
        n.onQuestStart()
        advance(119); assertEquals(0, fired.size)
        advance(1); assertEquals(listOf(NudgeKind.STUCK), fired)
    }

    @Test fun progressResetsStuck() {
        n.onQuestStart(); advance(100); n.onQuestProgress(); advance(100)
        assertEquals(0, fired.size)
    }

    @Test fun stuckNeedsHeadMostlyDownWhenKnown() {
        n.onHead(false); n.onQuestStart(); advance(130)
        assertEquals(0, fired.size)
        n.onHead(true); advance(130) // plenty of down ticks now
        assertEquals(listOf(NudgeKind.STUCK), fired)
    }

    @Test fun cooldownRespected() {
        n.onQuestStart(); advance(120)
        assertEquals(1, fired.size)
        n.onHead(false); n.onKeyword(); advance(20) // inside the 3 min cooldown
        assertEquals(1, fired.size)
        advance(140); n.onKeyword(); advance(25)    // fresh keyword once the 3 min are over
        assertEquals(2, fired.size)
    }

    @Test fun disabledGivesNothing() {
        n.enabled = false
        n.onHead(false); n.onKeyword(); n.onQuestStart(); advance(300)
        assertNull(n.tick())
        assertEquals(0, fired.size)
    }
}
