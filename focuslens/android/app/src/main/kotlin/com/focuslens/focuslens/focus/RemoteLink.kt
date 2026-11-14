package com.focuslens.focuslens.focus

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import com.focuslens.focuslens.BuildConfig
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDate
import java.util.Collections
import java.util.WeakHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject

// ---------------------------------------------------------------------------------------------
// Pure parts (unit tested in RemoteLinkTest)
// ---------------------------------------------------------------------------------------------

/** [reply]: a Meta AI connector call is waiting; the phone answers with POST /api/r/res after executing it. */
class RemoteCmd(val id: String, val type: String, val args: JSONObject, val reply: Boolean = false)

object RemoteProto {
    const val MAX_STATE_BYTES = 64 * 1024
    val GESTURES = setOf("select", "left", "right", "up", "down", "back")

    fun parse(data: String): RemoteCmd? {
        val j = runCatching { JSONObject(data) }.getOrNull() ?: return null
        val id = j.optString("id")
        val type = j.optString("type")
        if (id.isEmpty() || id.length > 100 || type.isEmpty()) return null
        return RemoteCmd(id, type, j.optJSONObject("args") ?: JSONObject(), j.optBoolean("reply", false))
    }

    private fun str(a: JSONObject, k: String, max: Int): String? = (a.opt(k) as? String)?.takeIf { it.isNotBlank() && it.length <= max }

    /** The server already validated; the phone checks again. Returns null when fine, else the text for the ack. */
    fun validate(type: String, a: JSONObject): String? = when (type) {
        "start_focus" -> when {
            a.has("mode") && (a.opt("mode") as? String) !in FocusConfig.MODES -> "bad mode"
            a.has("goal") && !a.isNull("goal") && a.opt("goal") !is String -> "bad goal"
            else -> null
        }
        "stop_focus", "help", "bookmark", "pause_mic", "resume_mic", "review_load", "refresh", "last_hint", "next", "reconnect", "inbox_sync" -> null
        "card_reviewed" -> when {
            str(a, "id", 100) == null -> "bad id"
            a.opt("reviewed") !is Boolean -> "bad reviewed"
            else -> null
        }
        "card_delete" -> if (str(a, "id", 100) == null) "bad id" else null
        "imu_label" -> if ((a.opt("label") as? String) !in IMU_LABELS) "bad label" else null
        "web_seen" -> if (a.opt("visible") !is Boolean) "bad visible" else null
        "add_note" -> if (str(a, "text", 300) == null) "bad text" else null
        "set_config" -> when {
            CONFIG_KEYS.none { a.has(it) } -> "nothing to change"
            a.has("mode") && (a.opt("mode") as? String) !in FocusConfig.MODES -> "bad mode"
            BOOL_KEYS.any { a.has(it) && a.opt(it) !is Boolean } -> "bad flag"
            ENUMS.any { (k, ok) -> a.has(k) && (a.opt(k) as? String) !in ok } -> "bad value"
            INTS.any { (k, ok) -> a.has(k) && integral(a.opt(k)) !in ok } -> "bad value"
            else -> null
        }
        "inbox_done" -> when {
            str(a, "id", 100) == null -> "bad id"
            a.opt("done") !is Boolean -> "bad done"
            else -> null
        }
        "inbox_delete" -> if (str(a, "id", 100) == null) "bad id" else null
        "gesture" -> if ((a.opt("g") as? String) !in GESTURES) "bad gesture" else null
        else -> "unknown command"
    }

    val IMU_LABELS = setOf("none") + Act.entries.map { it.name.lowercase() }
    private val BOOL_KEYS = listOf("nudges", "wakeWord", "readAloud", "dozeSound", "collect")
    private val ENUMS = mapOf(
        "language" to setOf("en", "cs", "sk", "de", "pl"), "keepAudio" to FocusConfig.KEEP, "audioSource" to FocusConfig.AUDIO,
    )
    private val INTS = mapOf("bufferSec" to setOf(60, 120, 150), "hudTimeoutSec" to setOf(0, 10, 20, 40), "retentionDays" to setOf(0, 7, 30))
    private val CONFIG_KEYS = listOf("mode") + BOOL_KEYS + ENUMS.keys + INTS.keys

    /** JSON numbers arrive as Int or Double: the Int if the value is integral, else null. */
    private fun integral(v: Any?): Int? = (v as? Number)?.let { n -> n.toDouble().takeIf { it == Math.floor(it) && Math.abs(it) < 1e9 }?.toInt() }

    /** Only the keys the glasses menu may change, as an engine config map. */
    fun configPatch(a: JSONObject): Map<String, Any> = buildMap {
        for (k in CONFIG_KEYS) if (a.has(k)) put(k, if (k in INTS) integral(a.get(k)) ?: continue else a.get(k))
    }

    fun clip(s: String?, n: Int): String = (s ?: "").let { if (it.length > n) it.take(n - 1) + "…" else it }

    /** Drops the least important rows (review, then cards, then inbox, oldest last) until the JSON fits [max] UTF-8 bytes. */
    fun trimState(st: JSONObject, max: Int = MAX_STATE_BYTES): JSONObject {
        fun size() = st.toString().toByteArray(Charsets.UTF_8).size
        val arrays = listOf(st.optJSONObject("review")?.optJSONArray("cards"), st.optJSONArray("cards"), st.optJSONArray("inbox"))
        for (a in arrays) while (a != null && a.length() > 0 && size() > max) a.remove(a.length() - 1)
        if (size() > max) st.optJSONObject("focus")?.put("lastHint", JSONObject.NULL)
        return st
    }
}

// ---------------------------------------------------------------------------------------------
// The link
// ---------------------------------------------------------------------------------------------

/**
 * Phone side of the glasses relay (web/lib/relay.ts, Redis): polls `/api/r/poll` for commands, publishes the
 * state + HUD view model + the last acks to `/api/r/view` (debounced, heartbeat < 30 s) and uploads HUD crops to
 * `/api/r/img`. The web app on the glasses renders the view and forwards glasses input as `gesture` commands; the
 * standby [FocusService] keeps this process alive. device_id / phone_secret live in the encrypted prefs; neither
 * they nor any state text is ever logged.
 */
class RemoteLink(private val app: Context, private val engine: FocusEngine) : EngineListener {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var devId = Integrations.secret(KEY_ID)
    private var secret = Integrations.secret(KEY_SECRET)
    private var job: Job? = null
    private val kick = Channel<Boolean>(Channel.CONFLATED) // true = push right now
    @Volatile private var hudFast = false // the pending kick comes from a HUD change: short debounce
    private val acks = ArrayDeque<JSONObject>() // last 10, included in every view publish
    private val cropHashes = WeakHashMap<Bitmap, String>() // guarded by itself
    private val cropUploading = Collections.newSetFromMap(WeakHashMap<Bitmap, Boolean>()) // guarded by cropHashes
    private val cmds = Channel<RemoteCmd>(32)

    @Volatile var connected = false
        private set
    @Volatile var lastError: String? = null
        private set

    // review (flashcards for today's cards), part of the pushed state
    @Volatile private var reviewStatus = "idle"
    @Volatile private var reviewCards: List<Pair<String, String>> = emptyList()
    @Volatile private var reviewError: String? = null

    // inbox + cards are files: rebuilt at most every few seconds, or when something says they changed
    private var dataJson: Pair<JSONArray, JSONArray>? = null
    private var dataAt = 0L
    @Volatile private var dataDirty = true

    val enabled: Boolean get() = devId.isNotEmpty() && secret.isNotEmpty()

    fun status(): Map<String, Any?> = mapOf("paired" to enabled, "connected" to connected, "lastError" to lastError)

    // -- lifecycle --

    fun start() {
        if (!enabled || job != null) return
        scope.launch(Dispatchers.Main.immediate) { engine.addListener(this@RemoteLink) }
        job = scope.launch {
            launch { pushLoop() }
            launch { for (c in cmds) handle(c) }
            pollLoop()
        }
    }

    fun stop() {
        job?.cancel(); job = null
        connected = false
        scope.launch(Dispatchers.Main.immediate) { engine.removeListener(this@RemoteLink) }
    }

    /** Engine ticks every second during a block; the push loop debounces. */
    override fun onStatus(status: Map<String, Any?>) { kick.trySend(false) }
    override fun onHud() { hudFast = true; kick.trySend(false) }
    override fun onEvent(event: Map<String, Any?>) { if (event["type"] == "card") dataDirty = true; kick.trySend(false) }

    private fun changed() {
        scope.launch(Dispatchers.Main.immediate) { engine.emitStatus() }
        FocusService.recheck()
    }

    // -- pairing (blocking: call from Dispatchers.IO) --

    fun pair(): Map<String, Any?> {
        if (!HintClient.isConfigured) return mapOf("ok" to false, "error" to "The server isn't configured.")
        val body = JSONObject()
            .put("device_id", devId.ifEmpty { JSONObject.NULL })
            .put("phone_secret", secret.ifEmpty { JSONObject.NULL })
        val (code, text) = request("POST", "/api/r/pair", BuildConfig.API_TOKEN, null, body.toString())
        if (code != 200) return mapOf("ok" to false, "error" to when (code) {
            -1 -> "No connection to the FocusLens server."
            401, 403 -> "The server refused access."
            else -> "Server error ($code)."
        })
        val j = runCatching { JSONObject(text) }.getOrNull()
        val id = j?.optString("device_id").orEmpty()
        val sec = j?.optString("phone_secret").orEmpty()
        if (id.isEmpty() || sec.isEmpty()) return mapOf("ok" to false, "error" to "Unexpected answer from the server.")
        Integrations.setSecrets(KEY_ID to id, KEY_SECRET to sec)
        devId = id; secret = sec
        lastError = null
        start()
        FocusService.standby(app)
        changed()
        return mapOf(
            "ok" to true, "code" to j?.optString("code"), "expiresIn" to j?.optInt("expires_in", 600),
            "webUrl" to j?.optString("web_url"), "installUrl" to j?.optString("install_url"),
        )
    }

    /** Tells the server (best effort), forgets the credentials, stops standby. */
    fun unpair(): Map<String, Any?> {
        if (enabled) post("/api/r/unpair", "{}")
        forget(null)
        return mapOf("ok" to true)
    }

    private fun forget(reason: String?) {
        Integrations.setSecrets(KEY_ID to "", KEY_SECRET to "")
        devId = ""; secret = ""
        lastError = reason
        reviewStatus = "idle"; reviewCards = emptyList(); reviewError = null
        stop()
        changed()
    }

    // -- polling --

    private suspend fun pollLoop() {
        var attempt = 0
        var fastUntil = 0L
        while (currentCoroutineContext().isActive && enabled) {
            val (code, text) = request("GET", "/api/r/poll", secret, devId, null)
            if (code == 401) return forget("The server removed this phone. Add FocusLens to the glasses menu again.")
            val wasConnected = connected
            if (code == 200) {
                attempt = 0
                connected = true
                lastError = null
                if (!wasConnected) { engine.log("remote", "link up"); changed(); kick.trySend(true) }
                val a = runCatching { JSONObject(text).optJSONArray("cmds") }.getOrNull()
                if (a != null) for (i in 0 until a.length()) a.optJSONObject(i)?.let { RemoteProto.parse(it.toString()) }?.let { cmds.trySend(it) }
                // Someone is using the glasses UI: poll fast for a bit so gestures feel responsive.
                if ((a?.length() ?: 0) > 0) fastUntil = SystemClock.elapsedRealtime() + FAST_WINDOW_MS
                delay(when {
                    SystemClock.elapsedRealtime() < fastUntil -> POLL_FAST_MS
                    engine.isRunning -> POLL_RUNNING_MS
                    else -> POLL_IDLE_MS
                })
            } else {
                connected = false
                if (wasConnected) changed()
                lastError = if (code == -1) "No connection to the server" else "Server error ($code)"
                engine.log("remote", "link down ($code), retry in ${BACKOFF_S[minOf(attempt, BACKOFF_S.size - 1)]} s")
                changed()
                delay(BACKOFF_S[minOf(attempt++, BACKOFF_S.size - 1)] * 1_000L)
            }
        }
    }

    // -- commands --

    /** What exec produced for a `reply` command: a ready text, or a deferred hint to wait for (help). */
    private class Reply { var text: String? = null; var wait: CompletableDeferred<String?>? = null }

    private suspend fun handle(c: RemoteCmd) {
        if (c.type == "web_seen") { // no ack, no log: it is a heartbeat
            if (RemoteProto.validate(c.type, c.args) == null) main { engine.onWebSeen(c.args.getBoolean("visible")) }
            return
        }
        val rep = Reply()
        val err = RemoteProto.validate(c.type, c.args) ?: try {
            exec(c, rep)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            "failed"
        }
        engine.log("remote", "command ${c.type}: ${err ?: "ok"}") // never the arguments
        synchronized(acks) {
            acks.addLast(JSONObject().put("id", c.id).put("ok", err == null).put("error", err ?: JSONObject.NULL))
            while (acks.size > 10) acks.removeFirst()
        }
        dataDirty = true
        kick.trySend(true)
        if (c.reply) scope.launch { sendReply(c, err, rep) } // help waits for its hint: must not block the next commands
    }

    private suspend fun sendReply(c: RemoteCmd, err: String?, r: Reply) {
        var ok = err == null
        val text = when {
            err != null -> err.replaceFirstChar { it.uppercase() } + "."
            c.type == "help" -> (withTimeoutOrNull(HELP_WAIT_MS) { r.wait?.await() })
                .let { if (it.isNullOrBlank()) { ok = false; Strings.helpFailed } else it }
            c.type == "start_focus" -> Strings.focusStarted
            c.type == "stop_focus" -> Strings.focusStopped
            r.text != null -> r.text!!
            else -> "Done."
        }
        val body = JSONObject().put("id", c.id).put("ok", ok).put("text", RemoteProto.clip(text, 500)).toString()
        if (post("/api/r/res", body) != 204) { delay(1_000); post("/api/r/res", body) }
    }

    private suspend fun <T> main(f: () -> T): T = withContext(Dispatchers.Main.immediate) { f() }

    private suspend fun exec(c: RemoteCmd, rep: Reply): String? {
        val a = c.args
        return when (c.type) {
            "start_focus" -> main {
                when {
                    engine.isRunning -> null
                    !engine.glassesAvailable() -> "glasses not connected"
                    else -> {
                        engine.startFocus(buildMap {
                            if (a.has("mode")) put("mode", a.getString("mode"))
                            put("goal", a.optString("goal", ""))
                        })
                        null
                    }
                }
            }
            "stop_focus" -> main { engine.stopFocus("glasses menu"); null }
            "help" -> main {
                if (!engine.isRunning) "no focus block running"
                else { if (c.reply) rep.wait = engine.helpForResult("glasses menu") else engine.help("glasses menu"); null }
            }
            "bookmark" -> main {
                if (!engine.isRunning) "no focus block running"
                else { engine.bookmark("glasses menu"); rep.text = Strings.bookmarked; null }
            }
            "add_note" -> {
                val t = a.getString("text").trim()
                engine.inbox.add(t, "meta_ai")
                rep.text = Strings.noteSaved(t)
                null
            }
            "last_hint" -> { rep.text = main { engine.lastHintText() } ?: Strings.noHintYet; null }
            "next" -> main { if (!engine.isRunning) "no focus block running" else { engine.next(); null } }
            "reconnect" -> main { engine.reconnectGlasses(); null }
            "inbox_sync" -> Integrations.syncInbox(engine.inbox).also { if (it.retry) Integrations.kickSync() }.error
            "card_reviewed" -> { engine.cards.update(a.getString("id")) { it.put("reviewed", a.getBoolean("reviewed")) }; null }
            "card_delete" -> { engine.cards.delete(a.getString("id")); null } // same as FlutterBridge deleteCard, but in order
            "imu_label" -> main { engine.setImuLabel(a.getString("label").takeIf { it != "none" }); null }
            "pause_mic" -> main { if (!engine.isRunning) "no focus block running" else { engine.pauseMic(); null } }
            "resume_mic" -> main { if (!engine.isRunning) "no focus block running" else { engine.resumeMic(); null } }
            "set_config" -> main { engine.updateConfig(RemoteProto.configPatch(a)); null }
            "inbox_done" -> { engine.inbox.setDone(a.getString("id"), a.getBoolean("done")); null }
            "inbox_delete" -> { engine.inbox.delete(a.getString("id")); null }
            "review_load" -> { startReview(); null }
            "gesture" -> main { engine.onGesture(Gesture.valueOf(a.getString("g").uppercase()), fromWeb = true); null }
            else -> null // refresh: the view publish after the ack is the answer
        }
    }

    private fun startReview() {
        if (reviewStatus == "loading") return
        reviewStatus = "loading"; reviewError = null
        kick.trySend(true)
        scope.launch {
            val items = JSONArray()
            for (c in todayCards().takeLast(20)) {
                val info = c.optJSONObject("card") ?: continue
                items.put(JSONObject().put("title", info.optString("title")).put("summary", info.optString("summary"))
                    .put("correction", info.optString("correction")).put("mistake", c.optString("mistake")))
            }
            if (items.length() == 0) {
                reviewCards = emptyList(); reviewError = "No cards to review yet."; reviewStatus = "error"
            } else {
                val r = Integrations.review(items, engine.config.language)
                val a = r.data as? JSONArray
                reviewCards = if (a == null) emptyList() else (0 until a.length()).mapNotNull { a.optJSONObject(it) }
                    .map { RemoteProto.clip(it.optString("q"), 300) to RemoteProto.clip(it.optString("a"), 400) }.take(20)
                reviewError = r.error
                reviewStatus = if (r.ok) "ready" else "error"
            }
            kick.trySend(true)
        }
    }

    // -- state --

    private suspend fun pushLoop() {
        var last = ""
        var lastAt = 0L
        while (true) {
            val now = withTimeoutOrNull(PUSH_EVERY_MS) { kick.receive() }
            if (!connected) continue
            if (now != true) delay(if (hudFast) HUD_DEBOUNCE_MS else DEBOUNCE_MS) // collapse bursts
            hudFast = false
            val json = runCatching { buildState() }.getOrNull() ?: continue
            val t = SystemClock.elapsedRealtime()
            if (now != true && json == last && t - lastAt < PUSH_EVERY_MS - 1_000) continue
            if (post("/api/r/view", json) == 204) { last = json; lastAt = t }
        }
    }

    private fun todayCards(): List<JSONObject> {
        val today = LocalDate.now().toString()
        return engine.cards.ids().filter { it.startsWith(today) }.mapNotNull { engine.cards.read(it) }
            .filter { it.optString("status") == "done" }
    }

    private fun data(): Pair<JSONArray, JSONArray> {
        val t = SystemClock.elapsedRealtime()
        dataJson?.takeIf { !dataDirty && t - dataAt < 10_000 }?.let { return it }
        val inbox = JSONArray()
        val items = engine.inbox.list().let { a -> (0 until a.length()).map { a.getJSONObject(it) } }
            .sortedByDescending { it.optString("createdAt") }.sortedBy { it.optBoolean("done") }.take(20)
        for (i in items) {
            val synced = i.optJSONObject("synced")?.let { s -> s.keys().asSequence().any { s.optBoolean(it) } } ?: false
            inbox.put(JSONObject().put("id", i.optString("id")).put("text", RemoteProto.clip(i.optString("text"), 300))
                .put("done", i.optBoolean("done")).put("createdAt", i.optString("createdAt")).put("synced", synced))
        }
        val cards = JSONArray()
        var n = 0
        for (id in engine.cards.ids().asReversed()) {
            if (n >= 12) break
            val c = engine.cards.read(id)?.takeIf { it.optString("status") == "done" } ?: continue
            val info = c.optJSONObject("card") ?: JSONObject()
            val st = c.optJSONArray("steps")
            val last = st?.let { if (it.length() > 0) it.getJSONObject(it.length() - 1).optString("text") else null } ?: continue
            val quest = c.optJSONObject("quest")
            val qs = quest?.optJSONArray("steps")
            val qd = quest?.optJSONArray("done")
            val steps = JSONArray()
            if (qs != null) for (k in 0 until minOf(qs.length(), 6)) {
                steps.put(JSONObject().put("text", RemoteProto.clip(qs.optString(k), 160)).put("done", qd?.optBoolean(k) ?: false))
            }
            val at = c.optString("createdAt")
            n++
            cards.put(JSONObject().put("id", c.optString("id"))
                .put("title", RemoteProto.clip(info.optString("title").ifBlank { last }, 80))
                .put("step", RemoteProto.clip(last, 200))
                .put("situation", RemoteProto.clip(info.optString("situation"), 120))
                .put("day", at.take(10))
                .put("at", if (at.length >= 16) at.substring(11, 16) else "")
                .put("correction", RemoteProto.clip(info.optString("correction"), 200))
                .put("summary", RemoteProto.clip(info.optString("summary"), 300))
                .put("formula", RemoteProto.clip(c.optString("formula"), 300))
                .put("reviewed", c.optBoolean("reviewed"))
                .put("steps", steps))
        }
        dataDirty = false; dataAt = t
        return (inbox to cards).also { dataJson = it }
    }

    private fun obj(vararg kv: Pair<String, Any?>) = JSONObject().also { o -> for ((k, v) in kv) o.put(k, v ?: JSONObject.NULL) }

    private suspend fun buildState(): String {
        val st = main { engine.status() }
        val focus = st["focus"] as Map<*, *>
        val hint = st["hint"] as Map<*, *>
        val gl = st["glasses"] as Map<*, *>
        val cfg = st["config"] as Map<*, *>
        val (inbox, cards) = data()
        val quest = focus["quest"] as? Map<*, *>
        val review = obj("status" to reviewStatus, "error" to reviewError)
            .put("cards", JSONArray().also { a -> reviewCards.forEach { a.put(obj("q" to it.first, "a" to it.second)) } })
        val o = obj(
            "v" to 2,
            "focus" to obj(
                "running" to focus["running"], "mode" to focus["mode"], "situation" to focus["situation"], "goal" to RemoteProto.clip(focus["goal"] as? String, 200),
                "startedAt" to focus["startedAt"], "hintCount" to focus["hintCount"], "micPaused" to focus["micPaused"],
                "phase" to hint["phase"], "lastHint" to (hint["lastText"] as? String)?.let { RemoteProto.clip(it, 300) },
                "audioSource" to focus["audioSource"], "attention" to focus["attention"],
                "quest" to quest?.let { q ->
                    obj("title" to RemoteProto.clip(q["title"] as? String, 80), "index" to q["index"])
                        .put("steps", JSONArray().also { a -> (q["steps"] as? List<*>)?.take(8)?.forEach { a.put(RemoteProto.clip(it as? String, 160)) } })
                },
            ),
            "glasses" to obj("connected" to gl["connected"], "name" to gl["name"], "battery" to gl["battery"], "displayActive" to gl["hasDisplay"]),
            "config" to obj(*listOf("mode", "language", "bufferSec", "hudTimeoutSec", "keepAudio", "audioSource", "nudges", "wakeWord",
                "readAloud", "dozeSound", "collect", "retentionDays").map { it to cfg[it] }.toTypedArray()),
            "inbox" to inbox, "cards" to cards, "review" to review,
            "imu" to obj("collect" to cfg["collect"], "label" to focus["imuLabel"]),
            "integrations" to obj("todoist" to Integrations.todoistConfigured),
        )
        o.put("hud", hudJson())
        val state = RemoteProto.trimState(o, RemoteProto.MAX_STATE_BYTES - 4_096)
        val acksJson = synchronized(acks) { JSONArray(acks.toList()) }
        return JSONObject().put("state", state).put("acks", acksJson).toString()
    }

    // -- HUD view + crops --

    private fun hudJson(): JSONObject {
        val screen = engine.hudScreen ?: HudScreen.Idle(compact = false, paused = false)
        val bmp = screen.cropBitmap()
        return screen.toJson(bmp?.let { cropHash(it) }, engine.attention)
    }

    /** Hash of the uploaded crop, or null while it is still uploading (the view is republished when done). */
    private fun cropHash(bmp: Bitmap): String? {
        synchronized(cropHashes) {
            cropHashes[bmp]?.let { return it }
            if (!cropUploading.add(bmp)) return null
        }
        scope.launch {
            val hash = runCatching { uploadCrop(bmp) }.getOrNull()
            synchronized(cropHashes) {
                if (hash != null) cropHashes[bmp] = hash
                cropUploading.remove(bmp)
            }
            if (hash != null) kick.trySend(true)
        }
        return null
    }

    private fun uploadCrop(bmp: Bitmap): String? {
        if (bmp.isRecycled) return null
        val scale = minOf(1f, MAX_CROP_SIDE / maxOf(bmp.width, bmp.height).toFloat())
        val src = if (scale < 1f) Bitmap.createScaledBitmap(bmp, maxOf(1, (bmp.width * scale).toInt()), maxOf(1, (bmp.height * scale).toInt()), true) else bmp
        var bytes = ByteArray(0)
        for (q in intArrayOf(80, 65, 50, 35)) {
            val out = ByteArrayOutputStream()
            src.compress(Bitmap.CompressFormat.JPEG, q, out)
            bytes = out.toByteArray()
            if (bytes.size <= MAX_CROP_BYTES) break
        }
        if (src !== bmp) src.recycle()
        if (bytes.size > MAX_CROP_BYTES) return null
        val (code, text) = request("POST", "/api/r/img", secret, devId, null, bytes, "image/jpeg")
        if (code == 401) forget("The server removed this phone. Add FocusLens to the glasses menu again.")
        if (code != 200) return null
        return runCatching { JSONObject(text).optString("hash") }.getOrNull()?.takeIf { it.isNotEmpty() }
    }

    // -- HTTP (blocking) --

    private fun open(method: String, path: String, bearer: String, deviceId: String?): HttpURLConnection {
        val c = URL(HintClient.baseUrl + path).openConnection() as HttpURLConnection
        c.requestMethod = method
        c.connectTimeout = 8_000
        c.readTimeout = 15_000
        c.setRequestProperty("Authorization", "Bearer $bearer")
        if (deviceId != null) c.setRequestProperty("X-Device-Id", deviceId)
        return c
    }

    /** (status, body) or (-1, "") on network errors. [body] null = no request body (GET); [bytes] overrides [body]. */
    private fun request(
        method: String, path: String, bearer: String, deviceId: String?, body: String?,
        bytes: ByteArray? = null, contentType: String = "application/json",
    ): Pair<Int, String> {
        val c = try { open(method, path, bearer, deviceId) } catch (e: IOException) { return -1 to "" }
        return try {
            val payload = bytes ?: body?.toByteArray(Charsets.UTF_8)
            if (payload != null) {
                c.doOutput = true
                c.setFixedLengthStreamingMode(payload.size)
                c.setRequestProperty("Content-Type", contentType)
                c.outputStream.use { it.write(payload) }
            }
            val code = c.responseCode
            code to (if (code in 200..299 && code != 204) c.inputStream.bufferedReader().readText() else "")
        } catch (e: IOException) {
            -1 to ""
        } finally {
            c.disconnect()
        }
    }

    /** Phone-authenticated POST. A 401 means the server dropped this phone: credentials are wiped. */
    private fun post(path: String, body: String): Int {
        if (!enabled) return -1
        val code = request("POST", path, secret, devId, body).first
        if (code == 401) forget("The server removed this phone. Add FocusLens to the glasses menu again.")
        return code
    }

    private companion object {
        const val KEY_ID = "remoteDeviceId"
        const val HELP_WAIT_MS = 20_000L
        const val KEY_SECRET = "remotePhoneSecret"
        val BACKOFF_S = longArrayOf(1, 2, 5, 10, 30)
        const val DEBOUNCE_MS = 1_000L
        const val HUD_DEBOUNCE_MS = 300L
        const val PUSH_EVERY_MS = 20_000L // heartbeat: the web app calls the phone offline after 60 s without a view
        const val POLL_RUNNING_MS = 2_000L
        const val POLL_IDLE_MS = 10_000L
        const val POLL_FAST_MS = 500L // ponytail: polling; FCM push replaces this (V2_DIRECTION.md Fáze 5)
        const val FAST_WINDOW_MS = 10_000L
        const val MAX_CROP_SIDE = 544
        const val MAX_CROP_BYTES = 200_000
    }
}
