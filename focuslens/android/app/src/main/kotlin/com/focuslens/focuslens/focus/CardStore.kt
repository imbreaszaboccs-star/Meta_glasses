package com.focuslens.focuslens.focus

import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import org.json.JSONArray
import org.json.JSONObject

/**
 * Context cards as plain folders (ARCHITECTURE §8):
 *
 *   filesDir/cards/<id>/card.json, photo.jpg, photo-2.jpg…, audio.ogg | audio.wav, request.<ext>
 *   filesDir/sessions/<sessionId>.json
 *
 * card.json:
 *   {id, sessionId, createdAt, mode, locale, status: done|pending|failed, error?,
 *    steps:[{text, at, photo?}], card:{title, summary, correction, transcript_tail},
 *    reviewed, timings:{photoMs, encodeMs, aiMs, serverMs, totalMs}, attempts,
 *    request:{ts, previous[], audio?, audioMime?, photo?}   ← only while pending (for the retry worker)
 *    audio?: "audio.ogg"}
 *
 * The Flutter UI only reads these files. Every mutation goes through here (one lock), and every write is atomic.
 */
class CardStore(filesDir: File) {
    val cardsDir = File(filesDir, "cards").apply { mkdirs() }
    val sessionsDir = File(filesDir, "sessions").apply { mkdirs() }
    private val lock = Any()

    fun dir(id: String) = File(cardsDir, id)

    fun newId(): String {
        val base = LocalDateTime.now().format(ID_FORMAT)
        synchronized(lock) {
            var id = base
            var n = 2
            while (dir(id).exists()) id = "$base-${n++}"
            dir(id).mkdirs()
            return id
        }
    }

    fun read(id: String): JSONObject? = synchronized(lock) {
        val f = File(dir(id), "card.json")
        if (!f.exists()) null else runCatching { JSONObject(f.readText()) }.getOrNull()
    }

    fun write(id: String, card: JSONObject) = synchronized(lock) {
        val d = dir(id)
        if (!d.exists()) return@synchronized // deleted meanwhile (user swiped it away)
        writeAtomic(File(d, "card.json"), card.toString(2))
    }

    /** Read-modify-write under the lock. No-op if the card is gone. */
    fun update(id: String, block: (JSONObject) -> Unit): JSONObject? = synchronized(lock) {
        val c = read(id) ?: return@synchronized null
        block(c)
        write(id, c)
        c
    }

    fun saveFile(id: String, name: String, bytes: ByteArray) = synchronized(lock) {
        val d = dir(id)
        if (d.exists()) writeAtomic(File(d, name), bytes)
    }

    fun readFile(id: String, name: String): ByteArray? =
        File(dir(id), name).takeIf { it.exists() }?.readBytes()

    fun deleteFile(id: String, name: String) {
        File(dir(id), name).delete()
    }

    fun delete(id: String) = synchronized(lock) { dir(id).deleteRecursively() }

    fun deleteAll() = synchronized(lock) {
        cardsDir.deleteRecursively(); cardsDir.mkdirs()
        sessionsDir.deleteRecursively(); sessionsDir.mkdirs()
    }

    fun ids(): List<String> = cardsDir.listFiles { f -> f.isDirectory }?.map { it.name }?.sorted() ?: emptyList()

    fun pendingIds(): List<String> = ids().filter { read(it)?.optString("status") == STATUS_PENDING }

    /** Deletes cards (and sessions) older than [days]. 0 = keep forever. */
    fun applyRetention(days: Int) {
        if (days <= 0) return
        val cutoff = System.currentTimeMillis() - days * 86_400_000L
        synchronized(lock) {
            cardsDir.listFiles()?.filter { it.lastModified() < cutoff }?.forEach { it.deleteRecursively() }
            sessionsDir.listFiles()?.filter { it.lastModified() < cutoff }?.forEach { it.delete() }
        }
    }

    // -- sessions (focus blocks) --

    fun writeSession(session: JSONObject) = synchronized(lock) {
        writeAtomic(File(sessionsDir, session.getString("id") + ".json"), session.toString(2))
    }

    companion object {
        const val STATUS_DONE = "done"
        const val STATUS_PENDING = "pending"
        const val STATUS_FAILED = "failed"
        private val ID_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH-mm-ss")

        fun now(): String = LocalDateTime.now().withNano(0).toString()

        fun newCard(id: String, sessionId: String?, mode: String, locale: String) = JSONObject()
            .put("id", id).put("sessionId", sessionId ?: JSONObject.NULL).put("createdAt", now())
            .put("mode", mode).put("locale", locale).put("status", STATUS_PENDING)
            .put("steps", JSONArray()).put("card", JSONObject()).put("reviewed", false)
            .put("timings", JSONObject()).put("attempts", 0)

        private fun writeAtomic(f: File, text: String) = writeAtomic(f, text.toByteArray(Charsets.UTF_8))

        private fun writeAtomic(f: File, bytes: ByteArray) {
            val tmp = File(f.parentFile, f.name + ".tmp")
            tmp.writeBytes(bytes)
            if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
        }
    }
}
