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
import com.focuslens.focuslens.focus.Integrations
import com.focuslens.focuslens.focus.NotionCard
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
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

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
    private var pendingMic: MethodChannel.Result? = null
    private val remote get() = (activity.application as FocusLensApp).remote

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

    private val micLauncher = activity.registerForActivityResult(Wearables.RequestPermissionContract()) { result ->
        result
            .onSuccess { status ->
                engine.onMicPermissionResult(status)
                pendingMic?.success(status.toString())
            }
            .onFailure { e, _ ->
                engine.log("error", "glasses microphone permission: ${e.description}")
                pendingMic?.success("error")
            }
        pendingMic = null
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
                "requestGlassesMic" -> scope.launch {
                    engine.refreshCameraPermission() // also reads the microphone grant
                    if (engine.status().let { ((it["glasses"] as Map<*, *>)["micPermission"]) } == "granted") return@launch result.success("Granted")
                    pendingMic?.success(null)
                    pendingMic = result
                    micLauncher.launch(Permission.MICROPHONE)
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
                "bookmark" -> { engine.bookmark("app"); result.success(null) }
                "next" -> { engine.next(); result.success(null) }
                "prev" -> { engine.prev(); result.success(null) }
                "dismiss" -> { engine.dismiss(); result.success(null) }
                "setMode" -> { engine.setMode(arg("mode") ?: "auto"); result.success(null) }
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
                "forgetMistakes" -> { engine.forgetMistakes(); result.success(null) }
                "deleteAllData" -> { engine.deleteAllData(); result.success(null) }
                "shareCard" -> result.success(share(arg("text") ?: "", arg("photo")))
                "speak" -> { engine.speak(arg("text") ?: ""); result.success(null) }
                "stopSpeaking" -> { engine.stopSpeaking(); result.success(null) }

                // -- inbox, integrations, review --
                "inboxList" -> io(result) { engine.inbox.list().toString() }
                "inboxSetDone" -> io(result) { engine.inbox.setDone(arg("id") ?: "", call.argument<Boolean>("done") ?: true) }
                "inboxDelete" -> io(result) { engine.inbox.delete(arg("id") ?: "") }
                "inboxSync" -> io(result) { Integrations.syncInbox(engine.inbox).also { if (it.retry) Integrations.kickSync() }.toMap() }
                "integrationStatus" -> io(result) { Integrations.status() }
                "integrationSave" -> io(result) {
                    val err = Integrations.save(arg("kind") ?: "", arg("token") ?: "", arg("database"))
                    if (err == null && arg("kind") == "todoist") Integrations.kickSync()
                    mapOf("ok" to (err == null), "error" to err)
                }
                "integrationClear" -> io(result) { Integrations.clear(arg("kind") ?: "") }
                "integrationTest" -> io(result) { Integrations.test(arg("kind") ?: "").toMap() }
                "exportCardsToNotion" -> io(result) { exportToNotion(call.argument<List<String>>("ids").orEmpty()).toMap() }
                "review" -> io(result) { review(call.argument<List<String>>("ids").orEmpty()) }
                "openUrl" -> result.success(arg("url")?.takeIf { it.startsWith("https://") }?.let { startSafely(Intent(Intent.ACTION_VIEW, Uri.parse(it))) } ?: false)
                "shareToAnki" -> result.success(shareToAnki(arg("front") ?: "", arg("back") ?: ""))
                "exportAnkiCsv" -> io(result) { exportAnkiCsv(call.argument<List<Map<String, String>>>("cards").orEmpty()) }

                // -- glasses menu (web app on the glasses, via the backend relay) --
                "remotePair" -> io(result) { remote.pair() }
                "remoteUnpair" -> io(result) { remote.unpair() }
                "remoteInstall" -> result.success(arg("url")?.takeIf { it.startsWith("fb-viewapp://") }
                    ?.let { startSafely(Intent(Intent.ACTION_VIEW, Uri.parse(it))) } ?: false)

                // -- data collection (IMU recordings stay on the phone until exported) --
                "setImuLabel" -> { engine.setImuLabel(arg("label")); result.success(null) }
                "exportImu" -> io(result) { exportImu() }

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

    /** Runs [block] off the main thread and returns its value (an exception becomes an error map). */
    private fun io(result: MethodChannel.Result, block: () -> Any?) {
        scope.launch {
            val v = withContext(Dispatchers.IO) { runCatching { block().takeIf { it !is Unit } }.getOrElse { mapOf("ok" to false, "error" to "Something went wrong.") } }
            result.success(v)
        }
    }

    private fun cardJson(ids: List<String>) = ids.mapNotNull { engine.cards.read(it) }.filter { it.optString("status") == "done" }

    private fun exportToNotion(ids: List<String>): com.focuslens.focuslens.focus.Res {
        val cards = cardJson(ids).sortedBy { it.optString("createdAt") }
        if (cards.isEmpty()) return com.focuslens.focuslens.focus.Res("Nothing to export yet.")
        val items = cards.map { c ->
            val info = c.optJSONObject("card") ?: JSONObject()
            val quest = c.optJSONObject("quest")?.optJSONArray("steps")
            val steps = quest?.let(::strings) ?: c.optJSONArray("steps")?.let { a -> (0 until a.length()).map { a.getJSONObject(it).optString("text") } }.orEmpty()
            val speakers = info.optJSONArray("speakers")?.let { a ->
                (0 until a.length()).mapNotNull { a.optJSONObject(it) }.filter { it.optString("said").isNotBlank() }
                    .map { (it.optString("label").ifBlank { "?" }) + ": " + it.optString("said").trim() }
            }.orEmpty()
            NotionCard(
                info.optString("title"), info.optString("summary"), info.optString("correction"), steps,
                speakers.ifEmpty { info.optString("transcript_tail").trim().takeIf { it.isNotEmpty() }?.let { listOf(it) } ?: emptyList() },
                c.optString("mistake"),
            )
        }
        val date = cards.first().optString("createdAt").take(10)
        val what = if (cards.size == 1) cards[0].optJSONObject("card")?.optString("situation").orEmpty().ifBlank { items[0].title }
        else items.map { it.title }.filter { it.isNotBlank() }.take(3).joinToString(", ")
        return Integrations.notionCreatePage(listOf("FocusLens", date, what).filter { it.isNotBlank() }.joinToString(" · "), Integrations.notionBlocks(items))
    }

    private fun strings(a: JSONArray) = (0 until a.length()).map { a.optString(it) }

    private fun review(ids: List<String>): Map<String, Any?> {
        val items = JSONArray()
        for (c in cardJson(ids).takeLast(20)) {
            val info = c.optJSONObject("card") ?: continue
            items.put(JSONObject().put("title", info.optString("title")).put("summary", info.optString("summary"))
                .put("correction", info.optString("correction")).put("mistake", c.optString("mistake")))
        }
        if (items.length() == 0) return mapOf("ok" to false, "error" to "No cards to review yet.")
        val r = Integrations.review(items, engine.config.language)
        val cards = (r.data as? JSONArray)?.let { a ->
            (0 until a.length()).mapNotNull { a.optJSONObject(it) }.map { mapOf("q" to it.optString("q"), "a" to it.optString("a")) }
        }.orEmpty()
        return mapOf("ok" to r.ok, "error" to r.error, "cards" to cards)
    }

    /** AnkiDroid's share-to-add screen (front = subject, back = text); any share target if it isn't installed. */
    private fun shareToAnki(front: String, back: String): Boolean {
        val i = Intent(Intent.ACTION_SEND).setType("text/plain")
            .putExtra(Intent.EXTRA_SUBJECT, front).putExtra(Intent.EXTRA_TEXT, back)
        return startSafely(Intent(i).setPackage(ANKI_PACKAGE)) || startSafely(Intent.createChooser(i, null))
    }

    /** Comma-separated, quoted "front","back" lines in cache/export/, shared as a file. Returns an error map or ok. */
    private fun exportAnkiCsv(cards: List<Map<String, String>>): Map<String, Any?> {
        if (cards.isEmpty()) return mapOf("ok" to false, "error" to "No cards to export.")
        val dir = File(activity.cacheDir, "export").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val f = File(dir, "focuslens-${java.time.LocalDate.now()}.csv")
        f.writeText(Integrations.csv(cards.map { (it["q"] ?: "") to (it["a"] ?: "") }), Charsets.UTF_8)
        val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.cards", f)
        val i = Intent(Intent.ACTION_SEND).setType("text/csv").putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        return if (startSafely(Intent.createChooser(i, null))) mapOf("ok" to true) else mapOf("ok" to false, "error" to "No app to share the file with.")
    }

    /** Zips filesDir/imu into cache/export and opens the share sheet. */
    private fun exportImu(): Map<String, Any?> {
        val src = File(activity.filesDir, "imu")
        val files = src.walkTopDown().filter { it.isFile }.toList()
        if (files.isEmpty()) return mapOf("ok" to false, "error" to "No recordings yet. Turn on data collection and run a focus block.")
        val dir = File(activity.cacheDir, "export").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val zip = File(dir, "focuslens-imu-${java.time.LocalDate.now()}.zip")
        java.util.zip.ZipOutputStream(zip.outputStream().buffered()).use { z ->
            for (f in files) { z.putNextEntry(java.util.zip.ZipEntry(f.relativeTo(src).invariantSeparatorsPath)); f.inputStream().use { it.copyTo(z) }; z.closeEntry() }
        }
        val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.cards", zip)
        val i = Intent(Intent.ACTION_SEND).setType("application/zip").putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        return if (startSafely(Intent.createChooser(i, null))) mapOf("ok" to true) else mapOf("ok" to false, "error" to "No app to share the file with.")
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
        pendingMic = null
        scope.cancel()
    }

    private companion object {
        /** Meta AI app (formerly Meta View) that pairs and manages the glasses. */
        const val META_AI_PACKAGE = "com.facebook.stella"
        const val ANKI_PACKAGE = "com.ichi2.anki"
    }
}
