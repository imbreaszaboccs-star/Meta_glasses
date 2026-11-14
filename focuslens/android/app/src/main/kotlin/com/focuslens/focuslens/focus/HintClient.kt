package com.focuslens.focuslens.focus

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

/** Backend contract: see backend/README.md. */
class HintRequest(
    val photo: ByteArray?,
    val audio: EncodedAudio?,
    val locale: String,
    val mode: String,
    val ts: String,
    val previous: List<String>,
    val cardId: String,
)

class HintResponse(val nextStep: String, val card: JSONObject, val serverMs: Long?)

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

class HintResult private constructor(val ok: HintResponse?, val error: HintError?) {
    companion object {
        fun ok(r: HintResponse) = HintResult(r, null)
        fun err(e: HintError) = HintResult(null, e)
    }
}

object HintClient {
    val baseUrl: String get() = BuildConfig.API_URL.trimEnd('/')
    private val token: String get() = BuildConfig.API_TOKEN
    val isConfigured: Boolean get() = baseUrl.isNotBlank() && token.isNotBlank()

    /** Blocking; call from Dispatchers.IO. */
    fun hint(req: HintRequest, timeoutMs: Int = 15_000): HintResult {
        if (!isConfigured) return HintResult.err(HintError.NotConfigured())
        if (req.photo == null && req.audio == null) return HintResult.err(HintError.NoInput())
        val meta = JSONObject()
            .put("locale", req.locale).put("mode", req.mode).put("ts", req.ts)
            .put("previous", JSONArray(req.previous.takeLast(5)))
            .put("card_id", req.cardId)
        val boundary = "fl-" + UUID.randomUUID()
        val body = ByteArrayOutputStream().also { out ->
            val w = DataOutputStream(out)
            fun part(name: String, filename: String?, type: String?, data: ByteArray) {
                w.writeBytes("--$boundary\r\n")
                w.writeBytes("Content-Disposition: form-data; name=\"$name\"" + (filename?.let { "; filename=\"$it\"" } ?: "") + "\r\n")
                if (type != null) w.writeBytes("Content-Type: $type\r\n")
                w.writeBytes("\r\n"); w.write(data); w.writeBytes("\r\n")
            }
            req.photo?.let { part("photo", "photo.jpg", "image/jpeg", it) }
            req.audio?.let { part("audio", "audio.${it.ext}", it.mime, it.bytes) }
            part("meta", null, null, meta.toString().toByteArray(Charsets.UTF_8))
            w.writeBytes("--$boundary--\r\n")
            w.flush()
        }.toByteArray()

        val conn = try {
            (URL("$baseUrl/hint").openConnection() as HttpURLConnection)
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
            val code = conn.responseCode
            when (code) {
                200 -> {
                    val json = JSONObject(conn.inputStream.bufferedReader().readText())
                    val step = json.optString("next_step").trim()
                    if (step.isEmpty()) HintResult.err(HintError.Retryable("empty next_step"))
                    else HintResult.ok(HintResponse(step, json.optJSONObject("card") ?: JSONObject(), serverTiming(conn)))
                }
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

    /** `Server-Timing: ai;dur=2345` if the backend sends it (backend task B2). */
    private fun serverTiming(conn: HttpURLConnection): Long? =
        conn.getHeaderField("Server-Timing")?.let { Regex("dur=([0-9.]+)").find(it)?.groupValues?.get(1)?.toDoubleOrNull()?.toLong() }

    /** WAV bytes or null. Blocking. */
    fun tts(text: String, timeoutMs: Int = 4_000): ByteArray? {
        if (!isConfigured) return null
        val conn = runCatching { URL("$baseUrl/tts").openConnection() as HttpURLConnection }.getOrNull() ?: return null
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
        val conn = runCatching { URL("$baseUrl/health").openConnection() as HttpURLConnection }.getOrNull() ?: return "bad url"
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
