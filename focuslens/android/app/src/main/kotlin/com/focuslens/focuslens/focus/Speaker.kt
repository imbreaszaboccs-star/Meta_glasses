package com.focuslens.focuslens.focus

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.io.File
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Speaks a hint. One voice: backend /api/ai/tts. Android TextToSpeech only when there is no HUD to read the step from.
 * Audio goes to the current media route, which is the glasses when they are connected over A2DP.
 */
class Speaker(private val context: Context) {
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var player: MediaPlayer? = null
    private var last: Pair<String, ByteArray>? = null // WAV of the last text, so repeat/next doesn't refetch
    /** Fired on every speaking start/stop (any thread). */
    var onSpeaking: (Boolean) -> Unit = {}
    @Volatile private var speaking = false

    private fun setSpeaking(on: Boolean) {
        if (speaking == on) return
        speaking = on
        onSpeaking(on)
    }

    private val attrs = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANT)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    fun warmUp() {
        if (tts == null) tts = TextToSpeech(context) { status ->
            ttsReady = status == TextToSpeech.SUCCESS
            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(id: String?) = setSpeaking(true)
                override fun onDone(id: String?) = setSpeaking(false)
                @Deprecated("Deprecated in Java") override fun onError(id: String?) = setSpeaking(false)
            })
        }
    }

    /** [hasDisplay]: the text is on the HUD, so a failed /api/ai/tts stays silent instead of switching voice. */
    suspend fun speak(text: String, locale: String, hasDisplay: Boolean = false) {
        stop()
        val wav = last?.takeIf { it.first == text }?.second
            ?: withTimeoutOrNull(6_000) { withContext(Dispatchers.IO) { HintClient.tts(text, 6_000) } }?.also { last = text to it }
        if (wav != null && playWav(wav)) return
        if (!hasDisplay) speakLocal(text, locale)
    }

    fun speakLocal(text: String, locale: String) {
        warmUp()
        val t = tts ?: return
        if (!ttsReady) return
        t.language = Locale.forLanguageTag(locale)
        t.setAudioAttributes(attrs)
        t.setSpeechRate(0.92f)
        setSpeaking(true)
        t.speak(text, TextToSpeech.QUEUE_FLUSH, null, "hint")
    }

    private suspend fun playWav(wav: ByteArray): Boolean = withContext(Dispatchers.IO) {
        try {
            val f = File(context.cacheDir, "tts.wav").apply { writeBytes(wav) }
            withContext(Dispatchers.Main) {
                player = MediaPlayer().apply {
                    setAudioAttributes(attrs)
                    setDataSource(f.absolutePath)
                    setOnCompletionListener { it.release(); if (player === it) { player = null; setSpeaking(false) } }
                    prepare()
                    start()
                }
                setSpeaking(true)
            }
            true
        } catch (e: Exception) {
            false
        }
    }

    fun stop() {
        runCatching { player?.stop(); player?.release() }
        player = null
        tts?.stop()
        setSpeaking(false)
    }

    fun shutdown() {
        stop()
        tts?.shutdown()
        tts = null
    }
}
