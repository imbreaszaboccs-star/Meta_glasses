package com.focuslens.focuslens.focus

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.SystemClock
import com.meta.wearable.dat.camera.Camera
import com.meta.wearable.dat.camera.addCamera
import com.meta.wearable.dat.camera.types.AudioCodec as DatAudioCodec
import com.meta.wearable.dat.camera.types.AudioFrame
import com.meta.wearable.dat.camera.types.AudioSampleRate
import com.meta.wearable.dat.camera.types.PhotoData
import com.meta.wearable.dat.camera.types.StreamConfiguration
import com.meta.wearable.dat.camera.types.StreamState
import com.meta.wearable.dat.camera.types.VideoQuality
import com.meta.wearable.dat.core.Wearables
import com.meta.wearable.dat.core.selectors.SpecificDeviceSelector
import com.meta.wearable.dat.core.session.DeviceSession
import com.meta.wearable.dat.core.session.DeviceSessionState
import com.meta.wearable.dat.core.types.DeviceIdentifier
import com.meta.wearable.dat.core.types.DeviceSessionError
import com.meta.wearable.dat.display.Display
import com.meta.wearable.dat.display.addDisplay
import com.meta.wearable.dat.display.removeDisplay
import com.meta.wearable.dat.display.types.DisplayState
import com.meta.wearable.dat.display.views.ContentScope
import com.meta.wearable.dat.inputs.Inputs
import com.meta.wearable.dat.inputs.addInputs
import com.meta.wearable.dat.inputs.types.InputEvent
import com.meta.wearable.dat.inputs.types.InputsConfiguration
import com.meta.wearable.dat.inputs.types.NavDirection
import com.meta.wearable.dat.motion.Motion
import com.meta.wearable.dat.motion.addMotion
import com.meta.wearable.dat.motion.types.MotionConfiguration
import com.meta.wearable.dat.motion.types.MotionSample
import com.meta.wearable.dat.motion.types.MotionSamplingRate
import com.meta.wearable.dat.motion.types.MotionSource
import com.meta.wearable.dat.motion.types.MotionState
import com.meta.wearable.dat.speech.Speech
import com.meta.wearable.dat.speech.addSpeech
import java.io.ByteArrayOutputStream
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.sqrt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

enum class Gesture { SELECT, LEFT, RIGHT, UP, DOWN, BACK, CAPTURE }

enum class GlassesPhase { OFF, CONNECTING, READY, PAUSED, INTERRUPTED, ENDED_BY_USER, ERROR }

data class GlassesState(
    val phase: GlassesPhase = GlassesPhase.OFF,
    val hasDisplay: Boolean = false,
    val hasCamera: Boolean = false,
    val error: String? = null,
)

/**
 * One DAT session with the glasses: inputs + camera stream + HUD. Behaviour verified on hardware
 * (ARCHITECTURE.md §4–5) is kept exactly:
 *  - always SpecificDeviceSelector (AutoDeviceSelector reports NO_ELIGIBLE_DEVICE right after creation)
 *  - camera first (LOW, 7 fps, compressed), wait for STREAMING, only THEN the display;
 *    adding/removing the camera while the HUD runs breaks the display
 *  - pinch emits a burst of tiny Drag events before Select → Drag is ignored
 *  - "SESSION_ENDED_BY_DEVICE" = the user ended it on the glasses → never auto-restart
 */
class GlassesSession(
    private val scope: CoroutineScope,
    private val log: (String, String) -> Unit,
    private val onState: (GlassesState) -> Unit,
    private val onGesture: (Gesture) -> Unit,
    /** Called whenever the HUD (re)starts and needs content. */
    private val onDisplayReady: () -> Unit,
    /** The glasses need the DAT app updated (needs an Activity to open Meta AI). */
    private val onUpdateRequired: () -> Unit,
    /** Head pitch signal: true = looking down (desk/notebook), false = up/ahead, null = no signal (unsupported / paused). Called on change only. */
    private val onHead: (Boolean?) -> Unit = {},
    /** Glasses microphone: PCM16 16 kHz mono, any chunk size, on an IO thread. Must not block. */
    private val onAudio: (ByteArray, Int) -> Unit = { _, _ -> },
    /** Every motion sample at 30 Hz (t = elapsedRealtime-based ms), on the main thread: hand it off, don't compute here. */
    private val onImu: (ImuSample) -> Unit = {},
    /** Non-null = attach the on-device speech capability (needs the glasses microphone permission) and report every (partial or final) text. */
    private val onSpeech: ((String, Boolean) -> Unit)? = null,
) {
    private var session: DeviceSession? = null
    private var inputs: Inputs? = null
    private var display: Display? = null
    private var displayStarted = false
    private var displayJob: Job? = null
    private var started = false // DeviceSessionState.STARTED seen: capabilities may be added
    private var camera: Camera? = null
    private var motion: Motion? = null
    private var speech: Speech? = null
    private var headDown: Boolean? = null
    private var tOffset = Long.MIN_VALUE // elapsedRealtime - device clock, from the first sample
    private val jobs = mutableListOf<Job>()

    private var wantDisplay = true
    private var wantCamera = true
    private var wantAudio = false
    private var stopRequested = false
    private var endedByDevice = false
    private var lastError: String? = null

    var state = GlassesState()
        private set

    val isActive: Boolean get() = session != null
    val hasDisplay: Boolean get() = display != null
    val hasCamera: Boolean get() = camera != null

    private fun publish(phase: GlassesPhase = state.phase, error: String? = state.error) {
        state = GlassesState(phase, display != null, camera != null, error)
        onState(state)
    }

    fun start(deviceId: DeviceIdentifier, withDisplay: Boolean, withCamera: Boolean, withAudio: Boolean = false) {
        if (session != null) return
        wantDisplay = withDisplay
        wantCamera = withCamera
        audioLogged = false
        wantAudio = withCamera && withAudio // audio rides the camera stream
        stopRequested = false
        endedByDevice = false
        lastError = null
        publish(GlassesPhase.CONNECTING, null)
        log("session", "starting on $deviceId (display=$withDisplay camera=$withCamera audio=$wantAudio)")

        Wearables.createSession(SpecificDeviceSelector(deviceId)).fold(
            onSuccess = { s ->
                session = s
                jobs += scope.launch {
                    s.errors.collect { err ->
                        lastError = err.name
                        log("error", "session: ${err.description}")
                        when (err) {
                            DeviceSessionError.SESSION_ENDED_BY_DEVICE -> endedByDevice = true
                            DeviceSessionError.DAT_APP_ON_THE_GLASSES_UPDATE_REQUIRED -> onUpdateRequired()
                            else -> Unit
                        }
                    }
                }
                jobs += scope.launch {
                    s.state.collect { st ->
                        log("session", "$st")
                        when (st) {
                            DeviceSessionState.STARTED -> onStarted(s)
                            DeviceSessionState.PAUSED -> publish(GlassesPhase.PAUSED)
                            DeviceSessionState.STOPPED -> onStopped()
                            else -> Unit
                        }
                    }
                }
                s.start()
            },
            onFailure = { e, _ ->
                log("error", "createSession: ${e.description}")
                publish(GlassesPhase.ERROR, e.name)
            },
        )
    }

    private fun onStarted(s: DeviceSession) {
        started = true
        if (inputs == null) attachInputs(s)
        scope.launch {
            if (wantCamera && camera == null && display == null) openCamera(s)
            if (wantDisplay && display == null) attachDisplay(s)
            if (onSpeech != null && speech == null) attachSpeech(s, onSpeech)
            if (motion == null) attachMotion(s) // no ordering constraint (BirdSpotter); after camera + display anyway
            publish(GlassesPhase.READY, null)
            // Resuming from PAUSED: the HUD keeps its handle but lost its content.
            if (display != null) onDisplayReady()
        }
    }

    private fun onStopped() {
        val phase = when {
            stopRequested -> GlassesPhase.OFF
            endedByDevice -> GlassesPhase.ENDED_BY_USER
            else -> GlassesPhase.INTERRUPTED
        }
        clear()
        publish(phase, if (phase == GlassesPhase.INTERRUPTED) lastError else null)
    }

    private fun attachInputs(s: DeviceSession) {
        s.addInputs(InputsConfiguration(consumeBack = true)).fold(
            onSuccess = { inp ->
                inputs = inp
                jobs += scope.launch { inp.state.collect { log("inputs", "$it") } }
                jobs += scope.launch { inp.errors.collect { err -> err?.let { log("error", "inputs: ${it.description}") } } }
                jobs += scope.launch { inp.events.collect(::onInput) }
            },
            onFailure = { e, _ -> log("error", "addInputs: ${e.description}") },
        )
    }

    private fun onInput(event: InputEvent) {
        val g = when (event) {
            is InputEvent.Drag -> return // pinch noise, see class doc
            is InputEvent.Select -> Gesture.SELECT
            is InputEvent.Nav -> when (event.direction) {
                NavDirection.LEFT -> Gesture.LEFT
                NavDirection.RIGHT -> Gesture.RIGHT
                NavDirection.UP -> Gesture.UP
                NavDirection.DOWN -> Gesture.DOWN
            }
            is InputEvent.Back -> Gesture.BACK
            is InputEvent.Capture -> Gesture.CAPTURE
            else -> return log("input", "unhandled $event")
        }
        log("input", "$g · $event")
        onGesture(g)
    }

    // -- camera --

    private suspend fun openCamera(s: DeviceSession): Camera? {
        val cam = s.addCamera(
            // Compressed stream keeps running when the app leaves the foreground (Meta BirdSpotter note).
            // Experimental camera audio (glasses mic): decided here, when the camera is added.
            StreamConfiguration(
                audioCodec = if (wantAudio) DatAudioCodec.PCM(AudioSampleRate.RATE_16000, 1) else null,
                videoQuality = VideoQuality.LOW, frameRate = 7, compressVideo = true,
            ),
        ).fold(
            onSuccess = { it },
            onFailure = { e, _ -> log("error", "addCamera: ${e.description}"); null },
        ) ?: return null
        // The audio collector must be running before start().
        if (wantAudio) jobs += scope.launch(Dispatchers.IO) { cam.stream.audioStream.collect(::onAudioFrame) }
        cam.stream.start().onFailure { e, _ -> log("error", "stream.start: ${e.description}") }
        val streaming = withTimeoutOrNull(STREAM_TIMEOUT_MS) { cam.stream.state.first { it == StreamState.STREAMING } }
        if (streaming == null) {
            log("camera", "stream did not start within $STREAM_TIMEOUT_MS ms, continuing without camera")
            cam.stop()
            return null
        }
        log("camera", "stream STREAMING")
        camera = cam
        jobs += scope.launch {
            cam.stream.state.collect { st ->
                if (st == StreamState.STOPPED || st == StreamState.CLOSED) {
                    log("camera", "stream $st")
                    if (camera === cam) { camera = null; publish() }
                }
            }
        }
        return cam
    }

    private var audioLogged = false

    private fun onAudioFrame(frame: AudioFrame) {
        // 16-bit PCM in the platform's byte order (little-endian on Android), mono, at the requested rate: no conversion.
        val b = frame.buffer.duplicate()
        val n = b.remaining() and 1.inv()
        if (n <= 0) return
        if (!audioLogged) { audioLogged = true; log("camera", "glasses audio arriving: ${b.remaining()} bytes/frame") }
        val bytes = ByteArray(n)
        b.get(bytes, 0, n)
        onAudio(bytes, n)
    }

    /** JPEG bytes, or null if the camera is unavailable. Takes 1.1–2.0 s on real glasses. */
    suspend fun capturePhoto(): ByteArray? {
        val cam = camera ?: return null
        val t0 = SystemClock.elapsedRealtime()
        val photo = withTimeoutOrNull(PHOTO_TIMEOUT_MS) {
            cam.stream.capturePhoto().fold(
                onSuccess = { it },
                onFailure = { e, _ -> log("error", "capturePhoto: ${e.description}"); null },
            )
        } ?: return null
        val jpeg = withContext(Dispatchers.Default) { toJpeg(photo) }
        log("photo", "${jpeg.size / 1024} kB in ${SystemClock.elapsedRealtime() - t0} ms")
        return jpeg
    }

    private fun toJpeg(photo: PhotoData): ByteArray {
        val bitmap = when (photo) {
            is PhotoData.Bitmap -> photo.bitmap
            is PhotoData.HEIC -> {
                val bytes = ByteArray(photo.data.remaining()).also { photo.data.duplicate().get(it) }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            }
        }
        // Very bright scenes (strong light, glare) lose text; dark ones are left alone.
        val px = IntArray(bitmap.width * bitmap.height).also { bitmap.getPixels(it, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height) }
        val fixed = ImageFix.correct(px)
        log("photo", fixed?.let { "bright ($it), corrected" } ?: "luma ${ImageFix.stats(px)}, not corrected")
        val out = if (fixed != null) Bitmap.createBitmap(px, bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888) else bitmap
        return ByteArrayOutputStream().also { out.compress(Bitmap.CompressFormat.JPEG, 85, it) }.toByteArray()
    }

    // -- speech (wake word on the glasses, text only) --

    private fun attachSpeech(s: DeviceSession, sink: (String, Boolean) -> Unit) {
        s.addSpeech().fold(
            onSuccess = { sp ->
                speech = sp
                jobs += scope.launch { sp.transcriptions.collect { r -> r?.let { sink(it.text, it.isFinal) } } }
                jobs += scope.launch { sp.state.collect { log("speech", "$it") } }
                jobs += scope.launch { sp.errors.collect { err -> err?.let { log("error", "speech: ${it.description}") } } }
                sp.start().onFailure { e, _ -> log("error", "speech start: ${e.description}") }
            },
            onFailure = { e, _ -> log("error", "addSpeech: ${e.description}") },
        )
    }

    // -- motion (head pitch) --

    private fun attachMotion(s: DeviceSession) {
        s.addMotion(MotionConfiguration(samplingRate = MotionSamplingRate.HZ_30)).fold(
            onSuccess = { m ->
                motion = m
                jobs += scope.launch { m.samples.collect(::onMotion) }
                jobs += scope.launch {
                    m.state.collect { st ->
                        log("motion", "$st")
                        if (st == MotionState.PAUSED || st == MotionState.STOPPED) setHead(null)
                    }
                }
                jobs += scope.launch { m.errors.collect { err -> err?.let { log("error", "motion: ${it.description}") } } }
                m.start() // the capability starts STOPPED; the collectors above are already running
            },
            onFailure = { e, _ -> log("error", "addMotion: ${e.description}") }, // nudges needing the head signal are skipped
        )
    }

    private fun onMotion(sample: MotionSample) {
        if (sample.source != MotionSource.GLASSES) return
        val a = sample.accelerometer ?: return
        val g = sqrt(a.x * a.x + a.y * a.y + a.z * a.z)
        if (g < MIN_G) return // free fall / noise
        // Axes as in BirdSpotter's GlassesAim: y = up, z = back towards the wearer, so z > 0 = nose pitched down.
        // ponytail: axis sign is NOT verified on hardware; if nudges never/always see "head down", flip the sign or the knobs below.
        val elevation = -Math.toDegrees(asin((a.z / g).toDouble().coerceIn(-1.0, 1.0)))
        emitImu(sample, a.x.toFloat(), a.y.toFloat(), a.z.toFloat(), elevation.toFloat())
        // ponytail: calibration knobs. Down below -DOWN_DEG, back up above -UP_DEG (hysteresis between).
        setHead(when {
            elevation < -DOWN_DEG -> true
            elevation > -UP_DEG -> false
            else -> headDown ?: return
        })
    }

    /** Pitch/roll come from gravity (same axes as the head signal); yaw from the orientation quaternion when there is one. */
    private fun emitImu(sample: MotionSample, ax: Float, ay: Float, az: Float, pitch: Float) {
        val ms = sample.timestampNs / 1_000_000
        if (tOffset == Long.MIN_VALUE) tOffset = SystemClock.elapsedRealtime() - ms
        val gyro = sample.gyroscope
        val q = sample.orientation
        val yaw = if (q == null) 0.0 else {
            val x = q.x.toDouble(); val y = q.y.toDouble(); val z = q.z.toDouble(); val w = q.w.toDouble()
            Math.toDegrees(atan2(2 * (w * y + x * z), 1 - 2 * (x * x + y * y)))
        }
        onImu(ImuSample(ms + tOffset, ax, ay, az,
            gyro?.x?.toFloat() ?: 0f, gyro?.y?.toFloat() ?: 0f, gyro?.z?.toFloat() ?: 0f,
            pitch, Math.toDegrees(atan2(ax.toDouble(), ay.toDouble())).toFloat(), yaw.toFloat()))
    }

    private fun setHead(down: Boolean?) {
        if (down == headDown) return
        headDown = down
        onHead(down)
    }

    // -- HUD --

    private fun attachDisplay(s: DeviceSession) {
        s.addDisplay().fold(
            onSuccess = { d ->
                display = d
                displayJob = scope.launch {
                    var began = false // the flow starts at STOPPED: only a STOPPED after STARTING/STARTED means the display is gone
                    d.state.collect { st ->
                        log("display", "$st")
                        when (st) {
                            DisplayState.STARTED -> { began = true; displayStarted = true; publish(); onDisplayReady() }
                            DisplayState.STOPPED, DisplayState.CLOSED -> if (began && display === d) { display = null; displayStarted = false; publish() }
                            else -> began = true
                        }
                    }
                }
            },
            onFailure = { e, _ -> log("error", "addDisplay: ${e.description}") },
        )
    }

    /** On demand: attaches the DAT display (idempotent, main thread). [onDisplayReady] fires when it is up. */
    fun ensureDisplay() {
        val s = session ?: return
        if (!started || display != null) return
        attachDisplay(s)
    }

    /** Gives the glasses back to the web app (idempotent, main thread). */
    fun releaseDisplay() {
        val s = session ?: return
        if (display == null) return
        displayJob?.cancel(); displayJob = null
        display = null; displayStarted = false
        s.removeDisplay().onFailure { e, _ -> log("error", "removeDisplay: ${e.description}") }
        publish()
    }

    /** Replaces the whole HUD screen. No-op until the display reports STARTED. */
    fun show(content: ContentScope.() -> Unit) {
        val d = display?.takeIf { displayStarted } ?: return
        scope.launch(Dispatchers.IO) {
            d.sendContent(content).onFailure { e, _ -> log("error", "sendContent: ${e.description}") }
        }
    }

    fun stop() {
        val s = session ?: return
        stopRequested = true
        log("session", "stopping")
        s.stop() // cascades to camera, inputs, display → STOPPED → clear()
    }

    private fun clear() {
        displayJob?.cancel(); displayJob = null; displayStarted = false; started = false
        jobs.forEach { it.cancel() }
        jobs.clear()
        inputs = null
        display = null
        camera = null
        motion = null
        speech = null
        tOffset = Long.MIN_VALUE
        setHead(null)
        session = null
    }

    private companion object {
        const val STREAM_TIMEOUT_MS = 8_000L
        const val PHOTO_TIMEOUT_MS = 6_000L
        const val MIN_G = 5f
        const val DOWN_DEG = 25.0
        const val UP_DEG = 15.0
    }
}
