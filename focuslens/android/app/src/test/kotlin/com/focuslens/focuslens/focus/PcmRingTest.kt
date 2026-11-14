package com.focuslens.focuslens.focus

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class PcmRingTest {
    private fun b(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    @Test
    fun keepsOnlyTheLastBytesInOrder() {
        val r = PcmRing(4)
        r.add(b(1, 2, 3))
        assertArrayEquals(b(1, 2, 3), r.snapshot())
        r.add(b(4, 5, 6))
        assertArrayEquals(b(3, 4, 5, 6), r.snapshot())
        r.add(b(7, 8, 9, 10, 11)) // larger than capacity
        assertArrayEquals(b(8, 9, 10, 11), r.snapshot())
    }

    @Test
    fun respectsLengthArgument() {
        val r = PcmRing(8)
        r.add(b(1, 2, 3, 4, 5), length = 2)
        assertArrayEquals(b(1, 2), r.snapshot())
        assertEquals(2, r.size)
    }

    @Test
    fun exactWrapAndClear() {
        val r = PcmRing(4)
        r.add(b(1, 2, 3, 4))
        assertArrayEquals(b(1, 2, 3, 4), r.snapshot())
        r.add(b(5))
        assertArrayEquals(b(2, 3, 4, 5), r.snapshot())
        r.clear()
        assertEquals(0, r.size)
        assertArrayEquals(ByteArray(0), r.snapshot())
    }
}
