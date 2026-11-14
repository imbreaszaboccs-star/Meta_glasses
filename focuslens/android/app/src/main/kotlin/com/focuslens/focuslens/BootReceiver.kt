package com.focuslens.focuslens

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.focuslens.focuslens.focus.FocusService

/** After a reboot: bring the glasses-menu standby service back if the phone is paired. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val app = context.applicationContext as FocusLensApp // Application.onCreate already started the link
        if (app.remote.enabled) FocusService.standby(app)
    }
}
