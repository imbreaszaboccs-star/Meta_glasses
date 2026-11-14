package com.focuslens.focuslens

import android.app.Application
import com.focuslens.focuslens.focus.FocusEngine
import com.focuslens.focuslens.focus.RetryWorker

/**
 * Owns the [FocusEngine] for the life of the process, so a focus block survives the Activity
 * (screen off, app swiped away) as long as [com.focuslens.focuslens.focus.FocusService] holds the process.
 */
class FocusLensApp : Application() {
    lateinit var engine: FocusEngine
        private set

    override fun onCreate() {
        super.onCreate()
        engine = FocusEngine(this)
        engine.initSdk() // no-op until Bluetooth permission is granted
        if (engine.cards.pendingIds().isNotEmpty()) RetryWorker.enqueue(this)
    }
}
