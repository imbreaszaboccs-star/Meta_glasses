package com.focuslens.focuslens.focus

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SessionMemoryTest {
    private fun c(at: Long, topic: String, shift: Boolean) = TranscriptChunk(at, "t", "s", topic, shift)
    private val m = SessionMemory.CHUNK_MS

    private val a0 = c(1 * m, "A", true)
    private val a1 = c(2 * m, "A", false)
    private val a2 = c(3 * m, "A", false)
    private val b0 = c(4 * m, "B", true)
    private val b1 = c(5 * m, "B", false)
    private val timeline = listOf(a0, a1, a2, b0, b1)

    @Test
    fun emptyTimelineHasNoTopic() {
        val s = SessionMemory.split(emptyList(), 0)
        assertEquals(0, s.current.size)
        assertEquals(0, s.earlier.size)
        assertNull(SessionMemory.currentStart(emptyList(), 0))
    }

    @Test
    fun freshTimelineSplitsAtShift() {
        val now = b1.atMs + 1_000
        val s = SessionMemory.split(timeline, now)
        assertEquals(listOf(b0, b1), s.current)
        assertEquals(listOf(listOf(a0, a1, a2)), s.earlier)
        assertEquals(b0.atMs - m, SessionMemory.currentStart(timeline, now))
    }

    @Test
    fun staleTimelineHasNoCurrentTopic() {
        val now = b1.atMs + 6 * 60_000L
        val s = SessionMemory.split(timeline, now)
        assertEquals(0, s.current.size)
        assertEquals(listOf(listOf(a0, a1, a2), listOf(b0, b1)), s.earlier)
        assertNull(SessionMemory.currentStart(timeline, now))
    }
}
