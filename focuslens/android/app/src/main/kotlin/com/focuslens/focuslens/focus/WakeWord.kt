package com.focuslens.focuslens.focus

import android.content.Context
import android.os.SystemClock
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.StorageService

/**
 * "hey lens" and lecture keywords on the phone mic, fully on-device (small Vosk models in assets, fetched
 * by tool/get_wake_model.ps1: vosk-model-en for "hey lens" + English keywords, vosk-model-cs for Czech
 * keywords when the hint language is "cs"). Each recognizer is grammar-restricted, so it is cheap.
 * MicRing feeds [feed] from the recorder thread; one background thread consumes and feeds every
 * recognizer, so the recorder never blocks (chunks are dropped when the queue is full).
 * A missing model asset is logged once and that recognizer is skipped. Keyword hits carry no text.
 */
class WakeWord(
    private val context: Context,
    private val log: (String, String) -> Unit,
    private val language: String,
    private val wake: Boolean,
    private val keywords: Boolean,
    private val onWake: () -> Unit,
    private val onKeyword: () -> Unit,
) {
    private val queue = ArrayBlockingQueue<ByteArray>(50) // 5 s of 100 ms chunks
    @Volatile private var running = false
    private var thread: Thread? = null

    fun feed(chunk: ByteArray, n: Int) {
        if (running) queue.offer(chunk.copyOf(n))
    }

    fun start() {
        if (running) return
        running = true
        queue.clear()
        thread = Thread(::loop, "focuslens-wake").apply { isDaemon = true; start() }
    }

    fun stop() {
        running = false
        thread?.interrupt()
        thread = null
        queue.clear()
    }

    private class Hit(val phrases: List<String>, val cooldownMs: Long, val action: () -> Unit) {
        var cooldownUntil = 0L
    }

    private class Spot(val rec: Recognizer, val hits: List<Hit>) {
        var bytes = 0

        fun feed(c: ByteArray, onFired: () -> Unit) {
            val final = rec.acceptWaveForm(c, c.size)
            bytes += c.size
            if (!final && bytes < MicRing.BYTES_PER_SECOND / 2) return // check about every 500 ms
            bytes = 0
            val json = if (final) rec.result else rec.partialResult
            val hit = hits.firstOrNull { h -> h.phrases.any { json.contains(it) } } ?: return
            rec.reset()
            onFired()
            val now = SystemClock.elapsedRealtime()
            if (now < hit.cooldownUntil) return
            hit.cooldownUntil = now + hit.cooldownMs
            hit.action()
        }
    }

    private fun spot(model: Model, hits: List<Hit>, extra: List<String> = emptyList()): Spot {
        val words = (hits.flatMap { it.phrases } + extra + "[unk]").joinToString(prefix = "[", postfix = "]") { "\"$it\"" }
        return Spot(Recognizer(model, 16000f, words), hits)
    }

    private fun loop() {
        val spots = ArrayList<Spot>()
        try {
            val cs = language == "cs"
            val enKeys = keywords && !cs
            if (wake || enKeys) model("vosk-model-en", "vosk-model")?.let { m ->
                val hits = ArrayList<Hit>()
                if (wake) hits += Hit(listOf(WAKE_PHRASE), WAKE_COOLDOWN_MS) { log("wake", "heard \"hey lens\""); onWake() }
                if (enKeys) hits += Hit(EN_KEYWORDS, KEYWORD_COOLDOWN_MS) { log("wake", "keyword"); onKeyword() }
                spots += spot(m, hits, extra = if (wake) listOf("lens") else emptyList())
            }
            if (keywords && cs) model("vosk-model-cs", "vosk-model-cs")?.let { m ->
                spots += spot(m, listOf(Hit(CS_KEYWORDS, KEYWORD_COOLDOWN_MS) { log("wake", "keyword"); onKeyword() }))
            }
            if (spots.isEmpty()) return
            try {
                while (running) {
                    val c = queue.poll(200, TimeUnit.MILLISECONDS) ?: continue
                    var fired = false
                    for (sp in spots) sp.feed(c) { fired = true }
                    if (fired) queue.clear()
                }
            } finally {
                spots.forEach { it.rec.close() }
            }
        } catch (e: InterruptedException) {
            // stop()
        } catch (e: Throwable) {
            log("error", "wake word off: ${e.javaClass.simpleName}")
        }
    }

    /** Loaded once per process; a missing asset is logged once and that model is skipped from then on. */
    private fun model(asset: String, target: String): Model? {
        models[asset]?.let { return it }
        if (asset in failed) return null
        return synchronized(Companion) {
            models[asset] ?: try {
                Model(StorageService.sync(context, asset, target)).also { models[asset] = it }
            } catch (e: Throwable) {
                failed += asset
                log("error", "$asset unavailable: ${e.javaClass.simpleName} (run tool/get_wake_model.ps1?)")
                null
            }
        }
    }

    companion object {
        const val WAKE_PHRASE = "hey lens"
        const val WAKE_COOLDOWN_MS = 3_000L
        const val KEYWORD_COOLDOWN_MS = 10_000L
        val EN_KEYWORDS = listOf("definition", "formula", "important", "exam", "remember this", "key point")
        val CS_KEYWORDS = listOf("definice", "vzorec", "důležité", "zkouška", "u zkoušky", "zapamatujte si")
        val models = java.util.concurrent.ConcurrentHashMap<String, Model>()
        val failed = java.util.concurrent.CopyOnWriteArraySet<String>()
    }
}
