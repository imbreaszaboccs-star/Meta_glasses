package com.focuslens.focuslens.focus

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageFixTest {
    private fun gray(v: Int) = (0xFF shl 24) or (v shl 16) or (v shl 8) or v
    private fun image(lo: Int, span: Int) = IntArray(10_000) { gray(lo + it % span) }

    @Test
    fun brightImageGetsDarkerAndLessClipped() {
        val px = image(200, 56) // 200..255, ~9 % above 250
        val before = ImageFix.stats(px)
        assertNotNull(ImageFix.correct(px))
        val after = ImageFix.stats(px)
        assertTrue(after.mean < before.mean)
        assertTrue(after.clipped < before.clipped)
    }

    @Test
    fun darkImageIsUnchanged() {
        val px = image(10, 50)
        val copy = px.copyOf()
        assertNull(ImageFix.correct(px))
        assertArrayEquals(copy, px)
    }

    @Test
    fun normalImageIsUnchanged() {
        val px = image(40, 160)
        val copy = px.copyOf()
        assertNull(ImageFix.correct(px))
        assertArrayEquals(copy, px)
    }
}
