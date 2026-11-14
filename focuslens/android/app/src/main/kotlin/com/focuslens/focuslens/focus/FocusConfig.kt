package com.focuslens.focuslens.focus

import android.content.SharedPreferences

/** Settings the native side needs. Source of truth is the Flutter settings screen; mirrored here for the service + retry worker. */
data class FocusConfig(
    val mode: String = "lecture",          // lecture | homework | desk
    val language: String = "en",           // language of the hints (the UI is English)
    val bufferSec: Int = 180,              // 60 | 120 | 180
    val keepAudio: String = "last60",      // none | last60 | all
    val hudTimeoutSec: Int = 20,           // 0 = never
    val readAloud: Boolean = false,        // TTS even when the glasses have a display
    val retentionDays: Int = 30,           // 0 = forever
) {
    fun save(p: SharedPreferences) = p.edit()
        .putString("mode", mode).putString("language", language).putInt("bufferSec", bufferSec)
        .putString("keepAudio", keepAudio).putInt("hudTimeoutSec", hudTimeoutSec)
        .putBoolean("readAloud", readAloud).putInt("retentionDays", retentionDays)
        .apply()

    fun merge(m: Map<*, *>?): FocusConfig {
        if (m == null) return this
        fun str(k: String, d: String) = (m[k] as? String) ?: d
        fun int(k: String, d: Int) = (m[k] as? Number)?.toInt() ?: d
        fun bool(k: String, d: Boolean) = (m[k] as? Boolean) ?: d
        return FocusConfig(
            mode = str("mode", mode).takeIf { it in MODES } ?: mode,
            language = str("language", language),
            bufferSec = int("bufferSec", bufferSec).coerceIn(30, 180),
            keepAudio = str("keepAudio", keepAudio).takeIf { it in KEEP } ?: keepAudio,
            hudTimeoutSec = int("hudTimeoutSec", hudTimeoutSec).coerceIn(0, 120),
            readAloud = bool("readAloud", readAloud),
            retentionDays = int("retentionDays", retentionDays).coerceAtLeast(0),
        )
    }

    fun toMap() = mapOf(
        "mode" to mode, "language" to language, "bufferSec" to bufferSec, "keepAudio" to keepAudio,
        "hudTimeoutSec" to hudTimeoutSec, "readAloud" to readAloud, "retentionDays" to retentionDays,
    )

    companion object {
        val MODES = setOf("lecture", "homework", "desk")
        val KEEP = setOf("none", "last60", "all")

        fun load(p: SharedPreferences) = FocusConfig(
            mode = p.getString("mode", null) ?: "lecture",
            language = p.getString("language", null) ?: "en",
            bufferSec = p.getInt("bufferSec", 180),
            keepAudio = p.getString("keepAudio", null) ?: "last60",
            hudTimeoutSec = p.getInt("hudTimeoutSec", 20),
            readAloud = p.getBoolean("readAloud", false),
            retentionDays = p.getInt("retentionDays", 30),
        )
    }
}
