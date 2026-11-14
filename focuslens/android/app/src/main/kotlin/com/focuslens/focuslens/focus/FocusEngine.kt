package com.focuslens.focuslens.focus

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.meta.wearable.dat.core.Wearables
import com.meta.wearable.dat.core.selectors.AutoDeviceSelector
import com.meta.wearable.dat.core.types.Device
import com.meta.wearable.dat.core.types.DeviceIdentifier
import com.meta.wearable.dat.core.types.LinkState
import com.meta.wearable.dat.core.types.Permission
import com.meta.wearable.dat.core.types.PermissionStatus
import com.meta.wearable.dat.core.types.DeviceCompatibility
import com.meta.wearable.dat.core.types.RegistrationState
import com.meta.wearable.dat.core.voiceinvocations.startVoiceInvocationsStream
import com.meta.wearable.dat.core.voiceinvocations.types.actions.LaunchApp
import java.io.ByteArrayOutputStream
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject

interface EngineListener {
    fun onStatus(status: Map<String, Any?>) {}
    /** The HUD screen changed (RemoteLink publishes it to the web app). */
    fun onHud() {}
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
    val inbox = InboxStore(app.filesDir)
    val mistakes = MistakeStore(app.getSharedPreferences("focus_mistakes", Context.MODE_PRIVATE))
    private val speaker = Speaker(app)

    var config = FocusConfig.load(prefs)
        private set

    // -- SDK / glasses link --
    private var sdkReady = false
    private var registration: RegistrationState = RegistrationState.UNAVAILABLE
    private val devices = mutableMapOf<DeviceIdentifier, Device>()
    private val watched = mutableSetOf<DeviceIdentifier>()
    private var cameraPermission = "unknown" // granted | denied | unknown
    private var micPermission = "unknown"    // glasses microphone (DAT Permission.MICROPHONE), same values
    /** True while an Activity is visible (MainActivity onStart/onStop): the phone mic can only be opened then (Android 14+). */
    @Volatile var foreground = false
    /** Where this block's audio comes from: phone | glasses | none. Decided at block start, see [chooseAudio]. */
    private var audio = "none"
    private var noAudioShown = false
    /** Set by FocusLensApp: the glasses-menu link's own status for the UI. */
    var remoteStatus: () -> Map<String, Any?> = { emptyMap() }
    private var updateRequired = false
    private var lastDeviceId: String? = prefs.getString("lastDeviceId", null) // last id seen CONNECTED
    private var voiceStarted = false

    // -- focus block --
    private class Run(val sessionId: String, val mode: String, val startedAt: Long) {
        var hintCount = 0
        var goal = ""          // the focus anchor ("" = none)
        var goalAsked = false  // the "what are we working on?" prompt is shown at most once
    }
    private var run: Run? = null
    private var mic: MicRing? = null
    private var wake: WakeWord? = null
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
    enum class Phase { IDLE, THINKING, SHOWN, MESSAGE, QUEST, CHECKING, DUMP, NUDGE }
    private data class Shown(val text: String, val cardId: String, val formula: Bitmap? = null, val crop: Bitmap? = null, val tex: String = "")

    // -- quest: a hint with ≥ 2 micro-steps; pinch = "done, check me" --
    private class Quest(val cardId: String, val title: String, val steps: List<String>) {
        var index = 0
        val done = BooleanArray(steps.size)
        var finished = false
        var feedback: String? = null
        var crop: Bitmap? = null
        var formula: Bitmap? = null
        var tex = ""
    }
    private var quest: Quest? = null

    // -- proactive nudges (decision logic in Nudges.kt) --
    private val nudges = Nudges { SystemClock.elapsedRealtime() }
    private var nudgeKind: NudgeKind? = null
    private var nudgeJob: Job? = null
    private var nudgeQuest: Quest? = null // the quest Nudges currently times
    private var checkJob: Job? = null
    private var dumpJob: Job? = null
    private var dumpPrev = Phase.IDLE
    private var phase = Phase.IDLE
    private val history = ArrayList<Shown>() // last 5 hints, oldest first
    private val recent = ArrayList<JSONObject>() // last 6 hints of this focus block {situation, title, step}, oldest first
    private var viewIndex = -1
    private var hintJob: Job? = null
    private var hideJob: Job? = null
    private var lastHelpAt = 0L
    @Volatile private var currentScreen: HudScreen? = null
    val hudScreen: HudScreen? get() = currentScreen
    private var lastMessage: String? = null

    // -- glasses "blocks" panel (swipe up while IDLE/SHOWN) --
    private class Block(val row: BlockRow, val title: String, val step: String, val correction: String, val formula: Bitmap?, val tex: String = "")
    private class Panel(val items: List<Block>, var selected: Int = 0, var detail: Boolean = false)
    private var panel: Panel? = null
    private var panelJob: Job? = null
    private var panelLoading = false

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
                startVoice()
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
                if (ids.size != devices.size) log("device", "device set: ${devices.size} → ${ids.size}")
                devices.keys.retainAll(ids)
                for (id in ids - watched) {
                    watched += id
                    launch {
                        var prev: LinkState? = null
                        Wearables.devicesMetadata[id]?.collect { d ->
                            devices[id] = d
                            if (d.linkState != prev) log("device", "link ${prev ?: "-"} → ${d.linkState}")
                            if (d.linkState == LinkState.CONNECTED) {
                                lastDeviceId = id.toString()
                                prefs.edit().putString("lastDeviceId", lastDeviceId).apply()
                            }
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

    /** The device to open a session on: connected one, else the remembered one, else the first compatible. The DAT link idles after a session ends but a new session still works. */
    private fun sessionDevice(): Pair<DeviceIdentifier, Device>? =
        connectedDevice()
            ?: devices.entries.firstOrNull { it.key.toString() == lastDeviceId }?.toPair()
            ?: devices.entries.firstOrNull { it.value.compatibility == DeviceCompatibility.COMPATIBLE }?.toPair()

    /** One app-scoped stream for "Hey Meta, start FocusLens" (experimental). Never closed. */
    private fun startVoice() {
        if (voiceStarted) return
        voiceStarted = true
        scope.launch {
            Wearables.registrationState.first { it == RegistrationState.REGISTERED }
            val stream = Wearables.startVoiceInvocationsStream(AutoDeviceSelector())
            launch { stream.state.collect { log("voice", "stream $it") } }
            launch { stream.errors.collect { log("error", "voice: ${it.name}") } }
            stream.invocations.collect { inv ->
                when (inv) {
                    is LaunchApp -> {
                        log("voice", "launch app")
                        val ok = try { startFocus(null); true } catch (e: Exception) { log("error", "voice start: $e"); false }
                        if (ok) inv.responseHandle.sendSuccess(null) else inv.responseHandle.sendFailure("start failed")
                    }
                }
            }
        }
    }

    private fun onDeviceConnected() {
        log("device", "glasses connected")
        reconnectAttempts = 0
        if (registration == RegistrationState.REGISTERED) scope.launch { refreshCameraPermission() }
        if (run != null && glasses == null && !glassesEndedByUser) attachGlasses()
    }

    suspend fun refreshCameraPermission(): String {
        if (!sdkReady || connectedDevice() == null) return cameraPermission
        cameraPermission = checkDat(Permission.CAMERA, cameraPermission)
        micPermission = checkDat(Permission.MICROPHONE, micPermission)
        emitStatus()
        return cameraPermission
    }

    private suspend fun checkDat(p: Permission, old: String) = Wearables.checkPermissionStatus(p).fold(
        onSuccess = { if (it == PermissionStatus.Granted) "granted" else "denied" },
        onFailure = { e, _ -> log("error", "checkPermissionStatus: ${e.description}"); old },
    )

    fun onMicPermissionResult(status: PermissionStatus) {
        micPermission = if (status == PermissionStatus.Granted) "granted" else "denied"
        log("camera", "glasses microphone permission: $status")
        emitStatus()
    }

    /** Registered with Meta AI and a glasses pair we can open a session on (the DAT link idles between sessions). */
    fun glassesAvailable() = registration == RegistrationState.REGISTERED && sessionDevice() != null

    val isRunning: Boolean get() = run != null

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
        r.goal = (cfg?.get("goal") as? String).orEmpty().trim().take(200)
        phase = Phase.IDLE
        quest = null
        nudges.reset(); nudges.enabled = config.nudges; nudgeQuest = null; nudgeKind = null
        history.clear(); recent.clear(); viewIndex = -1
        panel = null
        prefs.edit().putInt("blocksStarted", prefs.getInt("blocksStarted", 0) + 1).apply()
        writeSession(r, ended = false)

        audio = chooseAudio()
        noAudioShown = false
        if (audio == "none") noAudioNotice()
        // From the background (remote start) the standby service already runs: no new FGS start.
        if (audio == "phone" || !FocusService.active) FocusService.start(app)
        startMic()
        attachGlasses()
        speaker.warmUp()
        tickJob = scope.launch { while (isActive) { delay(1_000); nudgeTick(); emitStatus() } }
        log("focus", "started ${r.mode}")
        emitStatus()
        return null
    }

    fun stopFocus(reason: String = "user") {
        val r = run ?: return
        hintJob?.cancel(); hideJob?.cancel(); reconnectJob?.cancel(); tickJob?.cancel()
        checkJob?.cancel(); dumpJob?.cancel(); quest = null
        nudgeJob?.cancel(); nudgeKind = null
        speaker.stop()
        glasses?.stop(); glasses = null
        glassesState = GlassesState()
        stopWake()
        mic?.stop(); mic = null
        micPaused = false
        run = null
        phase = Phase.IDLE
        history.clear(); recent.clear(); viewIndex = -1
        panelJob?.cancel(); panel = null
        currentScreen = null
        writeSession(r, ended = true)
        Notifications.cancelInterrupted(app)
        audio = "none"
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
        if (run != null && !micPaused && old.bufferSec != config.bufferSec) { stopWake(); mic?.stop(); mic = null; startMic() }
        else if (old.wakeWord != config.wakeWord || old.nudges != config.nudges || old.language != config.language) { stopWake(); mic?.let { startWake(it) } }
        nudges.enabled = config.nudges
        if (old.language != config.language && phase == Phase.IDLE) showIdle()
        emitStatus()
    }

    fun setMode(mode: String) = updateConfig(mapOf("mode" to mode))

    /**
     * auto = phone mic if the app is visible and RECORD_AUDIO is granted (the phone mic can't be opened from the
     * background on Android 14+), else the glasses mic, else none. "phone" prefers the phone, "glasses" only the
     * glasses. The glasses mic is only confirmed (DAT permission) when the session starts.
     */
    private fun chooseAudio(): String {
        val phone = foreground && hasPermission(Manifest.permission.RECORD_AUDIO)
        val glasses = glassesAvailable()
        val a = when (config.audioSource) {
            "glasses" -> if (glasses) "glasses" else "none"
            else -> if (phone) "phone" else if (glasses) "glasses" else "none"
        }
        log("focus", "audio source ${config.audioSource} → $a (foreground=$foreground)")
        return a
    }

    private fun noAudioNotice() {
        if (noAudioShown || run == null) return
        noAudioShown = true
        showMessage(Strings.noAudio, Strings.noAudioBody, autoHideMs = 8_000)
    }

    private fun startMic() {
        when (audio) {
            "phone" -> {
                if (!hasPermission(Manifest.permission.RECORD_AUDIO)) {
                    micError = "permission"
                    return log("error", "mic: no RECORD_AUDIO permission")
                }
                val m = MicRing(config.bufferSec)
                if (m.start()) { mic = m; micError = null; startWake(m) } else { micError = "unavailable"; log("error", "mic could not be opened") }
            }
            // The glasses session pushes its microphone into this ring (attachGlasses → onAudio).
            "glasses" -> MicRing(config.bufferSec).let { m -> m.startExternal(); mic = m; micError = null; startWake(m) }
            else -> micError = null
        }
    }

    private fun startWake(m: MicRing) {
        if (!config.wakeWord && !config.nudges) return
        val w = WakeWord(app, ::log, config.language, config.wakeWord, config.nudges,
            onWake = { scope.launch { help("wake word") } }, onKeyword = { scope.launch { nudges.onKeyword() } })
        wake = w
        m.listener = w::feed
        w.start()
    }

    private fun stopWake() {
        mic?.listener = null
        wake?.stop(); wake = null
    }

    fun pauseMic() {
        if (run == null || micPaused) return
        stopWake()
        mic?.stop(); mic = null
        micPaused = true
        nudges.onMicPaused()
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
        val (id, device) = sessionDevice() ?: return log("info", "no glasses known, phone-only mode")
        if (registration != RegistrationState.REGISTERED) return log("info", "not registered with Meta AI, phone-only mode")
        if (device.linkState != LinkState.CONNECTED) log("info", "link is ${device.linkState}, trying session anyway")
        scope.launch {
            val cam = withCamera && refreshCameraPermission() == "granted"
            if (glasses?.isActive == true) return@launch
            // Audio rides the camera stream and must be decided now. Without the grant a stream may not start at all.
            val withAudio = cam && audio == "glasses" && micPermission == "granted"
            if (audio == "glasses" && !withAudio && run != null && !testMode) {
                log("focus", "glasses microphone unavailable (camera=$cam permission=$micPermission)")
                if (micPermission == "denied" || !cam) { audio = "none"; stopWake(); mic?.stop(); mic = null; noAudioNotice() }
            }
            lateinit var gs: GlassesSession
            gs = GlassesSession(
                scope = scope,
                log = ::log,
                onState = { st -> onGlassesState(gs, st) },
                onGesture = { g -> onGesture(g) },
                onDisplayReady = { if (glasses === gs && !askGoal()) rerender() },
                onHead = { h -> if (glasses === gs) nudges.onHead(h) },
                onUpdateRequired = { updateRequired = true; event("updateRequired"); emitStatus() },
                onAudio = { b, n -> mic?.feed(b, n) },
            )
            glasses = gs
            gs.start(id, withDisplay = device.isDisplayCapable(), withCamera = cam, withAudio = withAudio)
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

    fun onGesture(g: Gesture) {
        event("gesture", mapOf("name" to g.name))
        if (testMode) {
            if (g == Gesture.SELECT) showMessage(Strings.itWorks, null, autoHideMs = 4_000)
            return
        }
        if (run == null) return
        if (panel != null || panelLoading) return onPanelGesture(g)
        if (phase == Phase.NUDGE) return onNudgeGesture(g)
        when (phase) {
            Phase.DUMP -> return if (g == Gesture.SELECT || g == Gesture.BACK) { dumpJob?.cancel(); endDump() } else Unit
            Phase.CHECKING -> return if (g == Gesture.BACK) cancelCheck() else Unit
            Phase.QUEST -> return onQuestGesture(g)
            else -> Unit
        }
        when (g) {
            Gesture.SELECT -> when (phase) {
                Phase.IDLE -> help("pinch")
                Phase.THINKING -> showHud(HudScreen.Thinking(Strings.stillThinking))
                else -> dismiss()
            }
            Gesture.RIGHT -> next()
            Gesture.LEFT -> prev()
            Gesture.BACK -> if (phase == Phase.THINKING) cancelHint() else dismiss()
            Gesture.CAPTURE -> log("input", "capture button ignored (task.md T5.1: may clash with Meta AI capture)")
            Gesture.UP -> if (phase == Phase.IDLE || phase == Phase.SHOWN) openPanel()
            Gesture.DOWN -> if (phase != Phase.THINKING) startDump("task")
        }
    }

    // -- blocks panel: UP = next row, DOWN = previous row (DOWN on the first row closes), SELECT = open, LEFT/BACK = back --

    private fun openPanel() {
        panelLoading = true
        hideJob?.cancel(); speaker.stop()
        scope.launch {
            val today = LocalDate.now().toString()
            val items = withContext(Dispatchers.IO) {
                cards.ids().asReversed().mapNotNull { id ->
                    val c = cards.read(id) ?: return@mapNotNull null
                    val step = c.optJSONArray("steps")?.let { a -> if (a.length() > 0) a.getJSONObject(a.length() - 1).optString("text") else null }
                    val at = c.optString("createdAt")
                    if (step == null || !at.startsWith(today)) return@mapNotNull null
                    val title = c.optJSONObject("card")?.optString("title").orEmpty().ifBlank { step }
                    val png = if (c.has("formulaImage")) cards.readFile(id, c.getString("formulaImage")) else null
                    Block(
                        BlockRow(title.take(24), at.substring(11, 16)), title, step,
                        c.optJSONObject("card")?.optString("correction").orEmpty().take(120),
                        png?.let { BitmapFactory.decodeByteArray(it, 0, it.size) },
                        c.optString("formula"),
                    )
                }.take(10)
            }
            panelLoading = false
            if (run == null || panel != null || phase == Phase.THINKING || phase == Phase.MESSAGE) return@launch
            phase = Phase.IDLE
            panel = Panel(items)
            renderPanel()
            emitStatus()
        }
    }

    private fun onPanelGesture(g: Gesture) {
        val p = panel ?: return
        if (g == Gesture.CAPTURE) return
        if (g == Gesture.BACK || g == Gesture.LEFT) {
            if (p.detail) p.detail = false else return closePanel()
        } else if (!p.detail) when (g) {
            Gesture.UP -> p.selected = (p.selected + 1).coerceAtMost(maxOf(0, p.items.lastIndex))
            Gesture.DOWN -> if (p.selected == 0) return closePanel() else p.selected--
            Gesture.SELECT -> if (p.items.isNotEmpty()) p.detail = true
            else -> Unit
        }
        renderPanel()
    }

    private fun renderPanel() {
        val p = panel ?: return
        panelJob?.cancel()
        panelJob = scope.launch { delay(PANEL_TIMEOUT_MS); closePanel() }
        val b = p.items.getOrNull(p.selected)
        showHud(if (p.detail && b != null) HudScreen.BlockDetail(b.title, b.step, b.correction, b.formula, b.tex)
        else HudScreen.Blocks(p.items.map { it.row }, p.selected))
    }

    private fun closePanel() {
        panelJob?.cancel(); panelJob = null
        panel = null
        if (run != null && phase == Phase.IDLE) showIdle()
    }

    /** Pinch, the in-app button or the notification action. */
    fun help(source: String) {
        if (run == null) return log("info", "help ignored: no focus block running")
        val now = SystemClock.elapsedRealtime()
        if (now - lastHelpAt < DEBOUNCE_MS) return log("info", "help ignored (debounce)")
        lastHelpAt = now
        if (phase == Phase.THINKING) return showHud(HudScreen.Thinking(Strings.stillThinking))
        if (phase == Phase.CHECKING || phase == Phase.DUMP) return log("info", "help ignored (busy)")
        quest = null
        log("hint", "help from $source")
        runHint(chainCardId = null)
    }

    fun next() {
        if (phase == Phase.QUEST) return questMove(+1)
        if (history.isEmpty() || phase == Phase.THINKING || phase == Phase.CHECKING || phase == Phase.DUMP) return
        if (phase == Phase.SHOWN && viewIndex < history.lastIndex) {
            viewIndex++
            return showCurrentHint()
        }
        if (phase == Phase.IDLE) return
        runHint(chainCardId = history[viewIndex.coerceIn(0, history.lastIndex)].cardId)
    }

    fun prev() {
        if (phase == Phase.QUEST) return questMove(-1)
        if (history.isEmpty() || phase == Phase.THINKING || phase == Phase.CHECKING || phase == Phase.DUMP) return
        viewIndex = if (phase == Phase.IDLE || phase == Phase.MESSAGE) history.lastIndex else (viewIndex - 1).coerceAtLeast(0)
        showCurrentHint()
    }

    fun dismiss() {
        if (phase == Phase.THINKING || phase == Phase.CHECKING) return
        nudgeJob?.cancel(); nudgeKind = null
        dumpJob?.cancel(); dumpJob = null; quest = null
        hideJob?.cancel()
        speaker.stop()
        panelJob?.cancel(); panel = null
        phase = Phase.IDLE
        showIdle()
        emitStatus()
    }

    private fun cancelHint() {
        hintJob?.cancel()
        log("hint", "cancelled by user")
        if (quest != null) return showQuest() // a "smaller step" request keeps the old quest
        phase = Phase.IDLE
        showIdle()
        emitStatus()
    }

    // ------------------------------------------------------------------ the hint pipeline

    private fun runHint(chainCardId: String?, ask: String = "help", prevOverride: List<String>? = null) {
        hintJob?.cancel(); hideJob?.cancel()
        nudgeJob?.cancel(); nudgeKind = null
        speaker.stop()
        panelJob?.cancel(); panel = null
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
        val goal = run?.goal.orEmpty()
        val known = mistakes.list()
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
                val recentJson = JSONArray(recent.toList())

                if (isNew) withContext(Dispatchers.IO) {
                    val c = CardStore.newCard(id, sessionId, cfg.mode, cfg.language)
                    val req = JSONObject().put("ts", ts).put("previous", JSONArray()).put("recent", recentJson)
                        .put("goal", goal).put("mistakes", JSONArray(known))
                    photo?.let { cards.saveFile(id, "photo.jpg", it); req.put("photo", "photo.jpg") }
                    audio?.let {
                        cards.saveFile(id, "request.${it.ext}", it.bytes)
                        req.put("audio", "request.${it.ext}").put("audioMime", it.mime)
                    }
                    cards.write(id, c.put("request", req))
                } else if (photo != null) withContext(Dispatchers.IO) { cards.saveFile(id, photoName!!, photo) }
                if (isNew) event("card", mapOf("id" to id, "status" to CardStore.STATUS_PENDING))

                val req = HintRequest(photo, audio, cfg.language, cfg.mode, ts, prevOverride ?: previous, id, recentJson, goal, known, ask)
                var res = withContext(Dispatchers.IO) { HintClient.hint(req) }
                if (res.error is HintError.TooLarge && pcm != null) {
                    log("hint", "413, retrying with the last 60 s only")
                    val small = withContext(Dispatchers.Default) { AudioCodec.encode(pcm.takeLast(60 * MicRing.BYTES_PER_SECOND), MicRing.SAMPLE_RATE, app.cacheDir, 16_000) }
                    res = withContext(Dispatchers.IO) { HintClient.hint(HintRequest(photo, small, cfg.language, cfg.mode, ts, prevOverride ?: previous, id, recentJson, goal, known, ask)) }
                }
                val tTotal = SystemClock.elapsedRealtime() - t0
                val timings = JSONObject().put("photoMs", tPhoto).put("encodeMs", tReady).put("aiMs", tTotal - tReady)
                    .put("totalMs", tTotal).put("audioBytes", audio?.bytes?.size ?: 0).put("audioMime", audio?.mime ?: JSONObject.NULL)
                res.ok?.let { timings.put("serverMs", it.serverMs ?: JSONObject.NULL) }
                recordLatency(timings, res.ok != null)
                completed = true

                val ok = res.ok
                if (ok != null) onHintOk(id, isNew, ok, photo, photoName, timings, pcm, audio)
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
        id: String, isNew: Boolean, res: HintResponse, photo: ByteArray?, photoName: String?,
        timings: JSONObject, pcm: ByteArray?, audio: EncodedAudio?,
    ) {
        val formula = res.formulaPng?.let { withContext(Dispatchers.Default) { BitmapFactory.decodeByteArray(it, 0, it.size) } }
        val cropPng = cropOf(photo, res.focusBox)
        val crop = cropPng?.first?.let(Crop::forHud)
        if (res.mistake.isNotBlank()) mistakes.add(res.mistake)
        val isQuest = res.steps.size >= 2
        withContext(Dispatchers.IO) {
            cards.update(id) { c ->
                cards.putFormula(id, c, res.formula, res.formulaPng.takeIf { formula != null })
                cropPng?.let { cards.saveFile(id, "crop.png", it.second); c.put("cropImage", "crop.png") }
                if (res.mistake.isNotBlank()) c.put("mistake", res.mistake) // read by evening review + Notion export
                if (isQuest) c.put("quest", questJson(res.steps, 0, BooleanArray(res.steps.size))) else c.remove("quest")
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
        history += Shown(res.nextStep, id, formula, crop, res.formula)
        while (history.size > HISTORY) history.removeAt(0)
        recent += JSONObject().put("situation", res.card.optString("situation").ifBlank { config.mode })
            .put("title", res.card.optString("title")).put("step", res.nextStep)
        while (recent.size > RECENT) recent.removeAt(0)
        viewIndex = history.lastIndex
        run?.let { it.hintCount++; writeSession(it, ended = false) }
        serverOk = true; serverError = null
        if (isQuest) {
            quest = Quest(id, res.card.optString("title").ifBlank { res.nextStep }, res.steps).also { it.crop = crop; it.formula = formula; it.tex = res.formula }
            showQuest()
        } else {
            quest = null
            showCurrentHint()
        }
        event("card", mapOf("id" to id, "status" to CardStore.STATUS_DONE))
        log("hint", "ok in ${timings.optLong("totalMs")} ms")
        speakIfNeeded(res.nextStep)

        // After the HUD is updated: keep the audio on the card according to the setting.
        if (isNew) scope.launch(Dispatchers.IO) { applyAudioPolicy(id, pcm, audio) }
    }

    private fun mergeCard(old: JSONObject?, new: JSONObject): JSONObject {
        if (old == null) return new
        for (k in listOf("summary", "correction", "transcript_tail", "situation")) {
            val v = new.optString(k)
            if (v.isNotBlank()) old.put(k, v)
        }
        new.optJSONArray("speakers")?.takeIf { it.length() > 0 }?.let { old.put("speakers", it) }
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
        if (config.readAloud || glassesWithoutDisplay) scope.launch { speaker.speak(text, config.language, hasDisplay = g?.hasDisplay == true) }
    }

    private fun recordLatency(t: JSONObject, ok: Boolean) {
        val m = mutableMapOf<String, Any?>("at" to System.currentTimeMillis(), "ok" to ok)
        t.keys().forEach { k -> m[k] = t.opt(k).takeUnless { it == JSONObject.NULL } }
        latencies.addLast(m)
        while (latencies.size > 20) latencies.removeFirst()
    }

    // ------------------------------------------------------------------ quest, check, brain dump

    /** The AI's focus box cut out of the JPEG we sent: (tight bitmap, PNG bytes), or null when there is no valid box. */
    private suspend fun cropOf(photo: ByteArray?, box: IntArray?): Pair<Bitmap, ByteArray>? {
        if (photo == null || !Crop.valid(box)) return null
        return withContext(Dispatchers.Default) {
            Crop.render(photo, box)?.let { b -> b to ByteArrayOutputStream().also { b.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray() }
        }
    }

    private fun questJson(steps: List<String>, index: Int, done: BooleanArray) =
        JSONObject().put("steps", JSONArray(steps)).put("index", index).put("done", JSONArray(done.toList()))

    private fun persistQuest(q: Quest) {
        val j = questJson(q.steps, q.index, q.done.copyOf())
        scope.launch(Dispatchers.IO) { cards.update(q.cardId) { it.put("quest", j) } }
    }

    private fun showQuest() {
        val q = quest ?: return toIdle()
        hideJob?.cancel()
        phase = Phase.QUEST
        lastMessage = null
        showHud(HudScreen.Quest(q.steps[q.index], q.index, q.steps.size, run?.goal.orEmpty(), q.feedback, q.crop, q.formula, q.tex))
        emitStatus()
    }

    private fun toIdle() {
        quest = null
        phase = Phase.IDLE
        showIdle()
        emitStatus()
    }

    // -- quest gestures: pinch = done (check), right = skip, left = back, down = note, back = leave --

    private fun onQuestGesture(g: Gesture) {
        when (g) {
            Gesture.SELECT -> startCheck()
            Gesture.RIGHT -> questMove(+1)
            Gesture.LEFT -> questMove(-1)
            Gesture.DOWN -> startDump("task")
            Gesture.BACK -> dismiss()
            else -> Unit
        }
    }

    private fun questMove(d: Int) {
        val q = quest ?: return
        val i = q.index + d
        if (i !in q.steps.indices) return
        q.index = i; q.feedback = null; q.crop = null; q.formula = null; q.tex = ""
        nudges.onQuestProgress()
        persistQuest(q)
        showQuest()
        speakIfNeeded(q.steps[i])
    }

    private fun startCheck() {
        val q = quest ?: return
        val g = glasses
        hideJob?.cancel(); speaker.stop()
        phase = Phase.CHECKING
        showHud(HudScreen.Thinking(Strings.checking))
        emitStatus()
        val cfg = config
        val goal = run?.goal.orEmpty()
        val known = mistakes.list()
        checkJob = scope.launch {
            try {
                val photo = g?.capturePhoto()
                if (photo == null) { q.feedback = Strings.noPhoto; return@launch showQuest() }
                val res = withContext(Dispatchers.IO) { HintClient.check(photo, cfg.language, goal, known, q.title, q.steps, q.index) }
                val ok = res.ok
                if (ok == null) {
                    log("check", "failed: ${res.error?.message}")
                    q.feedback = Strings.checkFailed
                    return@launch showQuest()
                }
                onCheckOk(q, ok, photo)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log("error", "check: $e")
                q.feedback = Strings.checkFailed
                showQuest()
            }
        }
    }

    private fun cancelCheck() {
        checkJob?.cancel(); checkJob = null
        if (quest?.finished == true) toIdle() else showQuest()
    }

    private suspend fun onCheckOk(q: Quest, r: CheckResponse, photo: ByteArray) {
        serverOk = true; serverError = null
        if (r.mistake.isNotBlank()) {
            mistakes.add(r.mistake)
            withContext(Dispatchers.IO) { cards.update(q.cardId) { it.put("mistake", r.mistake) } }
        }
        if (!r.done) {
            val c = cropOf(photo, r.focusBox)
            if (c != null) withContext(Dispatchers.IO) {
                cards.saveFile(q.cardId, "crop.png", c.second)
                cards.update(q.cardId) { it.put("cropImage", "crop.png") }
            }
            q.crop = c?.first?.let(Crop::forHud)
            q.tex = r.formula
            q.formula = r.formulaPng?.let { withContext(Dispatchers.Default) { BitmapFactory.decodeByteArray(it, 0, it.size) } }
            q.feedback = r.feedback.ifBlank { Strings.notYet }
            return showQuest()
        }
        q.done[q.index] = true
        nudges.onQuestProgress()
        val last = q.index == q.steps.lastIndex
        if (last) q.finished = true else q.index++
        q.feedback = null; q.crop = null; q.formula = null; q.tex = ""
        persistQuest(q)
        showHud(HudScreen.Message("✓ " + r.feedback.ifBlank { Strings.stepDone }, ""))
        delay(1_500)
        if (last) {
            showHud(HudScreen.Message(Strings.questDone, ""))
            delay(2_000)
            toIdle()
        } else {
            showQuest()
            speakIfNeeded(q.steps[q.index])
        }
    }

    // -- brain dump / focus anchor: record one short utterance from the phone mic, send it to /dump --

    private fun startDump(kind: String) {
        val m = mic?.takeIf { it.isListening }
        if (m == null) {
            if (kind == "task") showMessage(Strings.micOff, null, autoHideMs = 3_000)
            return
        }
        hideJob?.cancel(); speaker.stop(); panelJob?.cancel(); panel = null
        nudgeJob?.cancel(); nudgeKind = null
        dumpPrev = phase
        phase = Phase.DUMP
        val isGoal = kind == "goal"
        showHud(HudScreen.Message(
            if (isGoal) Strings.goalPrompt else Strings.sayIt,
            if (isGoal) Strings.goalPromptBody else Strings.footerCancel,
        ))
        emitStatus()
        val cap = Dictation()
        val done = CompletableDeferred<ByteArray?>()
        val prev = m.listener
        val tap: (ByteArray, Int) -> Unit = { b, n -> prev?.invoke(b, n); if (cap.feed(b, n)) done.complete(cap.result) }
        m.listener = tap
        dumpJob = scope.launch {
            try {
                runDump(kind, withTimeoutOrNull(DUMP_TIMEOUT_MS) { done.await() })
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log("error", "dump: $e")
                flash(Strings.noteFailed, ms = 2_000)
            } finally {
                if (m.listener === tap) m.listener = prev
            }
            endDump()
        }
    }

    private suspend fun runDump(kind: String, pcm: ByteArray?) {
        if (pcm == null) return flash(Strings.nothingHeard)
        showHud(HudScreen.Thinking(Strings.saving))
        val audio = EncodedAudio(AudioCodec.wav(pcm, MicRing.SAMPLE_RATE), "audio/wav", "wav")
        val locale = config.language
        val res = withContext(Dispatchers.IO) { HintClient.dump(audio, kind, locale) }
        val text = res.ok.orEmpty()
        log("dump", "$kind: " + (res.error?.message ?: "${text.length} chars")) // never the text itself
        when {
            res.error != null -> flash(Strings.noteFailed, ms = 2_000)
            text.isEmpty() -> flash(Strings.nothingHeard)
            kind == "goal" -> { run?.goal = text.take(200); flash(Strings.anchor(text)) }
            else -> {
                withContext(Dispatchers.IO) { inbox.add(text) }
                flash(Strings.noteSaved, run?.goal?.takeIf { it.isNotBlank() }?.let(Strings::backTo).orEmpty(), 2_000)
            }
        }
    }

    private suspend fun flash(title: String, body: String = "", ms: Long = 1_500) {
        showHud(HudScreen.Message(title, body))
        delay(ms)
    }

    /** Back to whatever was on the HUD before the dump. */
    private fun endDump() {
        dumpJob = null
        when {
            quest != null -> showQuest()
            dumpPrev == Phase.SHOWN && history.isNotEmpty() -> showCurrentHint()
            else -> toIdle()
        }
    }

    /** First display of a block without a goal: ask for it once (pinch = skip). True if the prompt took the HUD. */
    private fun askGoal(): Boolean {
        val r = run ?: return false
        if (r.goalAsked || r.goal.isNotEmpty() || testMode || phase != Phase.IDLE || panel != null || glasses?.hasDisplay != true) return false
        r.goalAsked = true
        startDump("goal")
        return phase == Phase.DUMP
    }

    // ------------------------------------------------------------------ proactive nudges

    /** Once a second: offer help when Nudges says so and the HUD is free (idle or in a quest, nothing else on top). */
    private fun nudgeTick() {
        if (run == null) return
        val q = quest?.takeIf { !it.finished }
        if (q !== nudgeQuest) { nudgeQuest = q; if (q != null) nudges.onQuestStart() else nudges.onQuestEnd() }
        val free = (phase == Phase.IDLE || phase == Phase.QUEST) && panel == null && !panelLoading &&
            !testMode && glasses?.hasDisplay == true
        if (free) nudges.tick()?.let(::showNudge)
    }

    private fun showNudge(k: NudgeKind) {
        hideJob?.cancel(); speaker.stop()
        nudgeKind = k
        phase = Phase.NUDGE
        log("nudge", "$k shown")
        showHud(HudScreen.Nudge(if (k == NudgeKind.KEY) Strings.nudgeKey else Strings.nudgeStuck))
        nudgeJob?.cancel()
        nudgeJob = scope.launch {
            delay(Nudges.SHOW_MS)
            if (phase == Phase.NUDGE) { log("nudge", "$k timeout"); endNudge() }
        }
        emitStatus()
    }

    /** Back to the screen the nudge covered. */
    private fun endNudge() {
        nudgeJob?.cancel(); nudgeJob = null; nudgeKind = null
        if (quest != null) showQuest() else toIdle()
    }

    private fun onNudgeGesture(g: Gesture) {
        val k = nudgeKind ?: return endNudge()
        when (g) {
            Gesture.SELECT -> {
                log("nudge", "$k accepted")
                when (k) {
                    NudgeKind.KEY -> { quest = null; runHint(null, ask = "rescue") }
                    NudgeKind.STUCK -> {
                        val q = quest ?: return endNudge()
                        runHint(q.cardId, ask = "smaller", prevOverride = q.steps) // result replaces the quest
                    }
                }
            }
            Gesture.LEFT, Gesture.BACK -> { log("nudge", "$k dismissed"); endNudge() }
            else -> Unit
        }
    }

    // ------------------------------------------------------------------ HUD

    private fun showCurrentHint() {
        val h = history.getOrNull(viewIndex) ?: return
        phase = Phase.SHOWN
        lastMessage = null
        showHud(HudScreen.Hint(h.text, viewIndex + 1, history.size, h.formula, h.crop, run?.goal.orEmpty(), h.tex))
        scheduleHide(config.hudTimeoutSec * 1_000L)
        emitStatus()
    }

    private fun showMessage(title: String, body: String?, autoHideMs: Long) {
        panelJob?.cancel(); panel = null
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
        if (panel != null) return renderPanel()
        if (testMode) return showHud(HudScreen.Idle(compact = false, paused = false))
        showHud(HudScreen.Idle(compact = prefs.getInt("blocksStarted", 0) > 3, paused = micPaused, goal = run?.goal.orEmpty()))
    }

    private fun showHud(screen: HudScreen) {
        currentScreen = screen
        if (config.hudSource == "dat") glasses?.show(Hud.render(screen))
        listeners.forEach { it.onHud() }
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
        scope.launch(Dispatchers.IO) { cards.deleteAll(); inbox.clear() }
        mistakes.clear()
    }

    fun forgetMistakes() = mistakes.clear()

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
                "linkState" to dev?.linkState?.name,
                "name" to dev?.name,
                "battery" to dev?.batteryLevel,
                "displayCapable" to (dev?.isDisplayCapable() ?: false),
                "compatibility" to dev?.compatibility?.name,
                "cameraPermission" to cameraPermission,
                "micPermission" to micPermission,
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
                "goal" to (r?.goal ?: ""),
                "micPaused" to micPaused,
                "phoneOnly" to (r != null && g == null),
                "audioSource" to (if (r != null) audio else "none"),
            ),
            "remote" to remoteStatus(),
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
        private const val RECENT = 6
        private const val PANEL_TIMEOUT_MS = 20_000L
        private const val DUMP_TIMEOUT_MS = 10_000L
        private val RECONNECT_DELAYS = longArrayOf(2_000, 5_000, 10_000)
    }
}
