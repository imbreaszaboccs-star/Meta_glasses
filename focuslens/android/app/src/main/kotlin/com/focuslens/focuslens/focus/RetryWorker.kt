package com.focuslens.focuslens.focus

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import android.graphics.BitmapFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.focuslens.focuslens.FocusLensApp
import java.util.concurrent.TimeUnit
import org.json.JSONArray

/**
 * Re-sends `pending` cards when the network is back. A late answer only fills the card;
 * it is never pushed to the HUD (it is stale by then).
 */
class RetryWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result {
        val engine = (applicationContext as FocusLensApp).engine
        val store = engine.cards
        var again = false
        for (id in store.pendingIds()) {
            val card = store.read(id) ?: continue
            val req = card.optJSONObject("request")
            if (req == null) {
                store.update(id) { it.put("status", CardStore.STATUS_FAILED).put("error", "no request data") }
                engine.onCardChanged(id, CardStore.STATUS_FAILED)
                continue
            }
            val photo = req.optString("photo").takeIf { it.isNotEmpty() }?.let { store.readFile(id, it) }
            val audioName = req.optString("audio").takeIf { it.isNotEmpty() }
            val audio = audioName?.let { n -> store.readFile(id, n)?.let { EncodedAudio(it, req.optString("audioMime", "audio/wav"), n.substringAfterLast('.')) } }
            val previous = req.optJSONArray("previous") ?: JSONArray()
            val res = HintClient.hint(HintRequest(
                photo, audio, card.optString("locale", "en"), card.optString("mode", "auto"),
                req.optString("ts"), (0 until previous.length()).map { previous.getString(it) }, id,
                req.optJSONArray("recent") ?: JSONArray(),
                req.optString("goal"), req.optJSONArray("mistakes")?.let { m -> (0 until m.length()).map { m.getString(it) } } ?: emptyList(),
            ))
            val ok = res.ok
            val attempts = card.optInt("attempts") + 1
            if (ok != null) {
                val situation = ok.card.optString("situation").ifBlank { card.optString("mode") }
                val crop = withContext(Dispatchers.Default) { Crop.png(photo, ok.focusBox) }
                crop?.first?.recycle()
                val formulaOk = ok.formulaPng?.let { BitmapFactory.decodeByteArray(it, 0, it.size) != null } ?: false
                store.update(id) { c ->
                    store.applyHint(id, c, ok, ok.formulaPng.takeIf { formulaOk }, crop?.second, CardStore.isQuest(ok, situation),
                        req.optString("photo").takeIf { it.isNotEmpty() }, isNew = true)
                    ok.mistake.takeIf { it.isNotBlank() }?.let { engine.mistakes.add(it) }
                    c.put("attempts", attempts).put("late", true)
                }
                // Keep or drop the audio according to the setting (the PCM is gone, so "last60" keeps what was sent).
                if (audioName != null) {
                    val keep = engine.config.keepAudio != "none"
                    val f = java.io.File(store.dir(id), audioName)
                    if (keep) {
                        val target = "audio.${audioName.substringAfterLast('.')}"
                        f.renameTo(java.io.File(store.dir(id), target))
                        store.update(id) { it.put("audio", target) }
                    } else f.delete()
                }
                engine.onCardChanged(id, CardStore.STATUS_DONE)
            } else {
                val err = res.error!!
                val giveUp = !err.retryable || attempts >= MAX_ATTEMPTS
                val status = if (giveUp) CardStore.STATUS_FAILED else CardStore.STATUS_PENDING
                store.update(id) { it.put("attempts", attempts).put("error", err.message).put("status", status) }
                engine.onCardChanged(id, status)
                if (!giveUp) again = true
            }
        }
        return if (again) Result.retry() else Result.success()
    }

    companion object {
        private const val NAME = "focuslens-retry"
        private const val MAX_ATTEMPTS = 6

        fun enqueue(ctx: Context, now: Boolean = false) {
            val req = OneTimeWorkRequestBuilder<RetryWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .apply { if (!now) setInitialDelay(20, TimeUnit.SECONDS) }
                .build()
            WorkManager.getInstance(ctx).enqueueUniqueWork(NAME, if (now) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP, req)
        }
    }
}
