package com.focuslens.focuslens.focus

import android.graphics.Bitmap
import org.json.JSONArray
import org.json.JSONObject

/** The crop picture of a screen, if it has one (RemoteLink uploads it and passes the hash to [toJson]). */
fun HudScreen.cropBitmap(): Bitmap? = when (this) {
    is HudScreen.Hint -> crop
    is HudScreen.Quest -> crop
    else -> null
}

/** The HUD as the "view model" the web app on the glasses renders (strings are already localized/formatted). */
/** [attention]: the quiet "you may be stuck" dot (nudge level 1). */
fun HudScreen.toJson(cropHash: String?, attention: Boolean = false): JSONObject {
    val o = JSONObject()
    fun put(k: String, v: String?) { if (!v.isNullOrEmpty()) o.put(k, v) }
    when (this) {
        is HudScreen.Idle -> {
            o.put("s", "idle")
            if (goal.isNotBlank()) put("anchor", Strings.anchor(goal))
            put("title", if (paused) "${Strings.paused} · ${Strings.pausedHint}" else if (compact) "●" else "● ${Strings.appName} · ${Strings.idleHint}")
        }
        is HudScreen.Thinking -> { o.put("s", "thinking"); put("title", text) }
        is HudScreen.Hint -> {
            o.put("s", "hint")
            if (goal.isNotBlank()) put("anchor", Strings.anchor(goal))
            put("text", text); put("crop", cropHash); put("formula", tex)
            if (total > 1) put("progress", "$index/$total")
            put("footer", Strings.footerNext)
        }
        is HudScreen.Quest -> {
            o.put("s", "quest")
            if (goal.isNotBlank()) put("anchor", Strings.anchor(goal))
            val shown = index + 1
            put("text", step); put("sub", feedback); put("crop", cropHash); put("formula", tex)
            put("progress", "▰".repeat(shown) + "▱".repeat(maxOf(0, total - shown)) + " $shown/$total")
            put("footer", Strings.footerQuest)
        }
        is HudScreen.Blocks -> {
            o.put("s", "blocks")
            o.put("rows", JSONArray().also { a -> rows.forEach { a.put(JSONObject().put("title", it.title).put("time", it.time)) } })
            o.put("selected", selected)
            if (rows.isEmpty()) put("text", Strings.noBlocks)
            put("footer", Strings.footerBlocks)
        }
        is HudScreen.BlockDetail -> {
            o.put("s", "detail")
            put("title", title); put("text", step); put("formula", tex); put("sub", correction)
            put("footer", Strings.footerBack)
        }
        is HudScreen.Message -> { o.put("s", "message"); put("title", title); put("footer", body ?: Strings.footerClose) }
        is HudScreen.Nudge -> { o.put("s", "nudge"); put("title", title); put("footer", Strings.footerNudge) }
    }
    if (attention) o.put("attention", true)
    return o
}
