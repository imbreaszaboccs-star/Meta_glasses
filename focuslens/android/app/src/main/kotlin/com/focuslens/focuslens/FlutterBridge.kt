package com.focuslens.focuslens

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions
import androidx.core.content.FileProvider
import androidx.fragment.app.FragmentActivity
import com.focuslens.focuslens.focus.EngineListener
import com.focuslens.focuslens.focus.FocusEngine
import com.meta.wearable.dat.core.Wearables
import com.meta.wearable.dat.core.types.Permission
import io.flutter.plugin.common.BinaryMessenger
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.MethodChannel
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Flutter ↔ [FocusEngine] (task.md T1.9). Activity-bound flows (runtime permissions, Meta AI
 * registration, glasses camera permission) live here; everything else is forwarded to the engine.
 *
 * MethodChannel "focuslens/focus", EventChannel "focuslens/focus/events" emitting
 * {type: status|diag|card|gesture|focusEnded|updateRequired, ...}.
 */
class FlutterBridge(
    private val activity: FragmentActivity,
    messenger: BinaryMessenger,
    private val engine: FocusEngine,
) : EventChannel.StreamHandler, EngineListener {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var sink: EventChannel.EventSink? = null
    private var pendingPermissions: MethodChannel.Result? = null
    private var pendingCamera: MethodChannel.Result? = null

    private val permissionLauncher = activity.registerForActivityResult(RequestMultiplePermissions()) { res ->
        engine.initSdk()
        engine.emitStatus()
        pendingPermissions?.success(res.mapKeys { it.key.substringAfterLast('.') })
        pendingPermissions = null
    }

    private val cameraLauncher = activity.registerForActivityResult(Wearables.RequestPermissionContract()) { result ->
        result
            .onSuccess { status ->
                engine.onCameraPermissionResult(status)
                pendingCamera?.success(status.toString())
            }
            .onFailure { e, _ ->
                engine.log("error", "glasses camera permission: ${e.description}")
                pendingCamera?.success("error")
            }
        pendingCamera = null
    }

    init {
        MethodChannel(messenger, "focuslens/focus").setMethodCallHandler { call, result ->
            fun arg(name: String): String? = call.argument<String>(name)
            when (call.method) {
                "getStatus" -> result.success(engine.status())

                // -- permissions & system --
                "requestPermissions" -> {
                    val wanted = buildList {
                        add(Manifest.permission.RECORD_AUDIO)
                        add(Manifest.permission.BLUETOOTH_CONNECT)
                        if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
                    }.filterNot(engine::hasPermission)
                    if (wanted.isEmpty()) { engine.initSdk(); result.success(emptyMap<String, Boolean>()) }
                    else { pendingPermissions?.success(null); pendingPermissions = result; permissionLauncher.launch(wanted.toTypedArray()) }
                }
                "openBatterySettings" -> result.success(startSafely(
                    Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${activity.packageName}")),
                ) || startSafely(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)))
                "openAppSettings" -> result.success(startSafely(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${activity.packageName}")),
                ))
                "openMetaAi" -> result.success(
                    activity.packageManager.getLaunchIntentForPackage(META_AI_PACKAGE)?.let(::startSafely)
                        ?: startSafely(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$META_AI_PACKAGE"))),
                )

                // -- glasses --
                "register" -> { engine.initSdk(); Wearables.startRegistration(activity); result.success(null) }
                "unregister" -> { Wearables.startUnregistration(activity); result.success(null) }
                "requestGlassesCamera" -> scope.launch {
                    if (engine.refreshCameraPermission() == "granted") return@launch result.success("Granted")
                    pendingCamera?.success(null)
                    pendingCamera = result
                    cameraLauncher.launch(Permission.CAMERA)
                }
                "openGlassesUpdate" -> {
                    Wearables.openDATGlassesAppUpdate(activity).onFailure { e, _ -> engine.log("error", "openDATGlassesAppUpdate: ${e.description}") }
                    result.success(null)
                }
                "reconnect" -> { engine.reconnectGlasses(); result.success(null) }
                "startGestureTest" -> { engine.startGestureTest(); result.success(null) }
                "stopGestureTest" -> { engine.stopGestureTest(); result.success(null) }

                // -- focus block --
                "startFocus" -> result.success(engine.startFocus(call.arguments as? Map<*, *>))
                "stopFocus" -> { engine.stopFocus("user"); result.success(null) }
                "pauseMic" -> { engine.pauseMic(); result.success(null) }
                "resumeMic" -> { engine.resumeMic(); result.success(null) }
                "help" -> { engine.help("app"); result.success(null) }
                "next" -> { engine.next(); result.success(null) }
                "prev" -> { engine.prev(); result.success(null) }
                "dismiss" -> { engine.dismiss(); result.success(null) }
                "setMode" -> { engine.setMode(arg("mode") ?: "lecture"); result.success(null) }
                "updateConfig" -> { engine.updateConfig(call.arguments as? Map<*, *>); result.success(null) }

                // -- cards --
                "paths" -> result.success(mapOf(
                    "cards" to engine.cards.cardsDir.absolutePath,
                    "sessions" to engine.cards.sessionsDir.absolutePath,
                ))
                "deleteCard" -> { arg("id")?.let(engine::deleteCard); result.success(null) }
                "setReviewed" -> { arg("id")?.let { engine.setReviewed(it, call.argument<Boolean>("reviewed") ?: true) }; result.success(null) }
                "retryCard" -> { arg("id")?.let(engine::retryCard); result.success(null) }
                "retryPending" -> { engine.retryPending(); result.success(null) }
                "deleteAllData" -> { engine.deleteAllData(); result.success(null) }
                "shareCard" -> result.success(share(arg("text") ?: "", arg("photo")))
                "speak" -> { engine.speak(arg("text") ?: ""); result.success(null) }
                "stopSpeaking" -> { engine.stopSpeaking(); result.success(null) }

                // -- diagnostics --
                "checkServer" -> { engine.checkServer(); result.success(null) }
                "diagnostics" -> result.success(engine.diagnostics())

                else -> result.notImplemented()
            }
        }
        EventChannel(messenger, "focuslens/focus/events").setStreamHandler(this)
    }

    override fun onListen(arguments: Any?, events: EventChannel.EventSink) {
        sink = events
        engine.addListener(this)
    }

    override fun onCancel(arguments: Any?) {
        engine.removeListener(this)
        sink = null
    }

    override fun onStatus(status: Map<String, Any?>) = post(mapOf("type" to "status", "status" to status))

    override fun onEvent(event: Map<String, Any?>) = post(event)

    private fun post(m: Map<String, Any?>) {
        scope.launch { sink?.success(m) }
    }

    private fun startSafely(i: Intent): Boolean = try {
        activity.startActivity(i); true
    } catch (e: ActivityNotFoundException) {
        false
    } catch (e: SecurityException) {
        false
    }

    private fun share(text: String, photoPath: String?): Boolean {
        val i = Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_TEXT, text)
        val photo = photoPath?.let(::File)?.takeIf { it.exists() }
        if (photo != null) {
            val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.cards", photo)
            i.type = "image/jpeg"
            i.putExtra(Intent.EXTRA_STREAM, uri)
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } else {
            i.type = "text/plain"
        }
        return startSafely(Intent.createChooser(i, null))
    }

    fun dispose() {
        engine.removeListener(this)
        pendingPermissions = null
        pendingCamera = null
        scope.cancel()
    }

    private companion object {
        /** Meta AI app (formerly Meta View) that pairs and manages the glasses. */
        const val META_AI_PACKAGE = "com.facebook.stella"
    }
}
