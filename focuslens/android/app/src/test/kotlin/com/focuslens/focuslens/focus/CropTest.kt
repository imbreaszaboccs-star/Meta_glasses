package com.focuslens.focuslens.focus

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class CropTest {
    private fun box(vararg v: Int) = v

    @Test
    fun boxMapsToPixels() {
        val r = Crop.box(box(100, 200, 300, 600), 1000, 500)!!
        assertEquals(200, r.left); assertEquals(50, r.top); assertEquals(600, r.right); assertEquals(150, r.bottom)
    }

    @Test
    fun paddingGrowsTheBoxByTwelvePercentPerSide() {
        // box 400×100 px at (200,50): pad 48 px horizontally, 12 px vertically
        val r = Crop.region(box(100, 200, 300, 600), 1000, 500)!!
        assertEquals(152, r.left); assertEquals(38, r.top); assertEquals(648, r.right); assertEquals(162, r.bottom)
    }

    @Test
    fun paddingIsClampedToTheImage() {
        val r = Crop.region(box(0, 0, 500, 500), 1000, 1000)!!
        assertEquals(0, r.left); assertEquals(0, r.top)
        val r2 = Crop.region(box(500, 500, 1000, 1000), 1000, 1000)!!
        assertEquals(1000, r2.right); assertEquals(1000, r2.bottom)
    }

    @Test
    fun invalidBoxesGiveNull() {
        assertNull(Crop.region(null, 100, 100))
        assertNull(Crop.region(box(1, 2, 3), 100, 100))            // wrong size
        assertNull(Crop.region(box(300, 100, 200, 400), 100, 100)) // ymin > ymax
        assertNull(Crop.region(box(100, 100, 100, 400), 100, 100)) // empty
        assertNull(Crop.region(box(-1, 0, 500, 500), 100, 100))    // negative
        assertNull(Crop.region(box(0, 0, 1001, 500), 100, 100))    // out of range
        assertNull(Crop.region(box(0, 0, 50, 50), 100, 100))       // 0.25 % of the image
    }

    @Test
    fun smallestAcceptedAreaIsHalfAPercent() {
        assertNotNull(Crop.region(box(0, 0, 50, 100), 1000, 1000)) // 5000 / 1e6 = 0.5 %
        assertNull(Crop.region(box(0, 0, 49, 100), 1000, 1000))
    }
}
