package com.focuslens.focuslens.focus

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeechMeterTest {
    // 100 ms of PCM16 at a constant sample value (little-endian).
    private fun frame(v: Int) = ByteArray(3200) { if (it % 2 == 0) (v and 0xFF).toByte() else (v shr 8).toByte() }

    @Test
    fun quietForSeesOnlyTheLastFramesAcrossTheWrap() {
        val m = SpeechMeter()
        repeat(SpeechMeter.FRAMES + 3) { m.feed(frame(4000), 3200) } // wrapped, all loud
        assertFalse(m.quietFor(1))
        repeat(9) { m.feed(frame(0), 3200) }
        assertTrue(m.quietFor(9))
        assertFalse(m.quietFor(10)) // the 10th frame back was loud
        m.feed(frame(0), 3200)
        assertTrue(m.quietFor(10))
    }
}
