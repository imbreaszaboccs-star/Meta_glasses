package com.focuslens.focuslens.focus

import android.content.SharedPreferences

/** Settings the native side needs. Source of truth is the Flutter settings screen; mirrored here for the service + retry worker. */
data class FocusConfig(
    val mode: String = "auto",             // auto | lecture | homework | desk
    val language: String = "en",           // language of the hints (the UI is English)
    val bufferSec: Int = 150,              // 60 | 120 | 150
    val keepAudio: String = "last60",      // none | last60 | all
    val hudTimeoutSec: Int = 20,           // 0 = never
    val readAloud: Boolean = false,        // TTS even when the glasses have a display
    val retentionDays: Int = 30,           // 0 = forever
    val wakeWord: Boolean = true,          // "hey lens" on the phone mic (Vosk, on-device)
    val nudges: Boolean = true,            // proactive "key point" / "stuck" nudges on the HUD
    val audioSource: String = "auto",      // auto | phone | glasses (see FocusEngine.chooseAudio)
    val hudSource: String = "web",         // web | dat (HUD drawn by the web app on the glasses, or via DAT sendContent)
) {
    fun save(p: SharedPreferences) = p.edit()
        .putString("mode", mode).putString("language", language).putInt("bufferSec", bufferSec)
        .putString("keepAudio", keepAudio).putInt("hudTimeoutSec", hudTimeoutSec)
        .putBoolean("readAloud", readAloud).putInt("retentionDays", retentionDays).putBoolean("wakeWord", wakeWord).putBoolean("nudges", nudges)
        .putString("audioSource", audioSource).putString("hudSource", hudSource).apply()

    fun merge(m: Map<*, *>?): FocusConfig {
        if (m == null) return this
        fun str(k: String, d: String) = (m[k] as? String) ?: d
        fun int(k: String, d: Int) = (m[k] as? Number)?.toInt() ?: d
        fun bool(k: String, d: Boolean) = (m[k] as? Boolean) ?: d
        return FocusConfig(
            mode = str("mode", mode).takeIf { it in MODES } ?: mode,
            language = str("language", language),
            bufferSec = int("bufferSec", bufferSec).coerceIn(30, 150),
            keepAudio = str("keepAudio", keepAudio).takeIf { it in KEEP } ?: keepAudio,
            hudTimeoutSec = int("hudTimeoutSec", hudTimeoutSec).coerceIn(0, 120),
            readAloud = bool("readAloud", readAloud),
            retentionDays = int("retentionDays", retentionDays).coerceAtLeast(0),
            wakeWord = bool("wakeWord", wakeWord),
            nudges = bool("nudges", nudges),
            audioSource = str("audioSource", audioSource).takeIf { it in AUDIO } ?: audioSource,
            hudSource = str("hudSource", hudSource).takeIf { it in HUD } ?: hudSource,
        )
    }

    fun toMap() = mapOf(
        "mode" to mode, "language" to language, "bufferSec" to bufferSec, "keepAudio" to keepAudio,
        "hudTimeoutSec" to hudTimeoutSec, "readAloud" to readAloud, "retentionDays" to retentionDays, "wakeWord" to wakeWord, "nudges" to nudges,
        "audioSource" to audioSource, "hudSource" to hudSource,
    )

    companion object {
        val MODES = setOf("auto", "lecture", "homework", "desk")
        val KEEP = setOf("none", "last60", "all")
        val AUDIO = setOf("auto", "phone", "glasses")
        val HUD = setOf("web", "dat")

        fun load(p: SharedPreferences) = FocusConfig(
            mode = p.getString("mode", null) ?: "auto",
            language = p.getString("language", null) ?: "en",
            bufferSec = p.getInt("bufferSec", 150),
            keepAudio = p.getString("keepAudio", null) ?: "last60",
            hudTimeoutSec = p.getInt("hudTimeoutSec", 20),
            readAloud = p.getBoolean("readAloud", false),
            retentionDays = p.getInt("retentionDays", 30),
            wakeWord = p.getBoolean("wakeWord", true),
            nudges = p.getBoolean("nudges", true),
            audioSource = p.getString("audioSource", null) ?: "auto",
            hudSource = p.getString("hudSource", null) ?: "web",
        )
    }
}
