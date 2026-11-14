package com.focuslens.focuslens.focus

/** Native-side text (HUD + notification). The app UI is English; only the hint language is configurable. */
object Strings {
    const val appName = "FocusLens"
    const val idleHint = "pinch = help"
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
    const val itWorks = "It works."
    const val batteryLow = "Glasses battery is low."

    // notification
    const val channelFocus = "Focus"
    const val channelAlerts = "Alerts"
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

    fun modeName(mode: String) = when (mode) {
        "homework" -> "Homework"
        "desk" -> "Desk"
        else -> "Lecture"
    }
}
