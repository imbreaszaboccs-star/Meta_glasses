package com.focuslens.focuslens.focus

import android.util.Base64
import com.focuslens.focuslens.BuildConfig
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

/** Server contract: web/app/api/ai/hint/route.ts + web/lib/meta.ts (request) and web/lib/ai.ts (response). */
class HintRequest(
    val photo: ByteArray?,
    val audio: EncodedAudio?,
    val locale: String,
    val mode: String,
    val ts: String,
    val previous: List<String>,
    val cardId: String,
    /** Earlier hints of this focus block: [{situation, title, step}], oldest first, max 6. */
    val recent: JSONArray = JSONArray(),
    /** The focus anchor ("" if none), the user's recurring mistakes, and "help" | "smaller" | "rescue". */
    val goal: String = "",
    val mistakes: List<String> = emptyList(),
    val ask: String = "help",
)

/** [steps] = 1-6 micro-steps (steps[0] == nextStep); [focusBox] = [ymin, xmin, ymax, xmax] in 0..1000 or null (validated by [Crop]). */
class HintResponse(
    val nextStep: String, val card: JSONObject, val serverMs: Long?,
    val formula: String = "", val formulaPng: ByteArray? = null,
    val steps: List<String> = listOf(nextStep), val focusBox: IntArray? = null, val mistake: String = "",
    /** intent=recap only: what was just said that matters (<= 25 words), "" otherwise. */
    val recap: String = "",
    /** What the decision tree picked: answer | fix | recap | next_step | summary | start | solve ("" from an older backend). */
    val intent: String = "",
    /** One LaTeX string per step (same length as [steps], "" = no math); stepFormulas[0] == formula. */
    val stepFormulas: List<String> = emptyList(),
)

class CheckResponse(
    val done: Boolean, val feedback: String, val formula: String, val formulaPng: ByteArray?,
    val focusBox: IntArray?, val mistake: String,
)

sealed class HintError(val message: String) {
    /** Retrying won't help until the app is reconfigured. */
    class Auth : HintError("bad token")
    class NotConfigured : HintError("backend URL/token missing")
    class TooLarge : HintError("payload too large")
    class NoInput : HintError("nothing to send")
    /** Worth retrying later. */
    class Retryable(message: String) : HintError(message)

    val retryable: Boolean get() = this is Retryable
}

class HintResult<T> private constructor(val ok: T?, val error: HintError?) {
    companion object {
        fun <T> ok(r: T) = HintResult<T>(r, null)
        fun <T> err(e: HintError) = HintResult<T>(null, e)
    }
}

object HintClient {
    val baseUrl: String get() = BuildConfig.API_URL.trimEnd('/')
    private val token: String get() = BuildConfig.API_TOKEN
    val isConfigured: Boolean get() = baseUrl.isNotBlank() && token.isNotBlank()

    /** Blocking; call from Dispatchers.IO. */
    fun hint(req: HintRequest, timeoutMs: Int = 15_000): HintResult<HintResponse> {
        if (req.photo == null && req.audio == null) return HintResult.err(HintError.NoInput())
        val meta = JSONObject()
            .put("locale", req.locale).put("mode", req.mode).put("ts", req.ts)
            .put("previous", JSONArray(req.previous.takeLast(5)))
            .put("card_id", req.cardId)
            .put("recent", req.recent)
            .put("goal", req.goal.take(200))
            .put("mistakes", JSONArray(req.mistakes.takeLast(10)))
            .put("ask", req.ask)
        return post("/api/ai/hint", req.photo, req.audio, meta, timeoutMs) { json, conn ->
            val step = json.optString("next_step").trim()
            if (step.isEmpty()) null
            else {
                val steps = strings(json.optJSONArray("steps")).take(6).ifEmpty { listOf(step) }
                val sf = json.optJSONArray("step_formulas")
                val formulas = steps.indices.map { sf?.optString(it, "")?.trim().orEmpty() }
                val formula = json.optString("formula").trim()
                HintResponse(
                    step, json.optJSONObject("card") ?: JSONObject(), serverTiming(conn),
                    formula, decodePng(json.optString("formula_png")) ?: if (formula.isNotEmpty()) Latex.png(formula) else null,
                    steps, box(json), json.optString("mistake").trim(), json.optString("recap").trim(),
                    json.optString("intent").trim(), formulas,
                )
            }
        }
    }

    /** "Done, check me": the current photo against the quest step. Blocking. */
    fun check(
        photo: ByteArray, locale: String, goal: String, mistakes: List<String>,
        title: String, steps: List<String>, index: Int, timeoutMs: Int = 15_000,
    ): HintResult<CheckResponse> {
        val meta = JSONObject().put("locale", locale).put("goal", goal.take(200))
            .put("mistakes", JSONArray(mistakes.takeLast(10)))
            .put("quest", JSONObject().put("title", title).put("steps", JSONArray(steps)).put("index", index))
        return post("/api/ai/check", photo, null, meta, timeoutMs) { json, _ ->
            val formula = json.optString("formula").trim()
            CheckResponse(
                json.optBoolean("done"), json.optString("feedback").trim(),
                formula, decodePng(json.optString("formula_png")) ?: if (formula.isNotEmpty()) Latex.png(formula) else null,
                box(json), json.optString("mistake").trim(),
            )
        }
    }

    /** Short speech (WAV, ≤ 20 s) → text; "" when nothing intelligible. kind = "task" | "goal". Blocking. */
    fun dump(audio: EncodedAudio, kind: String, locale: String, timeoutMs: Int = 10_000): HintResult<String> =
        post("/api/ai/dump", null, audio, JSONObject().put("locale", locale).put("kind", kind), timeoutMs) { json, _ ->
            json.optString("text").trim()
        }

    private fun strings(a: JSONArray?): List<String> =
        if (a == null) emptyList() else (0 until a.length()).map { a.optString(it).trim() }.filter { it.isNotEmpty() }

    private fun box(json: JSONObject): IntArray? {
        val a = json.optJSONArray("focus_box")?.takeIf { it.length() == 4 } ?: return null
        val out = IntArray(4)
        for (i in 0 until 4) {
            val d = a.optDouble(i, Double.NaN)
            if (d.isNaN()) return null
            out[i] = d.toInt()
        }
        return out
    }

    /** One multipart POST with the shared auth and error mapping. [parse] returns null for an unusable body. */
    private fun <T> post(
        path: String, photo: ByteArray?, audio: EncodedAudio?, meta: JSONObject, timeoutMs: Int,
        parse: (JSONObject, HttpURLConnection) -> T?,
    ): HintResult<T> {
        if (!isConfigured) return HintResult.err(HintError.NotConfigured())
        val boundary = "fl-" + UUID.randomUUID()
        val body = ByteArrayOutputStream().also { out ->
            val w = DataOutputStream(out)
            fun part(name: String, filename: String?, type: String?, data: ByteArray) {
                w.writeBytes("--$boundary\r\n")
                w.writeBytes("Content-Disposition: form-data; name=\"$name\"" + (filename?.let { "; filename=\"$it\"" } ?: "") + "\r\n")
                if (type != null) w.writeBytes("Content-Type: $type\r\n")
                w.writeBytes("\r\n"); w.write(data); w.writeBytes("\r\n")
            }
            photo?.let { part("photo", "photo.jpg", "image/jpeg", it) }
            audio?.let { part("audio", "audio.${it.ext}", it.mime, it.bytes) }
            part("meta", null, null, meta.toString().toByteArray(Charsets.UTF_8))
            w.writeBytes("--$boundary--\r\n")
            w.flush()
        }.toByteArray()

        val conn = try {
            (URL("$baseUrl$path").openConnection() as HttpURLConnection)
        } catch (e: IOException) {
            return HintResult.err(HintError.Retryable("bad url: ${e.message}"))
        }
        return try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 5_000
            conn.readTimeout = timeoutMs
            conn.doOutput = true
            conn.setFixedLengthStreamingMode(body.size)
            conn.setRequestProperty("Authorization", "Bearer $token")
            conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            conn.outputStream.use { it.write(body) }
            when (val code = conn.responseCode) {
                200 -> parse(JSONObject(conn.inputStream.bufferedReader().readText()), conn)
                    ?.let { HintResult.ok(it) } ?: HintResult.err(HintError.Retryable("empty response"))
                401 -> HintResult.err(HintError.Auth())
                413 -> HintResult.err(HintError.TooLarge())
                422 -> HintResult.err(HintError.NoInput())
                else -> HintResult.err(HintError.Retryable("HTTP $code"))
            }
        } catch (e: SocketTimeoutException) {
            HintResult.err(HintError.Retryable("timeout"))
        } catch (e: IOException) {
            HintResult.err(HintError.Retryable("network: ${e.javaClass.simpleName}"))
        } catch (e: org.json.JSONException) {
            HintResult.err(HintError.Retryable("bad json"))
        } finally {
            conn.disconnect()
        }
    }

    /** Base64 PNG → bytes, or null if empty / not a PNG. */
    private fun decodePng(b64: String): ByteArray? {
        if (b64.isEmpty() || b64 == "null") return null
        val b = try { Base64.decode(b64, Base64.DEFAULT) } catch (e: IllegalArgumentException) { return null }
        return b.takeIf { it.size > 8 && it[0] == 0x89.toByte() && it[1] == 'P'.code.toByte() }
    }

    /** `Server-Timing: ai;dur=2345` if the backend sends it (backend task B2). */
    private fun serverTiming(conn: HttpURLConnection): Long? =
        conn.getHeaderField("Server-Timing")?.let { Regex("dur=([0-9.]+)").find(it)?.groupValues?.get(1)?.toDoubleOrNull()?.toLong() }

    /** WAV bytes or null. Blocking. */
    fun tts(text: String, timeoutMs: Int = 4_000): ByteArray? {
        if (!isConfigured) return null
        val conn = runCatching { URL("$baseUrl/api/ai/tts").openConnection() as HttpURLConnection }.getOrNull() ?: return null
        return try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 3_000
            conn.readTimeout = timeoutMs
            conn.doOutput = true
            conn.setRequestProperty("Authorization", "Bearer $token")
            conn.setRequestProperty("Content-Type", "application/json")
            conn.outputStream.use { it.write(JSONObject().put("text", text.take(500)).toString().toByteArray()) }
            if (conn.responseCode == 200) conn.inputStream.readBytes() else null
        } catch (e: IOException) {
            null
        } finally {
            conn.disconnect()
        }
    }

    /** Returns null when healthy, otherwise a short reason. Blocking. */
    fun health(): String? {
        if (!isConfigured) return "not configured"
        val conn = runCatching { URL("$baseUrl/api/health").openConnection() as HttpURLConnection }.getOrNull() ?: return "bad url"
        return try {
            conn.connectTimeout = 4_000
            conn.readTimeout = 4_000
            if (conn.responseCode == 200) null else "HTTP ${conn.responseCode}"
        } catch (e: IOException) {
            e.javaClass.simpleName
        } finally {
            conn.disconnect()
        }
    }
}
