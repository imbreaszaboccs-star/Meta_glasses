package com.focuslens.focuslens

import android.Manifest
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.SystemClock
import android.util.Log
import androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions
import androidx.fragment.app.FragmentActivity
import com.meta.wearable.dat.camera.Camera
import com.meta.wearable.dat.camera.addCamera
import com.meta.wearable.dat.camera.removeCamera
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
import com.meta.wearable.dat.core.types.LinkState
import com.meta.wearable.dat.core.types.Permission
import com.meta.wearable.dat.core.types.PermissionStatus
import com.meta.wearable.dat.display.Display
import com.meta.wearable.dat.display.addDisplay
import com.meta.wearable.dat.display.types.DisplayState
import com.meta.wearable.dat.display.views.Direction
import com.meta.wearable.dat.display.views.FlexBoxBackground
import com.meta.wearable.dat.display.views.TextStyle
import com.meta.wearable.dat.inputs.Inputs
import com.meta.wearable.dat.inputs.addInputs
import com.meta.wearable.dat.inputs.types.InputEvent
import com.meta.wearable.dat.inputs.types.InputsConfiguration
import io.flutter.plugin.common.BinaryMessenger
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.MethodChannel
import java.io.ByteArrayOutputStream
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

private const val TAG = "FocusLens"
private const val DEBOUNCE_MS = 1500L
private const val STREAM_TIMEOUT_MS = 8000L
private const val PHOTO_TIMEOUT_MS = 10000L

/**
 * Most Flutter ↔ Meta DAT.
 *
 * Dart → Kotlin (MethodChannel "focuslens/glasses"):
 *   init, register, unregister, startSession {display}, stopSession, help
 * Kotlin → Dart (EventChannel "focuslens/glasses/events"):
 *   {kind, text, ts, ...extra}  kind ∈ registration | device | session | display | inputs | input
 *                                      | camera | photo | error | info
 */
class GlassesBridge(
    private val activity: FragmentActivity,
    messenger: BinaryMessenger,
) : EventChannel.StreamHandler {

    private enum class Hud { IDLE, BUSY, RESULT }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var sink: EventChannel.EventSink? = null

    private var initialized = false
    private val watchedDevices = mutableSetOf<DeviceIdentifier>()

    private var session: DeviceSession? = null
    private var inputs: Inputs? = null
    private var display: Display? = null
    private var camera: Camera? = null
    private val sessionJobs = mutableListOf<Job>()

    private var withDisplay = true
    private var hud = Hud.IDLE
    private var lastHelpAt = 0L

    private val btPermissionLauncher =
        activity.registerForActivityResult(RequestMultiplePermissions()) { results ->
            if (results.values.all { it }) initWearables()
            else emit("error", "Bluetooth oprávnění zamítnuto: $results")
        }

    private val cameraPermissionLauncher =
        activity.registerForActivityResult(Wearables.RequestPermissionContract()) { result ->
            result
                .onSuccess(::onCameraPermission)
                .onFailure { e, _ -> emit("error", "žádost o kameru: ${e.description}") }
        }

    init {
        MethodChannel(messenger, "focuslens/glasses").setMethodCallHandler { call, result ->
            when (call.method) {
                "init" -> btPermissionLauncher.launch(
                    arrayOf(Manifest.permission.BLUETOOTH, Manifest.permission.BLUETOOTH_CONNECT),
                )
                "register" -> Wearables.startRegistration(activity)
                "unregister" -> Wearables.startUnregistration(activity)
                "startSession" -> startSession(call.argument<Boolean>("display") ?: true)
                "stopSession" -> stopSession()
                "help" -> onHelp("tlačítko v telefonu")
                else -> return@setMethodCallHandler result.notImplemented()
            }
            result.success(null)
        }
        EventChannel(messenger, "focuslens/glasses/events").setStreamHandler(this)
    }

    override fun onListen(arguments: Any?, events: EventChannel.EventSink) { sink = events }
    override fun onCancel(arguments: Any?) { sink = null }

    /** Thread-safe: DAT callbacky můžou přijít z jiného vlákna. */
    private fun emit(kind: String, text: String, extra: Map<String, Any?> = emptyMap()) {
        Log.i(TAG, "[$kind] $text")
        val ts = System.currentTimeMillis()
        scope.launch { sink?.success(mapOf("kind" to kind, "text" to text, "ts" to ts) + extra) }
    }

    // -- SDK init + monitoring --

    private fun initWearables() {
        if (initialized) return emit("info", "SDK už je inicializované")
        Wearables.initialize(activity.applicationContext).fold(
            onSuccess = {
                initialized = true
                emit("info", "Wearables.initialize OK")
                startMonitoring()
            },
            onFailure = { e, _ -> emit("error", "initialize: ${e.description}") },
        )
    }

    private fun startMonitoring() {
        scope.launch { Wearables.registrationState.collect { emit("registration", "$it") } }
        scope.launch {
            Wearables.registrationErrorStream.collect {
                emit("error", "registrace: ${it.getLocalizedDescription(activity)}")
            }
        }
        scope.launch {
            Wearables.devices.collect { ids ->
                emit("device", "spárovaná zařízení: ${ids.size}")
                for (id in ids - watchedDevices) {
                    watchedDevices += id
                    launch { Wearables.devicesMetadata[id]?.collect { emit("device", "$it") } }
                }
            }
        }
    }

    // -- Session --

    /** Konkrétní připojené brýle. AutoDeviceSelector se plní asynchronně a hned po vytvoření bývá prázdný → NO_ELIGIBLE_DEVICE. */
    private fun connectedDevice(): DeviceIdentifier? =
        Wearables.devices.value.firstOrNull { id ->
            Wearables.devicesMetadata[id]?.value?.linkState == LinkState.CONNECTED
        }

    private fun startSession(withDisplay: Boolean) {
        if (!initialized) return emit("error", "nejdřív init")
        if (session != null) return emit("info", "session už běží, nejdřív stop")
        val deviceId = connectedDevice()
            ?: return emit("error", "brýle nejsou připojené (Bluetooth / Meta AI app). Zařízení: ${Wearables.devices.value.size}")
        this.withDisplay = withDisplay
        emit("info", "startuji session na $deviceId (HUD=$withDisplay)")

        Wearables.createSession(SpecificDeviceSelector(deviceId)).fold(
            onSuccess = { s ->
                session = s
                sessionJobs += scope.launch {
                    s.errors.collect { err ->
                        emit("error", "session: ${err.description}")
                        if (err == DeviceSessionError.DAT_APP_ON_THE_GLASSES_UPDATE_REQUIRED) {
                            emit("info", "otevírám aktualizaci DAT appky na brýlích v Meta AI")
                            Wearables.openDATGlassesAppUpdate(activity)
                                .onFailure { e, _ -> emit("error", "openDATGlassesAppUpdate: ${e.description}") }
                        }
                    }
                }
                sessionJobs += scope.launch {
                    s.state.collect { state ->
                        emit("session", "$state")
                        when (state) {
                            DeviceSessionState.STARTED -> onSessionStarted(s)
                            DeviceSessionState.STOPPED -> clearSession()
                            else -> Unit
                        }
                    }
                }
                s.start() // fire-and-forget, chyby chodí přes s.errors
            },
            onFailure = { e, _ -> emit("error", "createSession: ${e.description}") },
        )
    }

    /**
     * Pořadí podle Meta BirdSpotter: nejdřív kamera (stream běží celou session), display až po STREAMING.
     * Přidávání/odebírání kamery za běhu displeje rozbíjelo HUD („Failed to render display content“).
     */
    private fun onSessionStarted(s: DeviceSession) {
        if (inputs == null) attachInputs(s)
        scope.launch {
            val status = Wearables.checkPermissionStatus(Permission.CAMERA).fold(
                onSuccess = { it },
                onFailure = { e, _ -> emit("error", "checkPermissionStatus: ${e.description}"); null },
            )
            emit("camera", "oprávnění ke kameře: $status")
            if (status == PermissionStatus.Denied) cameraPermissionLauncher.launch(Permission.CAMERA) // pokračuje v callbacku
            else startCameraThenDisplay(s)
        }
    }

    private fun onCameraPermission(status: PermissionStatus) {
        emit("camera", "oprávnění ke kameře brýlí: $status")
        val s = session ?: return
        if (status == PermissionStatus.Granted) scope.launch { startCameraThenDisplay(s) }
        else attachDisplayIfWanted(s) // bez kamery aspoň HUD
    }

    private suspend fun startCameraThenDisplay(s: DeviceSession) {
        if (camera == null) openCamera(s)
        attachDisplayIfWanted(s)
    }

    private fun attachDisplayIfWanted(s: DeviceSession) {
        if (withDisplay && display == null) attachDisplay(s)
    }

    private fun attachInputs(s: DeviceSession) {
        s.addInputs(InputsConfiguration(consumeBack = true)).fold(
            onSuccess = { inp ->
                inputs = inp
                sessionJobs += scope.launch { inp.state.collect { emit("inputs", "$it") } }
                sessionJobs += scope.launch {
                    inp.errors.collect { err -> err?.let { emit("error", "inputs: ${it.description}") } }
                }
                sessionJobs += scope.launch { inp.events.collect(::onInput) }
            },
            onFailure = { e, _ -> emit("error", "addInputs: ${e.description}") },
        )
    }

    private fun onInput(event: InputEvent) {
        when (event) {
            // ponytail: pinch posílá před Select sérii drobných Drag událostí, ty jsou šum
            is InputEvent.Drag -> return
            is InputEvent.Select -> {
                emit("input", "SELECT · $event")
                when (hud) {
                    Hud.IDLE -> onHelp("pinch")
                    Hud.RESULT -> showIdle() // pinch nad výsledkem = zavřít
                    Hud.BUSY -> emit("info", "pinch ignorován, fotka se pořizuje")
                }
            }
            is InputEvent.Nav -> emit("input", "NAV · $event")
            is InputEvent.Back -> emit("input", "BACK · $event")
            is InputEvent.Button -> emit("input", "BUTTON · $event")
            is InputEvent.Capture -> emit("input", "CAPTURE · $event")
            else -> emit("input", "OTHER · $event")
        }
    }

    // -- Kamera + foto --

    private fun onHelp(source: String) {
        val s = session ?: return emit("error", "pomoc: session neběží")
        val now = SystemClock.elapsedRealtime()
        if (hud == Hud.BUSY || now - lastHelpAt < DEBOUNCE_MS) return emit("info", "pomoc ignorována (debounce)")
        lastHelpAt = now
        hud = Hud.BUSY
        showHud("Dívám se…", "pořizuji fotku")
        emit("info", "POMOC ($source)")

        scope.launch {
            val t0 = SystemClock.elapsedRealtime()
            // Stream běží celou session; když spadl, zkusíme ho jednou nahodit znovu.
            val cam = camera ?: openCamera(s)
            if (cam == null) return@launch fail("kamera neběží")

            val photo = withTimeoutOrNull(PHOTO_TIMEOUT_MS) {
                cam.stream.capturePhoto().fold(
                    onSuccess = { it },
                    onFailure = { e, _ -> emit("error", "capturePhoto: ${e.description}"); null },
                )
            }
            val tPhoto = SystemClock.elapsedRealtime()
            if (photo == null) return@launch fail("fotka nepřišla")

            val saved = withContext(Dispatchers.IO) { savePhoto(photo) }
            val tSaved = SystemClock.elapsedRealtime()
            val timing = "foto ${tPhoto - t0} ms · uložení ${tSaved - tPhoto} ms · celkem ${tSaved - t0} ms"
            emit(
                "photo", "${saved.width}×${saved.height}, ${saved.file.length() / 1024} kB · $timing",
                mapOf("path" to saved.file.absolutePath, "totalMs" to (tSaved - t0)),
            )
            hud = Hud.RESULT
            showHud("Fotka ✓", "${saved.width}×${saved.height} · ${"%.1f".format((tSaved - t0) / 1000.0)} s\npinch = zavřít")
        }
    }

    private fun fail(why: String) {
        emit("error", "pomoc selhala: $why")
        hud = Hud.RESULT
        showHud("Nepovedlo se", "$why\npinch = zavřít")
    }

    /** Spustí stream a počká na STREAMING; capturePhoto() funguje jen během streamu. */
    private suspend fun openCamera(s: DeviceSession): Camera? {
        val cam = s.addCamera(
            // Komprimovaný stream nepauzuje, když appka odejde z popředí (poznámka z Meta BirdSpotter).
            StreamConfiguration(videoQuality = VideoQuality.LOW, frameRate = 7, compressVideo = true),
        ).fold(
            onSuccess = { it },
            onFailure = { e, _ -> emit("error", "addCamera: ${e.description}"); null },
        ) ?: return null
        camera = cam
        cam.stream.start().onFailure { e, _ -> emit("error", "stream.start: ${e.description}") }
        val streaming = withTimeoutOrNull(STREAM_TIMEOUT_MS) {
            cam.stream.state.first { it == StreamState.STREAMING }
        }
        emit("camera", if (streaming != null) "stream STREAMING" else "stream nenaběhl do $STREAM_TIMEOUT_MS ms")
        if (streaming == null) {
            cam.stop()
            camera = null
            s.removeCamera()
            return null
        }
        sessionJobs += scope.launch {
            cam.stream.state.collect { st ->
                if (st == StreamState.STOPPED || st == StreamState.CLOSED) {
                    emit("camera", "stream $st")
                    if (camera === cam) camera = null
                }
            }
        }
        return cam
    }

    private class SavedPhoto(val file: File, val width: Int, val height: Int)

    private fun savePhoto(photo: PhotoData): SavedPhoto {
        val bitmap = when (photo) {
            is PhotoData.Bitmap -> photo.bitmap
            is PhotoData.HEIC -> {
                val bytes = ByteArray(photo.data.remaining()).also { photo.data.duplicate().get(it) }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            }
        }
        val jpeg = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, 85, it) }.toByteArray()
        val file = File(activity.filesDir, "photos/${System.currentTimeMillis()}.jpg")
        file.parentFile?.mkdirs()
        file.writeBytes(jpeg)
        return SavedPhoto(file, bitmap.width, bitmap.height)
    }

    // -- HUD --

    private fun attachDisplay(s: DeviceSession) {
        s.addDisplay().fold(
            onSuccess = { d ->
                display = d
                sessionJobs += scope.launch {
                    d.state.collect { st ->
                        emit("display", "$st")
                        if (st == DisplayState.STARTED) showIdle()
                    }
                }
            },
            onFailure = { e, _ -> emit("error", "addDisplay: ${e.description}") },
        )
    }

    private fun showIdle() {
        hud = Hud.IDLE
        showHud("FocusLens", "pinch = pomoc")
    }

    /** sendContent nahrazuje celou obrazovku. */
    private fun showHud(title: String, body: String) {
        val d = display ?: return
        scope.launch(Dispatchers.IO) {
            d.sendContent {
                flexBox(direction = Direction.COLUMN, gap = 12, padding = 24, background = FlexBoxBackground.CARD) {
                    text(title, style = TextStyle.HEADING)
                    text(body, style = TextStyle.BODY)
                }
            }.onFailure { e, _ -> emit("error", "sendContent: ${e.description}") }
        }
    }

    // -- Úklid --

    private fun stopSession() {
        val s = session ?: return emit("info", "žádná session neběží")
        emit("info", "zastavuji session")
        s.stop() // kaskádově zastaví kameru, inputs i display
    }

    private fun clearSession() {
        sessionJobs.forEach { it.cancel() }
        sessionJobs.clear()
        inputs = null
        display = null
        camera = null
        session = null
        hud = Hud.IDLE
    }

    fun dispose() {
        session?.stop()
        scope.cancel()
    }
}
