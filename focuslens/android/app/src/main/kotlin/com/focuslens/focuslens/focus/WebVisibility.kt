package com.focuslens.focuslens.focus

/** Is the web app on screen on the glasses? It reports `web_seen {visible}` every ~20 s; no report for [WINDOW_MS] = hidden. */
class WebVisibility(private val now: () -> Long) {
    private var until = 0L
    val visible: Boolean get() = now() < until

    fun seen(visible: Boolean) { until = if (visible) now() + WINDOW_MS else 0L }

    companion object {
        const val WINDOW_MS = 45_000L // ponytail: calibration knob
    }
}
