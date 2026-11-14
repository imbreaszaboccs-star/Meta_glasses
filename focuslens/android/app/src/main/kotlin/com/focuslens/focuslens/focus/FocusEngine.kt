package com.focuslens.focuslens.focus

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.meta.wearable.dat.core.Wearables
import com.meta.wearable.dat.core.types.Device
import com.meta.wearable.dat.core.types.DeviceIdentifier
import com.meta.wearable.dat.core.types.LinkState
import com.meta.wearable.dat.core.types.Permission
import com.meta.wearable.dat.core.types.PermissionStatus
import com.meta.wearable.dat.core.types.RegistrationState
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

interface EngineListener {
    fun onStatus(status: Map<String, Any?>) {}
    fun onEvent(event: Map<String, Any?>) {}
}

/**
 * The whole pinch → hint loop, process-wide (lives in [com.focuslens.focuslens.FocusLensApp]).
 * [FocusService] only keeps the process and the microphone alive; the Flutter UI only observes.
 * All state is touched on the main thread.
 */
class FocusEngine(private val app: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val prefs = app.getSharedPreferences("focus_engine", Context.MODE_PRIVATE)
    private val listeners = CopyOnWriteArrayList<EngineListener>()
    val cards = CardStore(app.filesDir)
    private val speaker = Speaker(app)

    var config = FocusConfig.load(prefs)
        private set

    // -- SDK / glasses link --
    private var sdkReady = false
    private var registration: RegistrationState = RegistrationState.UNAVAILABLE
    private val devices = mutableMapOf<DeviceIdentifier, Device>()
    private val watched = mutableSetOf<DeviceIdentifier>()
    private var cameraPermission = "unknown" // granted | denied | unknown
    private var updateRequired = false

    // -- focus block --
    private class Run(val sessionId: String, val mode: String, val startedAt: Long) {
        var hintCount = 0
    }
    private var run: Run? = null
    private var mic: MicRing? = null
    private var micPaused = false
    private var micError: String? = null
    private var glasses: GlassesSession? = null
    private var glassesState = GlassesState()
    private var glassesEndedByUser = false
    private var reconnectAttempts = 0
    private var reconnectJob: Job? = null
    private var tickJob: Job? = null
    private var testMode = false
    private var batteryWarned = false
    private var lastEnded: Map<String, Any?>? = null

    // -- hint state machine --
    enum class Phase { IDLE, THINKING, SHOWN, MESSAGE }
    private data class Shown(val text: String, val cardId: String)
    private var phase = Phase.IDLE
    private val history = ArrayList<Shown>() // last 5 hints, oldest first
    private var viewIndex = -1
    private var hintJob: Job? = null
    private var hideJob: Job? = null
    private var lastHelpAt = 0L
    private var currentScreen: HudScreen? = null
    private var lastMessage: String? = null

    // -- server + diagnostics --
    private var serverOk: Boolean? = null
    private var serverError: String? = null
    private val diag = ArrayDeque<Map<String, Any?>>()
    private val latencies = ArrayDeque<Map<String, Any?>>()

    init {
        cards.applyRetention(config.retentionDays)
    }

    fun addListener(l: EngineListener) { listeners += l; l.onStatus(status()) }
    fun removeListener(l: EngineListener) { listeners -= l }

    // ------------------------------------------------------------------ logging / events

    fun log(kind: String, text: String) {
        Log.i(TAG, "[$kind] $text")
        val e = mapOf("kind" to kind, "text" to text, "ts" to System.currentTimeMillis())
        scope.launch {
            diag.addLast(e)
            while (diag.size > 400) diag.removeFirst()
            event("diag", e)
        }
    }

    private fun event(type: String, data: Map<String, Any?> = emptyMap()) {
        val e = data + ("type" to type)
        listeners.forEach { it.onEvent(e) }
    }

    fun emitStatus() {
        val s = status()
        listeners.forEach { it.onStatus(s) }
    }

    // ------------------------------------------------------------------ SDK

    fun hasPermission(p: String) = ContextCompat.checkSelfPermission(app, p) == PackageManager.PERMISSION_GRANTED

    /** Needs BLUETOOTH_CONNECT. Safe to call repeatedly. */
    fun initSdk() {
        if (sdkReady) return
        if (!hasPermission(Manifest.permission.BLUETOOTH_CONNECT)) return log("info", "SDK init waits for Bluetooth permission")
        Wearables.initialize(app).fold(
            onSuccess = {
                sdkReady = true
                log("info", "Wearables.initialize OK (devMode=${Wearables.isDevMode})")
                monitor()
                emitStatus()
            },
            onFailure = { e, _ -> log("error", "initialize: ${e.description}") },
        )
    }

    private fun monitor() {
        scope.launch {
            Wearables.registrationState.collect {
                registration = it
                log("registration", "$it")
                if (it == RegistrationState.REGISTERED) refreshCameraPermission()
                emitStatus()
            }
        }
        scope.launch {
            Wearables.registrationErrorStream.collect { log("error", "registration: ${it.getLocalizedDescription(app)}") }
        }
        scope.launch {
            Wearables.devices.collect { ids ->
                devices.keys.retainAll(ids)
                for (id in ids - watched) {
                    watched += id
                    launch {
                        var prev: LinkState? = null
                        Wearables.devicesMetadata[id]?.collect { d ->
                            devices[id] = d
                            if (d.linkState == LinkState.CONNECTED && prev != LinkState.CONNECTED) onDeviceConnected()
                            prev = d.linkState
                            checkBattery(d)
                            emitStatus()
                        }
                    }
                }
                emitStatus()
            }
        }
    }

    private fun connectedDevice(): Pair<DeviceIdentifier, Device>? =
        devices.entries.firstOrNull { it.value.linkState == LinkState.CONNECTED }?.toPair()

    private fun onDeviceConnected() {
        log("device", "glasses connected")
        reconnectAttempts = 0
        if (registration == RegistrationState.REGISTERED) scope.launch { refreshCameraPermission() }
        if (run != null && glasses == null && !glassesEndedByUser) attachGlasses()
    }

    suspend fun refreshCameraPermission(): String {
        if (!sdkReady || connectedDevice() == null) return cameraPermission
        cameraPermission = Wearables.checkPermissionStatus(Permission.CAMERA).fold(
            onSuccess = { if (it == PermissionStatus.Granted) "granted" else "denied" },
            onFailure = { e, _ -> log("error", "checkPermissionStatus: ${e.description}"); cameraPermission },
        )
        emitStatus()
        return cameraPermission
    }

    fun onCameraPermissionResult(status: PermissionStatus) {
        cameraPermission = if (status == PermissionStatus.Granted) "granted" else "denied"
        log("camera", "glasses camera permission: $status")
        emitStatus()
    }

    private fun checkBattery(d: Device) {
        val low = d.batteryLevel in 1..10
        if (low && !batteryWarned && run != null && phase == Phase.IDLE) {
            batteryWarned = true
            showMessage(Strings.batteryLow, null, autoHideMs = 6_000)
        }
        if (!low) batteryWarned = false
    }

    // ------------------------------------------------------------------ focus block

    fun startFocus(cfg: Map<*, *>?): String? {
        updateConfig(cfg)
        if (run != null) return null
        stopGestureTest()
        val r = Run(UUID.randomUUID().toString(), config.mode, System.currentTimeMillis())
        run = r
        glassesEndedByUser = false
        reconnectAttempts = 0
        batteryWarned = false
        phase = Phase.IDLE
        history.clear(); viewIndex = -1
        prefs.edit().putInt("blocksStarted", prefs.getInt("blocksStarted", 0) + 1).apply()
        writeSession(r, ended = false)

        FocusService.start(app)
        startMic()
        attachGlasses()
        speaker.warmUp()
        tickJob = scope.launch { while (isActive) { delay(1_000); emitStatus() } }
        log("focus", "started ${r.mode}")
        emitStatus()
        return null
    }

    fun stopFocus(reason: String = "user") {
        val r = run ?: return
        hintJob?.cancel(); hideJob?.cancel(); reconnectJob?.cancel(); tickJob?.cancel()
        speaker.stop()
        glasses?.stop(); glasses = null
        glassesState = GlassesState()
        mic?.stop(); mic = null
        micPaused = false
        run = null
        phase = Phase.IDLE
        history.clear(); viewIndex = -1
        currentScreen = null
        writeSession(r, ended = true)
        Notifications.cancelInterrupted(app)
        FocusService.stop(app)
        val summary = mapOf(
            "sessionId" to r.sessionId, "mode" to r.mode, "startedAt" to r.startedAt,
            "endedAt" to System.currentTimeMillis(), "hintCount" to r.hintCount, "reason" to reason,
        )
        lastEnded = summary
        log("focus", "ended ($reason), ${r.hintCount} hints")
        event("focusEnded", summary)
        emitStatus()
    }

    private fun writeSession(r: Run, ended: Boolean) {
        val j = JSONObject().put("id", r.sessionId).put("mode", r.mode)
            .put("startedAt", r.startedAt).put("hintCount", r.hintCount)
        if (ended) j.put("endedAt", System.currentTimeMillis())
        scope.launch(Dispatchers.IO) { cards.writeSession(j) }
    }

    fun updateConfig(cfg: Map<*, *>?) {
        val old = config
        config = config.merge(cfg)
        config.save(prefs)
        if (old.retentionDays != config.retentionDays) scope.launch(Dispatchers.IO) { cards.applyRetention(config.retentionDays) }
        // Buffer length changed mid-block → restart the mic (the old buffer is dropped).
        if (run != null && !micPaused && old.bufferSec != config.bufferSec) { mic?.stop(); mic = null; startMic() }
        if (old.language != config.language && phase == Phase.IDLE) showIdle()
        emitStatus()
    }

    fun setMode(mode: String) = updateConfig(mapOf("mode" to mode))

    private fun startMic() {
        if (!hasPermission(Manifest.permission.RECORD_AUDIO)) {
            micError = "permission"
            return log("error", "mic: no RECORD_AUDIO permission")
        }
        val m = MicRing(config.bufferSec)
        if (m.start()) { mic = m; micError = null } else { micError = "unavailable"; log("error", "mic could not be opened") }
    }

    fun pauseMic() {
        if (run == null || micPaused) return
        mic?.stop(); mic = null
        micPaused = true
        log("focus", "mic paused (buffer wiped)")
        if (phase == Phase.IDLE) showIdle()
        emitStatus()
    }

    fun resumeMic() {
        if (run == null || !micPaused) return
        micPaused = false
        startMic()
        log("focus", "mic resumed")
        if (phase == Phase.IDLE) showIdle()
        emitStatus()
    }

    // ------------------------------------------------------------------ glasses session

    private fun attachGlasses(withCamera: Boolean = true) {
        if (glasses?.isActive == true) return
        val (id, device) = connectedDevice() ?: return log("info", "no glasses connected, phone-only mode")
        if (registration != RegistrationState.REGISTERED) return log("info", "not registered with Meta AI, phone-only mode")
        scope.launch {
            val cam = withCamera && refreshCameraPermission() == "granted"
            if (glasses?.isActive == true) return@launch
            lateinit var gs: GlassesSession
            gs = GlassesSession(
                scope = scope,
                log = ::log,
                onState = { st -> onGlassesState(gs, st) },
                onGesture = { g -> onGesture(g) },
                onDisplayReady = { if (glasses === gs) rerender() },
                onUpdateRequired = { updateRequired = true; event("updateRequired"); emitStatus() },
            )
            glasses = gs
            gs.start(id, withDisplay = device.isDisplayCapable(), withCamera = cam)
        }
    }

    private fun onGlassesState(gs: GlassesSession, st: GlassesState) {
        if (glasses !== gs) return emitStatus()
        glassesState = st
        when (st.phase) {
            GlassesPhase.READY -> { reconnectAttempts = 0; Notifications.cancelInterrupted(app) }
            GlassesPhase.ENDED_BY_USER -> {
                glasses = null
                if (testMode) testMode = false
                else { glassesEndedByUser = true; stopFocus("glasses") }
            }
            GlassesPhase.INTERRUPTED, GlassesPhase.ERROR -> {
                glasses = null
                if (testMode) testMode = false else scheduleReconnect()
            }
            else -> Unit
        }
        emitStatus()
    }

    private fun scheduleReconnect() {
        if (run == null || glassesEndedByUser) return
        if (reconnectAttempts >= RECONNECT_DELAYS.size) {
            log("session", "reconnect gave up after $reconnectAttempts attempts")
            Notifications.showInterrupted(app)
            return
        }
        val wait = RECONNECT_DELAYS[reconnectAttempts++]
        log("session", "reconnecting in $wait ms (attempt $reconnectAttempts)")
        reconnectJob?.cancel()
        reconnectJob = scope.launch { delay(wait); if (run != null && glasses == null) attachGlasses() }
    }

    /** From the "reconnect" notification / UI. */
    fun reconnectGlasses() {
        Notifications.cancelInterrupted(app)
        reconnectAttempts = 0
        glassesEndedByUser = false
        if (run != null) attachGlasses()
    }

    /** Onboarding: open a session without camera/mic so the user can try a pinch. */
    fun startGestureTest() {
        if (run != null || glasses != null) return
        testMode = true
        attachGlasses(withCamera = false)
    }

    fun stopGestureTest() {
        if (!testMode) return
        testMode = false
        glasses?.stop(); glasses = null
        glassesState = GlassesState()
        emitStatus()
    }

    // ------------------------------------------------------------------ gestures

    private fun onGesture(g: Gesture) {
        event("gesture", mapOf("name" to g.name))
        if (testMode) {
            if (g == Gesture.SELECT) showMessage(Strings.itWorks, null, autoHideMs = 4_000)
            return
        }
        if (run == null) return
        when (g) {
            Gesture.SELECT -> when (phase) {
                Phase.IDLE -> help("pinch")
                Phase.THINKING -> showHud(HudScreen.Thinking(Strings.stillThinking))
                Phase.SHOWN, Phase.MESSAGE -> dismiss()
            }
            Gesture.RIGHT -> next()
            Gesture.LEFT -> prev()
            Gesture.BACK -> if (phase == Phase.THINKING) cancelHint() else dismiss()
            Gesture.CAPTURE -> log("input", "capture button ignored (task.md T5.1: may clash with Meta AI capture)")
            Gesture.UP, Gesture.DOWN -> Unit
        }
    }

    /** Pinch, the in-app button or the notification action. */
    fun help(source: String) {
        if (run == null) return log("info", "help ignored: no focus block running")
        val now = SystemClock.elapsedRealtime()
        if (now - lastHelpAt < DEBOUNCE_MS) return log("info", "help ignored (debounce)")
        lastHelpAt = now
        if (phase == Phase.THINKING) return showHud(HudScreen.Thinking(Strings.stillThinking))
        log("hint", "help from $source")
        runHint(chainCardId = null)
    }

    fun next() {
        if (history.isEmpty() || phase == Phase.THINKING) return
        if (phase == Phase.SHOWN && viewIndex < history.lastIndex) {
            viewIndex++
            return showCurrentHint()
        }
        if (phase == Phase.IDLE) return
        runHint(chainCardId = history[viewIndex.coerceIn(0, history.lastIndex)].cardId)
    }

    fun prev() {
        if (history.isEmpty() || phase == Phase.THINKING) return
        viewIndex = if (phase == Phase.IDLE || phase == Phase.MESSAGE) history.lastIndex else (viewIndex - 1).coerceAtLeast(0)
        showCurrentHint()
    }

    fun dismiss() {
        if (phase == Phase.THINKING) return
        hideJob?.cancel()
        speaker.stop()
        phase = Phase.IDLE
        showIdle()
        emitStatus()
    }

    private fun cancelHint() {
        hintJob?.cancel()
        log("hint", "cancelled by user")
        phase = Phase.IDLE
        showIdle()
        emitStatus()
    }

    // ------------------------------------------------------------------ the hint pipeline

    private fun runHint(chainCardId: String?) {
        hintJob?.cancel(); hideJob?.cancel()
        speaker.stop()
        phase = Phase.THINKING
        val g = glasses
        showHud(HudScreen.Thinking(when {
            chainCardId != null -> Strings.nextStepLoading
            g?.hasCamera == true -> Strings.looking
            else -> Strings.listeningOnly
        }))
        emitStatus()

        val cfg = config
        val sessionId = run?.sessionId
        hintJob = scope.launch {
            val t0 = SystemClock.elapsedRealtime()
            var cardId: String? = chainCardId
            var completed = false
            try {
                val photoD = async { g?.capturePhoto() }
                val pcm = mic?.takeIf { it.isListening && it.bufferedSeconds >= 1 }?.snapshot()
                val audioD = async(Dispatchers.Default) {
                    pcm?.let { AudioCodec.encode(it, MicRing.SAMPLE_RATE, app.cacheDir) }
                }
                val photo = photoD.await()
                val tPhoto = SystemClock.elapsedRealtime() - t0
                val audio = audioD.await()
                val tReady = SystemClock.elapsedRealtime() - t0
                if (photo == null && audio == null) {
                    completed = true
                    return@launch showMessage(Strings.nothingToSend, Strings.nothingToSendBody, autoHideMs = 6_000)
                }

                val isNew = chainCardId == null
                val id = chainCardId ?: withContext(Dispatchers.IO) { cards.newId() }
                cardId = id
                val existing = if (isNew) null else withContext(Dispatchers.IO) { cards.read(id) }
                val previous = existing?.optJSONArray("steps")?.let { a -> (0 until a.length()).map { a.getJSONObject(it).optString("text") } } ?: emptyList()
                val photoName = photo?.let { if (isNew) "photo.jpg" else "photo-${previous.size + 1}.jpg" }
                val ts = CardStore.now()

                if (isNew) withContext(Dispatchers.IO) {
                    val c = CardStore.newCard(id, sessionId, cfg.mode, cfg.language)
                    val req = JSONObject().put("ts", ts).put("previous", JSONArray())
                    photo?.let { cards.saveFile(id, "photo.jpg", it); req.put("photo", "photo.jpg") }
                    audio?.let {
                        cards.saveFile(id, "request.${it.ext}", it.bytes)
                        req.put("audio", "request.${it.ext}").put("audioMime", it.mime)
                    }
                    cards.write(id, c.put("request", req))
                } else if (photo != null) withContext(Dispatchers.IO) { cards.saveFile(id, photoName!!, photo) }
                if (isNew) event("card", mapOf("id" to id, "status" to CardStore.STATUS_PENDING))

                val req = HintRequest(photo, audio, cfg.language, cfg.mode, ts, previous, id)
                var res = withContext(Dispatchers.IO) { HintClient.hint(req) }
                if (res.error is HintError.TooLarge && pcm != null) {
                    log("hint", "413, retrying with the last 60 s only")
                    val small = withContext(Dispatchers.Default) { AudioCodec.encode(pcm.takeLast(60 * MicRing.BYTES_PER_SECOND), MicRing.SAMPLE_RATE, app.cacheDir, 16_000) }
                    res = withContext(Dispatchers.IO) { HintClient.hint(HintRequest(photo, small, cfg.language, cfg.mode, ts, previous, id)) }
                }
                val tTotal = SystemClock.elapsedRealtime() - t0
                val timings = JSONObject().put("photoMs", tPhoto).put("encodeMs", tReady).put("aiMs", tTotal - tReady)
                    .put("totalMs", tTotal).put("audioBytes", audio?.bytes?.size ?: 0).put("audioMime", audio?.mime ?: JSONObject.NULL)
                res.ok?.let { timings.put("serverMs", it.serverMs ?: JSONObject.NULL) }
                recordLatency(timings, res.ok != null)
                completed = true

                val ok = res.ok
                if (ok != null) onHintOk(id, isNew, ok, photoName, timings, pcm, audio)
                else onHintFail(id, isNew, res.error!!)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                completed = true
                log("error", "hint pipeline: $e")
                showMessage(Strings.savedLater, null, autoHideMs = 6_000)
            } finally {
                // Back gesture during THINKING: a brand-new card that never got an answer is dropped.
                if (!completed && chainCardId == null) cardId?.let { id -> withContext(kotlinx.coroutines.NonCancellable + Dispatchers.IO) { cards.delete(id) } }
            }
        }
    }

    private fun ByteArray.takeLast(n: Int): ByteArray = if (size <= n) this else copyOfRange(size - n, size)

    private suspend fun onHintOk(
        id: String, isNew: Boolean, res: HintResponse, photoName: String?,
        timings: JSONObject, pcm: ByteArray?, audio: EncodedAudio?,
    ) {
        withContext(Dispatchers.IO) {
            cards.update(id) { c ->
                val step = JSONObject().put("text", res.nextStep).put("at", CardStore.now())
                photoName?.let { step.put("photo", it) }
                c.getJSONArray("steps").put(step)
                c.put("card", if (isNew) res.card else mergeCard(c.optJSONObject("card"), res.card))
                c.put("status", CardStore.STATUS_DONE).remove("error")
                c.put("attempts", c.optInt("attempts") + 1)
                if (isNew) c.put("timings", timings)
                c.remove("request")
            }
        }
        history += Shown(res.nextStep, id)
        while (history.size > HISTORY) history.removeAt(0)
        viewIndex = history.lastIndex
        run?.let { it.hintCount++; writeSession(it, ended = false) }
        serverOk = true; serverError = null
        showCurrentHint()
        event("card", mapOf("id" to id, "status" to CardStore.STATUS_DONE))
        log("hint", "ok in ${timings.optLong("totalMs")} ms")
        speakIfNeeded(res.nextStep)

        // After the HUD is updated: keep the audio on the card according to the setting.
        if (isNew) scope.launch(Dispatchers.IO) { applyAudioPolicy(id, pcm, audio) }
    }

    private fun mergeCard(old: JSONObject?, new: JSONObject): JSONObject {
        if (old == null) return new
        for (k in listOf("summary", "correction", "transcript_tail")) {
            val v = new.optString(k)
            if (v.isNotBlank()) old.put(k, v)
        }
        return old
    }

    private fun applyAudioPolicy(id: String, pcm: ByteArray?, sent: EncodedAudio?) {
        cards.dir(id).listFiles { f -> f.name.startsWith("request.") }?.forEach { it.delete() }
        val keep: EncodedAudio? = when (config.keepAudio) {
            "all" -> sent
            "last60" -> pcm?.let {
                if (it.size <= 60 * MicRing.BYTES_PER_SECOND) sent
                else AudioCodec.encode(it.takeLast(60 * MicRing.BYTES_PER_SECOND), MicRing.SAMPLE_RATE, app.cacheDir)
            }
            else -> null
        }
        if (keep != null) {
            cards.saveFile(id, "audio.${keep.ext}", keep.bytes)
            cards.update(id) { it.put("audio", "audio.${keep.ext}") }
        }
        scope.launch { event("card", mapOf("id" to id, "status" to CardStore.STATUS_DONE)) }
    }

    private suspend fun onHintFail(id: String, isNew: Boolean, err: HintError) {
        log("hint", "failed: ${err.message}")
        if (err.retryable) { serverOk = false; serverError = err.message }
        if (!isNew) return showMessage(Strings.nextFailed, Strings.nextFailedBody, autoHideMs = 8_000)

        val status = if (err.retryable) CardStore.STATUS_PENDING else CardStore.STATUS_FAILED
        withContext(Dispatchers.IO) {
            cards.update(id) { it.put("status", status).put("error", err.message).put("attempts", it.optInt("attempts") + 1) }
        }
        event("card", mapOf("id" to id, "status" to status))
        when (err) {
            is HintError.Auth, is HintError.NotConfigured -> { serverOk = false; serverError = err.message; showMessage(Strings.serverRefused, Strings.serverRefusedBody, autoHideMs = 8_000) }
            is HintError.NoInput -> showMessage(Strings.nothingToSend, null, autoHideMs = 6_000)
            else -> { RetryWorker.enqueue(app); showMessage(Strings.savedLater, null, autoHideMs = 6_000) }
        }
    }

    private fun speakIfNeeded(text: String) {
        val g = glasses
        val glassesWithoutDisplay = g?.isActive == true && !g.hasDisplay
        if (config.readAloud || glassesWithoutDisplay) scope.launch { speaker.speak(text, config.language) }
    }

    private fun recordLatency(t: JSONObject, ok: Boolean) {
        val m = mutableMapOf<String, Any?>("at" to System.currentTimeMillis(), "ok" to ok)
        t.keys().forEach { k -> m[k] = t.opt(k).takeUnless { it == JSONObject.NULL } }
        latencies.addLast(m)
        while (latencies.size > 20) latencies.removeFirst()
    }

    // ------------------------------------------------------------------ HUD

    private fun showCurrentHint() {
        val h = history.getOrNull(viewIndex) ?: return
        phase = Phase.SHOWN
        lastMessage = null
        showHud(HudScreen.Hint(h.text, viewIndex + 1, history.size))
        scheduleHide(config.hudTimeoutSec * 1_000L)
        emitStatus()
    }

    private fun showMessage(title: String, body: String?, autoHideMs: Long) {
        phase = Phase.MESSAGE
        lastMessage = title
        showHud(HudScreen.Message(title, body))
        scheduleHide(autoHideMs)
        emitStatus()
    }

    private fun scheduleHide(ms: Long) {
        hideJob?.cancel()
        if (ms <= 0) return
        hideJob = scope.launch {
            delay(ms)
            if (phase == Phase.SHOWN || phase == Phase.MESSAGE) { phase = Phase.IDLE; showIdle(); emitStatus() }
        }
    }

    private fun showIdle() {
        if (testMode) return showHud(HudScreen.Idle(compact = false, paused = false))
        showHud(HudScreen.Idle(compact = prefs.getInt("blocksStarted", 0) > 3, paused = micPaused))
    }

    private fun showHud(screen: HudScreen) {
        currentScreen = screen
        glasses?.show(Hud.render(screen))
    }

    private fun rerender() {
        val s = currentScreen
        if (s == null || phase == Phase.IDLE) showIdle() else showHud(s)
    }

    // ------------------------------------------------------------------ cards (UI requests)

    fun deleteCard(id: String) = scope.launch(Dispatchers.IO) { cards.delete(id) }

    fun setReviewed(id: String, reviewed: Boolean) = scope.launch(Dispatchers.IO) {
        cards.update(id) { it.put("reviewed", reviewed) }
    }

    fun retryCard(id: String) = scope.launch(Dispatchers.IO) {
        cards.update(id) { if (it.has("request")) it.put("status", CardStore.STATUS_PENDING).put("attempts", 0) }
        RetryWorker.enqueue(app, now = true)
    }

    fun retryPending() = RetryWorker.enqueue(app, now = true)

    /** Card "read aloud" from the phone UI. */
    fun speak(text: String) {
        if (text.isBlank()) return
        scope.launch { speaker.speak(text, config.language) }
    }

    fun stopSpeaking() = speaker.stop()

    fun deleteAllData() {
        if (run != null) stopFocus("reset")
        scope.launch(Dispatchers.IO) { cards.deleteAll() }
    }

    /** Called by [RetryWorker] after a pending card got its answer. */
    fun onCardChanged(id: String, status: String) = scope.launch {
        if (status == CardStore.STATUS_DONE) { serverOk = true; serverError = null }
        event("card", mapOf("id" to id, "status" to status))
        emitStatus()
    }

    fun checkServer() = scope.launch {
        val err = withContext(Dispatchers.IO) { HintClient.health() }
        serverOk = err == null
        serverError = err
        emitStatus()
    }

    fun diagnostics(): Map<String, Any?> = mapOf(
        "log" to diag.toList(),
        "latencies" to latencies.toList(),
        "serverUrl" to HintClient.baseUrl,
        "configured" to HintClient.isConfigured,
        "sdkReady" to sdkReady,
        "devMode" to (sdkReady && Wearables.isDevMode),
    )

    // ------------------------------------------------------------------ status snapshot for the UI

    fun status(): Map<String, Any?> {
        val dev = connectedDevice()?.second ?: devices.values.firstOrNull()
        val pm = app.getSystemService(Context.POWER_SERVICE) as PowerManager
        val g = glasses
        val r = run
        val current = history.getOrNull(viewIndex)
        return mapOf(
            "permissions" to mapOf(
                "mic" to hasPermission(Manifest.permission.RECORD_AUDIO),
                "bluetooth" to hasPermission(Manifest.permission.BLUETOOTH_CONNECT),
                "notifications" to ((Build.VERSION.SDK_INT < 33 || hasPermission(Manifest.permission.POST_NOTIFICATIONS)) &&
                    NotificationManagerCompat.from(app).areNotificationsEnabled()),
                "battery" to pm.isIgnoringBatteryOptimizations(app.packageName),
            ),
            "sdkReady" to sdkReady,
            "registration" to registration.name,
            "glasses" to mapOf(
                "known" to (dev != null),
                "connected" to (dev?.linkState == LinkState.CONNECTED),
                "name" to dev?.name,
                "battery" to dev?.batteryLevel,
                "displayCapable" to (dev?.isDisplayCapable() ?: false),
                "compatibility" to dev?.compatibility?.name,
                "cameraPermission" to cameraPermission,
                "phase" to (if (g != null) glassesState.phase.name else GlassesPhase.OFF.name),
                "hasDisplay" to (g?.hasDisplay ?: false),
                "hasCamera" to (g?.hasCamera ?: false),
                "error" to glassesState.error,
                "updateRequired" to updateRequired,
                "testMode" to testMode,
            ),
            "focus" to mapOf(
                "running" to (r != null),
                "sessionId" to r?.sessionId,
                "mode" to (r?.mode ?: config.mode),
                "startedAt" to r?.startedAt,
                "hintCount" to (r?.hintCount ?: 0),
                "micPaused" to micPaused,
                "phoneOnly" to (r != null && g == null),
            ),
            "mic" to mapOf(
                "listening" to (mic?.isListening == true),
                "bufferedSec" to (mic?.bufferedSeconds ?: 0),
                "error" to micError,
            ),
            "hint" to mapOf(
                "phase" to phase.name,
                "text" to (if (phase == Phase.SHOWN) current?.text else null),
                "lastText" to history.lastOrNull()?.text,
                "lastCardId" to history.lastOrNull()?.cardId,
                "message" to (if (phase == Phase.MESSAGE) lastMessage else null),
                "index" to viewIndex + 1,
                "total" to history.size,
            ),
            "server" to mapOf(
                "configured" to HintClient.isConfigured,
                "ok" to serverOk,
                "error" to serverError,
            ),
            "config" to config.toMap(),
            "lastEnded" to lastEnded,
        )
    }

    companion object {
        private const val TAG = "FocusLens"
        private const val DEBOUNCE_MS = 1_500L
        private const val HISTORY = 5
        private val RECONNECT_DELAYS = longArrayOf(2_000, 5_000, 10_000)
    }
}
