package com.focuslens.focuslens.focus

/**
 * Ring buffer of raw bytes (PCM16 mono). Keeps the last [capacity] bytes in chronological order.
 * Pure and synchronized so the recorder thread can write while the hint path takes a snapshot.
 */
class PcmRing(val capacity: Int) {
    private val buf = ByteArray(capacity)
    private var write = 0
    private var full = false

    @Synchronized
    fun add(chunk: ByteArray, length: Int = chunk.size) {
        if (length <= 0) return
        // A chunk larger than the ring: only its tail survives.
        val offset = if (length > capacity) length - capacity else 0
        val n = length - offset
        val first = minOf(capacity - write, n)
        System.arraycopy(chunk, offset, buf, write, first)
        if (n > first) System.arraycopy(chunk, offset + first, buf, 0, n - first)
        if (write + n >= capacity) full = true
        write = (write + n) % capacity
    }

    /** Oldest → newest copy. */
    @Synchronized
    fun snapshot(): ByteArray =
        if (!full) buf.copyOfRange(0, write)
        else ByteArray(capacity).also {
            System.arraycopy(buf, write, it, 0, capacity - write)
            System.arraycopy(buf, 0, it, capacity - write, write)
        }

    @get:Synchronized
    val size: Int get() = if (full) capacity else write

    /** Forget everything (used on pause, so audio from before a pause can never be sent). */
    @Synchronized
    fun clear() {
        buf.fill(0)
        write = 0
        full = false
    }
}
