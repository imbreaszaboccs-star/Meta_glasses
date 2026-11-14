package com.focuslens.focuslens.focus

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.SystemClock
import com.meta.wearable.dat.camera.Camera
import com.meta.wearable.dat.camera.addCamera
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
import com.meta.wearable.dat.display.types.DisplayState
import com.meta.wearable.dat.display.views.ContentScope
import com.meta.wearable.dat.inputs.Inputs
import com.meta.wearable.dat.inputs.addInputs
import com.meta.wearable.dat.inputs.types.InputEvent
import com.meta.wearable.dat.inputs.types.InputsConfiguration
import com.meta.wearable.dat.inputs.types.NavDirection
import java.io.ByteArrayOutputStream
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
) {
    private var session: DeviceSession? = null
    private var inputs: Inputs? = null
    private var display: Display? = null
    private var camera: Camera? = null
    private val jobs = mutableListOf<Job>()

    private var wantDisplay = true
    private var wantCamera = true
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

    fun start(deviceId: DeviceIdentifier, withDisplay: Boolean, withCamera: Boolean) {
        if (session != null) return
        wantDisplay = withDisplay
        wantCamera = withCamera
        stopRequested = false
        endedByDevice = false
        lastError = null
        publish(GlassesPhase.CONNECTING, null)
        log("session", "starting on $deviceId (display=$withDisplay camera=$withCamera)")

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
        if (inputs == null) attachInputs(s)
        scope.launch {
            if (wantCamera && camera == null && display == null) openCamera(s)
            if (wantDisplay && display == null) attachDisplay(s)
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
            StreamConfiguration(videoQuality = VideoQuality.LOW, frameRate = 7, compressVideo = true),
        ).fold(
            onSuccess = { it },
            onFailure = { e, _ -> log("error", "addCamera: ${e.description}"); null },
        ) ?: return null
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
        return ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, 85, it) }.toByteArray()
    }

    // -- HUD --

    private fun attachDisplay(s: DeviceSession) {
        s.addDisplay().fold(
            onSuccess = { d ->
                display = d
                jobs += scope.launch {
                    d.state.collect { st ->
                        log("display", "$st")
                        when (st) {
                            DisplayState.STARTED -> { publish(); onDisplayReady() }
                            DisplayState.STOPPED, DisplayState.CLOSED -> if (display === d) { display = null; publish() }
                            else -> Unit
                        }
                    }
                }
            },
            onFailure = { e, _ -> log("error", "addDisplay: ${e.description}") },
        )
    }

    /** Replaces the whole HUD screen. No-op without a display. */
    fun show(content: ContentScope.() -> Unit) {
        val d = display ?: return
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
        jobs.forEach { it.cancel() }
        jobs.clear()
        inputs = null
        display = null
        camera = null
        session = null
    }

    private companion object {
        const val STREAM_TIMEOUT_MS = 8_000L
        const val PHOTO_TIMEOUT_MS = 6_000L
    }
}
