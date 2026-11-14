package com.focuslens.focuslens.focus

import java.io.BufferedWriter
import java.io.File

/**
 * Data collection mode: one directory per session with raw.csv (all samples), win.csv (features + classes + label + state) and
 * events.csv. Times are ms since boot (SystemClock.elapsedRealtime), the same clock in all three files. Stays on the phone.
 */
class ImuRecorder(dir: File, private val clock: () -> Long) {
    @Volatile private var label = ""
    private val raw: BufferedWriter
    private val win: BufferedWriter
    private val ev: BufferedWriter
    private var closed = false

    init {
        dir.mkdirs()
        raw = File(dir, "raw.csv").bufferedWriter()
        win = File(dir, "win.csv").bufferedWriter()
        ev = File(dir, "events.csv").bufferedWriter()
        raw.write("t,ax,ay,az,gx,gy,gz,pitch,roll,yaw\n")
        win.write("t," + Features.NAMES.joinToString(",") + ",heuristic,model,label,att\n")
        ev.write("t,event\n")
    }

    @Synchronized
    fun sample(s: ImuSample) {
        if (closed) return
        raw.write("${s.t},${s.ax},${s.ay},${s.az},${s.gx},${s.gy},${s.gz},${s.pitch},${s.roll},${s.yaw}\n")
    }

    @Synchronized
    fun window(w: ImuWindow) {
        if (closed) return
        win.write("${w.t},${w.features.joinToString(",")},${w.heuristic.name.lowercase()},${w.model.name.lowercase()},$label,${w.state.name.lowercase()}\n")
        raw.flush(); win.flush()
    }

    /** pinch | hint | nudge_shown | nudge_accepted | nudge_dismissed | label:<act> */
    @Synchronized
    fun event(name: String) {
        if (closed) return
        ev.write("${clock()},$name\n"); ev.flush()
    }

    @Synchronized
    fun setLabel(l: String?) {
        label = l.orEmpty()
        event("label:$label")
    }

    @Synchronized
    fun close() {
        if (closed) return
        closed = true
        runCatching { raw.close(); win.close(); ev.close() }
    }
}
