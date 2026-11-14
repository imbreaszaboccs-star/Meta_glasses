package com.focuslens.focuslens.focus

import android.content.SharedPreferences
import org.json.JSONArray

/** The user's recurring mistakes (short generic types from the AI): last 10, newest last, no case-insensitive duplicates. */
class MistakeStore(private val p: SharedPreferences) {
    @Synchronized
    fun list(): List<String> {
        val a = runCatching { JSONArray(p.getString(KEY, "[]")) }.getOrDefault(JSONArray())
        return (0 until a.length()).map { a.optString(it) }.filter { it.isNotBlank() }
    }

    @Synchronized
    fun add(mistake: String) {
        val m = mistake.trim().take(80)
        if (m.isEmpty()) return
        val next = (list().filterNot { it.equals(m, ignoreCase = true) } + m).takeLast(MAX)
        p.edit().putString(KEY, JSONArray(next).toString()).apply()
    }

    @Synchronized
    fun clear() = p.edit().remove(KEY).apply()

    private companion object {
        const val KEY = "mistakes"
        const val MAX = 10
    }
}
