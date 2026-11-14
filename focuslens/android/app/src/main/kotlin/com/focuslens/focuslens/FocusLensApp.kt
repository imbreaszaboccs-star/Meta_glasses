package com.focuslens.focuslens

import android.app.Application
import com.focuslens.focuslens.focus.FocusEngine
import com.focuslens.focuslens.focus.FocusService
import com.focuslens.focuslens.focus.Integrations
import com.focuslens.focuslens.focus.RemoteLink
import com.focuslens.focuslens.focus.RetryWorker

/**
 * Owns the [FocusEngine] for the life of the process, so a focus block survives the Activity
 * (screen off, app swiped away) as long as [com.focuslens.focuslens.focus.FocusService] holds the process.
 */
class FocusLensApp : Application() {
    lateinit var engine: FocusEngine
        private set
    lateinit var remote: RemoteLink
        private set

    override fun onCreate() {
        super.onCreate()
        Integrations.init(this)
        engine = FocusEngine(this)
        engine.initSdk() // no-op until Bluetooth permission is granted
        remote = RemoteLink(this, engine)
        engine.remoteStatus = remote::status
        // Paired with the glasses menu: hold the relay connection (standby service keeps the process alive).
        if (remote.enabled) { remote.start(); FocusService.standby(this) }
        if (engine.cards.pendingIds().isNotEmpty()) RetryWorker.enqueue(this)
        Integrations.kickSync() // push notes that were waiting for the network
    }
}
