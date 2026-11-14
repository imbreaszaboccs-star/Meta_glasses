package com.focuslens.focuslens.focus

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.focuslens.focuslens.BuildConfig
import com.focuslens.focuslens.FocusLensApp
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit
import org.json.JSONArray
import org.json.JSONObject

/** Outcome of an integration call. [error] is user-facing; [retry] = worth trying again later (network, 5xx). */
class Res(val error: String? = null, val url: String? = null, val retry: Boolean = false, val data: Any? = null) {
    val ok get() = error == null
    fun toMap() = mapOf("ok" to ok, "error" to error, "url" to url)
}

/** One Notion block before JSON: type = heading_2 | paragraph | bulleted_list_item. */
class Block(val type: String, val text: String)

/** What goes into the Notion page for one card. */
class NotionCard(
    val title: String, val summary: String, val correction: String,
    val steps: List<String>, val transcript: List<String>, val mistake: String,
)

/**
 * Todoist + Notion, called straight from the phone. The tokens live only in [EncryptedSharedPreferences],
 * are never sent to our backend and never logged. All network calls are blocking: use Dispatchers.IO.
 */
object Integrations {
    private const val TODOIST = "https://api.todoist.com/api/v1" // REST v2 is shut down; the v1 paths are not verified against the docs
    private const val NOTION = "https://api.notion.com/v1"
    private const val NOTION_VERSION = "2026-03-11"

    private lateinit var app: Context
    fun init(ctx: Context) { app = ctx.applicationContext }

    private val prefs: SharedPreferences by lazy {
        val key = MasterKey.Builder(app).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
        EncryptedSharedPreferences.create(
            app, "focuslens_integrations", key,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    private fun get(k: String): String = runCatching { prefs.getString(k, "") }.getOrNull().orEmpty()

    /** Other encrypted secrets that live next to the tokens (glasses-menu pairing, see RemoteLink). Empty value removes the key. */
    fun secret(k: String): String = get(k)
    fun setSecrets(vararg kv: Pair<String, String>) {
        val e = prefs.edit()
        for ((k, v) in kv) if (v.isEmpty()) e.remove(k) else e.putString(k, v)
        e.apply()
    }

    val todoistConfigured: Boolean get() = get("todoist").isNotEmpty()
    val notionConfigured: Boolean get() = get("notion").isNotEmpty() && get("notionDb").isNotEmpty()

    fun status(): Map<String, Any> = mapOf(
        "todoist" to mapOf("configured" to todoistConfigured, "masked" to mask(get("todoist"))),
        "notion" to mapOf("configured" to notionConfigured, "masked" to mask(get("notion")), "database" to get("notionDb")),
    )

    /** Returns an error message, or null when saved. */
    fun save(kind: String, token: String, database: String?): String? {
        val t = token.trim()
        if (t.isEmpty()) return "Paste the token first."
        when (kind) {
            "todoist" -> prefs.edit().putString("todoist", t).apply()
            "notion" -> {
                val db = parseDatabaseId(database.orEmpty()) ?: return "That doesn't look like a Notion database link or ID."
                prefs.edit().putString("notion", t).putString("notionDb", db).remove("notionDs").remove("notionTitle").apply()
            }
            else -> return "Unknown integration."
        }
        return null
    }

    fun clear(kind: String) {
        val e = prefs.edit()
        if (kind == "todoist") e.remove("todoist") else e.remove("notion").remove("notionDb").remove("notionDs").remove("notionTitle")
        e.apply()
    }

    fun test(kind: String): Res = if (kind == "todoist") todoistValidate() else notionValidate()

    // -- Todoist --

    fun todoistValidate(): Res {
        val r = call("GET", "$TODOIST/projects", get("todoist"), null, "Todoist")
        return Res(r.error, retry = r.retry)
    }

    fun todoistCreate(content: String): Res {
        val r = call("POST", "$TODOIST/tasks", get("todoist"), JSONObject().put("content", content.take(500)), "Todoist")
        return Res(r.error, retry = r.retry)
    }

    /** Pushes every open, unsynced note. Stops at the first failure. */
    @Synchronized
    fun syncInbox(inbox: InboxStore): Res {
        if (!todoistConfigured) return Res()
        for (item in inbox.unsynced()) {
            val r = todoistCreate(item.getString("text"))
            if (!r.ok) return r
            inbox.markSynced(item.getString("id"), "todoist")
        }
        return Res()
    }

    /** Background sync that survives no network: called by InboxStore.add, app start and "Sync now". */
    fun kickSync() {
        if (!::app.isInitialized || !todoistConfigured) return
        val req = OneTimeWorkRequestBuilder<InboxSyncWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(app).enqueueUniqueWork("focuslens-inbox", ExistingWorkPolicy.REPLACE, req)
    }

    // -- Notion --

    fun notionValidate(): Res {
        val t = notionTarget()
        return if (t.ds == null) Res(t.error, retry = t.retry) else Res()
    }

    private class Target(val ds: String?, val title: String?, val error: String? = null, val retry: Boolean = false)

    /** data_source_id + title property name, cached after the first lookup. */
    private fun notionTarget(): Target {
        val cachedDs = get("notionDs")
        val cachedTitle = get("notionTitle")
        if (cachedDs.isNotEmpty() && cachedTitle.isNotEmpty()) return Target(cachedDs, cachedTitle)
        val token = get("notion")
        val db = get("notionDb")
        fun fail(m: String, retry: Boolean = false) = Target(null, null, m, retry)
        if (token.isEmpty() || db.isEmpty()) return fail("Notion isn't set up.")
        val d = call("GET", "$NOTION/databases/$db", token, null, "Notion")
        if (!d.ok) return fail(d.error!!, d.retry)
        val ds = d.json?.optJSONArray("data_sources")?.optJSONObject(0)?.optString("id").orEmpty()
        if (ds.isEmpty()) return fail("Notion returned no data source for this database.")
        val s = call("GET", "$NOTION/data_sources/$ds", token, null, "Notion")
        if (!s.ok) return fail(s.error!!, s.retry)
        val props = s.json?.optJSONObject("properties")
        var title = ""
        props?.keys()?.forEach { k -> if (props.optJSONObject(k)?.optString("type") == "title") title = k }
        if (title.isEmpty()) return fail("The database has no title property.")
        prefs.edit().putString("notionDs", ds).putString("notionTitle", title).apply()
        return Target(ds, title)
    }

    fun notionCreatePage(title: String, blocks: List<Block>): Res {
        val t = notionTarget()
        if (t.ds == null || t.title == null) return Res(t.error, retry = t.retry)
        val token = get("notion")
        val chunks = blocks.chunked(NOTION_MAX_CHILDREN)
        val page = JSONObject()
            .put("parent", JSONObject().put("data_source_id", t.ds))
            .put("properties", JSONObject().put(t.title, JSONObject().put("title", richText(title))))
            .put("children", blockJson(chunks.firstOrNull().orEmpty()))
        val r = call("POST", "$NOTION/pages", token, page, "Notion")
        if (!r.ok) {
            prefs.edit().remove("notionDs").remove("notionTitle").apply() // re-discover next time (database may have changed)
            return Res(r.error, retry = r.retry)
        }
        val id = r.json?.optString("id").orEmpty()
        val url = r.json?.optString("url")?.takeIf { it.isNotEmpty() }
        for (more in chunks.drop(1)) {
            val a = call("PATCH", "$NOTION/blocks/$id/children", token, JSONObject().put("children", blockJson(more)), "Notion")
            if (!a.ok) return Res(a.error, url = url)
        }
        return Res(url = url)
    }

    private fun richText(s: String) =
        JSONArray().put(JSONObject().put("type", "text").put("text", JSONObject().put("content", s.take(NOTION_MAX_TEXT))))

    private fun blockJson(blocks: List<Block>) = JSONArray().also { a ->
        for (b in blocks) a.put(JSONObject().put("object", "block").put("type", b.type).put(b.type, JSONObject().put("rich_text", richText(b.text))))
    }

    // -- Review (our backend, same bearer token as /hint) --

    /** items = [{title, summary, correction, mistake}]; Res.data = JSONArray of {q, a}. */
    fun review(items: JSONArray, locale: String): Res {
        if (HintClient.baseUrl.isBlank() || BuildConfig.API_TOKEN.isBlank()) return Res("The server isn't configured.")
        val r = call("POST", HintClient.baseUrl + "/api/ai/review", BuildConfig.API_TOKEN,
            JSONObject().put("locale", locale).put("items", items), "Server", timeoutMs = 30_000)
        if (!r.ok) return Res(r.error, retry = r.retry)
        return Res(data = r.json?.optJSONArray("cards") ?: JSONArray())
    }

    // -- HTTP --

    private class Resp(val error: String?, val json: JSONObject?, val retry: Boolean) { val ok get() = error == null }

    private fun call(method: String, url: String, token: String, body: JSONObject?, who: String, timeoutMs: Int = 15_000): Resp {
        if (token.isEmpty()) return Resp("$who isn't set up.", null, false)
        val conn = try { URL(url).openConnection() as HttpURLConnection } catch (e: IOException) { return Resp("Bad address.", null, false) }
        return try {
            conn.requestMethod = method
            conn.connectTimeout = 8_000
            conn.readTimeout = timeoutMs
            conn.setRequestProperty("Authorization", "Bearer $token")
            conn.setRequestProperty("Accept", "application/json")
            if (who == "Notion") conn.setRequestProperty("Notion-Version", NOTION_VERSION)
            if (body != null) {
                val bytes = body.toString().toByteArray(Charsets.UTF_8)
                conn.doOutput = true
                conn.setFixedLengthStreamingMode(bytes.size)
                conn.setRequestProperty("Content-Type", "application/json")
                conn.outputStream.use { it.write(bytes) }
            }
            val code = conn.responseCode
            if (code in 200..299) {
                val text = conn.inputStream.bufferedReader().readText()
                Resp(null, runCatching { JSONObject(text) }.getOrNull(), false)
            } else Resp(httpMessage(who, code), null, code == 429 || code >= 500)
        } catch (e: IOException) {
            Resp("No connection to $who. Try again later.", null, true)
        } finally {
            conn.disconnect()
        }
    }

    private fun httpMessage(who: String, code: Int) = when (code) {
        401 -> "$who rejected the token (HTTP 401)."
        403 -> "$who denied access (HTTP 403)."
        404 -> if (who == "Notion") "Database not found (HTTP 404). Share it with your integration: ... > Connections." else "$who: not found (HTTP 404)."
        422 -> "$who couldn't use this (HTTP 422)."
        429 -> "$who is rate limiting. Try again in a minute."
        else -> "$who error (HTTP $code)."
    }

    // -- pure helpers (unit tested) --

    const val NOTION_MAX_TEXT = 2000
    const val NOTION_MAX_CHILDREN = 100

    fun mask(t: String) = if (t.isEmpty()) "" else "•••• " + t.takeLast(4)

    /** The 32-hex database id from a Notion URL (or a bare id, dashed or not), lower-cased and dashless. */
    fun parseDatabaseId(s: String): String? {
        // Drop the query (?v=<view id> must not win) and fragment, then take the last 32 hex chars of the path.
        val path = s.trim().substringBefore('#').substringBefore('?').replace("-", "")
        return Regex("[0-9a-fA-F]{32}(?![0-9a-fA-F])").findAll(path).lastOrNull()?.value?.lowercase()
    }

    /** Splits text into pieces of at most [max] chars. */
    fun split(text: String, max: Int = NOTION_MAX_TEXT): List<String> = if (text.length <= max) listOf(text) else text.chunked(max)

    fun notionBlocks(cards: List<NotionCard>): List<Block> = buildList {
        fun add(type: String, text: String) { if (text.isNotBlank()) split(text.trim()).forEach { add(Block(type, it)) } }
        for (c in cards) {
            add("heading_2", c.title.ifBlank { "Card" })
            add("paragraph", c.summary)
            if (c.correction.isNotBlank()) add("paragraph", "Fix: ${c.correction.trim()}")
            c.steps.forEach { add("bulleted_list_item", it) }
            c.transcript.forEach { add("bulleted_list_item", it) }
            if (c.mistake.isNotBlank()) add("paragraph", "Mistake: ${c.mistake.trim()}")
        }
    }

    /** Anki imports comma-separated, double-quoted fields (newlines inside quotes are fine). */
    fun csvLine(front: String, back: String) = "\"${front.replace("\"", "\"\"")}\",\"${back.replace("\"", "\"\"")}\""

    fun csv(cards: List<Pair<String, String>>) = cards.joinToString("\r\n", postfix = "\r\n") { csvLine(it.first, it.second) }
}

/** Retries the Todoist push when the network is back. */
class InboxSyncWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val r = Integrations.syncInbox((applicationContext as FocusLensApp).engine.inbox)
        return if (r.retry) Result.retry() else Result.success()
    }
}
