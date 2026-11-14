package com.focuslens.focuslens.focus

/** Native-side text (HUD + notification). The app UI is English; only the hint language is configurable. */
object Strings {
    const val appName = "FocusLens"
    const val idleHint = "pinch help · ↑ blocks · ↓ note"
    const val paused = "Paused"
    const val pausedHint = "microphone is off"
    const val looking = "Looking…"
    const val listeningOnly = "Listening…"
    const val stillThinking = "Still thinking…"
    const val nextStepLoading = "Finding the next step…"
    const val footerNext = "→ next · pinch close"
    const val footerClose = "pinch close"
    const val savedLater = "Saved. I'll try again later."
    const val nothingToSend = "Nothing to send."
    const val nothingToSendBody = "The mic is off and the camera isn't running."
    const val serverRefused = "The server refused access."
    const val serverRefusedBody = "Open FocusLens on your phone."
    const val nextFailed = "Couldn't get the next step."
    const val nextFailedBody = "Swipe right to try again."
    const val noBlocks = "No blocks yet today."
    const val footerBlocks = "↑↓ move · pinch open · ← back"
    const val footerBack = "← back"
    const val itWorks = "It works."
    const val batteryLow = "Glasses battery is low."
    const val noAudio = "No microphone for this block."
    const val noAudioBody = "Hints will use the photo only."

    // quest / note / goal
    const val footerQuest = "pinch = done ✓ · ←→ steps"
    const val footerCancel = "pinch cancel"
    const val checking = "Checking…"
    const val checkFailed = "Couldn't check, try again."
    const val noPhoto = "Couldn't take a photo."
    const val questDone = "✓ Done!"
    const val stepDone = "Step done"
    const val notYet = "Not yet."
    const val sayIt = "🎙 Say it…"
    const val saving = "Saving…"
    const val nothingHeard = "Nothing heard"
    const val micOff = "The microphone is off."
    const val noteSaved = "📥 Saved for later"
    const val noteFailed = "Couldn't save, try again"
    const val goalPrompt = "What are we working on?"
    const val goalPromptBody = "Say it… · pinch skip"
    fun backTo(goal: String) = "Back to: ${goal.take(40)}"
    fun anchor(goal: String) = "⚓ " + (if (goal.length > 28) goal.take(27) + "…" else goal)

    // proactive nudges
    const val nudgeKey = "💡 Key point just now"
    const val nudgeStuck = "Stuck? Try a smaller step"
    const val footerNudge = "pinch yes · ← no"

    // notification
    const val channelFocus = "Focus"
    const val channelAlerts = "Alerts"
    const val channelStandby = "Glasses menu"
    const val listening = "Listening to the last %d min"
    const val micPaused = "Microphone paused"
    const val actionHelp = "Help"
    const val actionPause = "Pause"
    const val actionResume = "Resume"
    const val actionEnd = "End"
    const val actionReconnect = "Reconnect"
    const val interruptedTitle = "Glasses disconnected"
    const val interruptedBody = "Focus keeps running with the phone mic only. Reconnect?"
    const val thinking = "Thinking…"
    const val standbyTitle = "FocusLens ready on your glasses"
    const val standbyBody = "Start it from the glasses menu."
    const val actionTurnOff = "Turn off"

    fun modeName(mode: String) = when (mode) {
        "auto" -> "Auto"
        "homework" -> "Homework"
        "desk" -> "Desk"
        else -> "Lecture"
    }
}
