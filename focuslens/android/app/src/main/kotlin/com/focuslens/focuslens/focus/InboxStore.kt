package com.focuslens.focuslens.focus

import java.io.File
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

/**
 * Brain-dump inbox: filesDir/inbox.json = [{id, text, createdAt, source, done, synced:{}}].
 * Sync targets (Todoist…) record themselves in `synced`.
 */
class InboxStore(filesDir: File) {
    private val file = File(filesDir, "inbox.json")

    fun list(): JSONArray = synchronized(LOCK) { read() }

    fun add(text: String, source: String = "glasses"): JSONObject {
        val item = JSONObject().put("id", UUID.randomUUID().toString()).put("text", text)
            .put("createdAt", CardStore.now()).put("source", source).put("done", false).put("synced", JSONObject())
        synchronized(LOCK) { write(read().put(item)) }
        Integrations.kickSync()
        return item
    }

    fun setDone(id: String, done: Boolean) = change(id) { it.put("done", done) }

    fun delete(id: String) = synchronized(LOCK) {
        val a = read()
        write(JSONArray().also { out -> for (i in 0 until a.length()) if (a.getJSONObject(i).optString("id") != id) out.put(a.getJSONObject(i)) })
    }

    /** Open notes not yet pushed to Todoist. */
    fun unsynced(): List<JSONObject> = synchronized(LOCK) {
        val a = read()
        (0 until a.length()).map { a.getJSONObject(it) }
            .filter { !it.optBoolean("done") && !(it.optJSONObject("synced")?.optBoolean("todoist") ?: false) }
    }

    fun markSynced(id: String, target: String) = change(id) {
        val s = it.optJSONObject("synced") ?: JSONObject().also { n -> it.put("synced", n) }
        s.put(target, true)
    }

    fun clear() = synchronized(LOCK) { file.delete(); Unit }

    private fun change(id: String, f: (JSONObject) -> Unit) = synchronized(LOCK) {
        val a = read()
        for (i in 0 until a.length()) a.getJSONObject(i).takeIf { it.optString("id") == id }?.let(f)
        write(a)
    }

    private fun read(): JSONArray = runCatching { JSONArray(file.readText()) }.getOrDefault(JSONArray())

    private fun write(a: JSONArray) {
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(a.toString(2))
        if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
    }

    private companion object { val LOCK = Any() }
}
