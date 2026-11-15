package com.focuslens.focuslens.focus

/** One transcribed ~60 s chunk; atMs = elapsedRealtime when the chunk's audio ended. */
class TranscriptChunk(val atMs: Long, val text: String, val summary: String, val topic: String, val shift: Boolean)

/** Live-transcript timeline split into topic segments. Pure Kotlin. */
object SessionMemory {
    const val CHUNK_MS = 60_000L
    const val FRESH_MS = 5 * 60_000L // older last chunk = transcript is stale (no speech lately / toggle off): no current topic

    class Split(val current: List<TranscriptChunk>, val earlier: List<List<TranscriptChunk>>)

    /** Topic segments: a new one starts at the first chunk and at every shift. Current = last segment if the last chunk is fresh. */
    fun split(timeline: List<TranscriptChunk>, now: Long): Split {
        val segs = ArrayList<MutableList<TranscriptChunk>>()
        for ((i, c) in timeline.withIndex()) {
            if (i == 0 || c.shift) segs.add(ArrayList())
            segs.last().add(c)
        }
        val fresh = timeline.isNotEmpty() && now - timeline.last().atMs <= FRESH_MS
        return if (fresh) Split(segs.last(), segs.dropLast(1)) else Split(emptyList(), segs)
    }

    /** Start of the current topic (first chunk's atMs - CHUNK_MS), or null when there is no fresh current topic. */
    fun currentStart(timeline: List<TranscriptChunk>, now: Long): Long? =
        split(timeline, now).current.firstOrNull()?.let { it.atMs - CHUNK_MS }
}
