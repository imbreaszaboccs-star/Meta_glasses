package com.focuslens.focuslens.focus

/** Native-side text (HUD + notification). The app UI is English; only the hint language is configurable. */
object Strings {
    const val appName = "FocusLens"
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
    const val checking = "Checking…"
    const val checkFailed = "Couldn't check, try again."
    const val noPhoto = "Couldn't take a photo."
    const val questDone = "✓ Done!"
    const val stepDone = "Step done"
    const val notYet = "Not yet."
    const val dictating = "🎙 Listening…"
    const val footerListening = "pinch = done · ← cancel"
    const val dictateNeedsSession = "Start a session to dictate"
    const val dictateBusy = "Busy, try again in a moment"
    const val saving = "Saving…"
    const val bookmarked = "✓ Saved for later"
    const val nothingHeard = "Nothing heard"
    const val micOff = "The microphone is off."
    const val noteSaved = "📥 Saved for later"
    const val noteFailed = "Couldn't save, try again"
    const val goalPrompt = "What are we working on?"
    const val goalPromptBody = "Say it… · pinch skip"
    fun backTo(goal: String) = "Back to: ${goal.take(40)}"
    fun anchor(goal: String) = "⚓ " + (if (goal.length > 28) goal.take(27) + "…" else goal)
    fun timePulse(mins: Int) = "$mins min in session"
    fun intentLabel(intent: String) = when (intent) {
        "answer" -> "Answer"; "fix" -> "Fix this"; "recap" -> "Just said"
        "next_step" -> "Next step"; "summary" -> "Key idea"; "start" -> "Start here"; else -> ""
    }

    // Meta AI connector answers (spoken back by Meta AI)
    const val sessionStarted = "Session started"
    const val sessionStartedHint = "pinch = help now · swipe → = save for later"
    const val micPausedMsg = "Mic paused"
    const val micOnMsg = "Mic on"
    const val focusStarted = "Session started."
    const val focusStopped = "Session stopped."
    const val noHintYet = "No hint yet."
    const val helpFailed = "I couldn't get a hint."
    fun noteSaved(text: String) = "Saved: ${text.take(300)}"

    // proactive nudges
    const val nudgeKey = "Key point just now · Pinch = summary"
    const val nudgeStuck = "Stuck? Pinch = smaller step"
    const val nudgeLapse = "Lost the thread? Pinch = summary"
    const val nudgeDozing = "Still with us? Pinch = recap"
    const val footerNudge = "← dismiss"

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
