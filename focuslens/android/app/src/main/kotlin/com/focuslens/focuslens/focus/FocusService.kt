package com.focuslens.focuslens.focus

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.focuslens.focuslens.FocusLensApp
import com.focuslens.focuslens.MainActivity
import com.focuslens.focuslens.R

/**
 * Keeps the process alive. Two modes of the one foreground service:
 *  - focus block running: Help / Pause / End notification; type connectedDevice, plus microphone only when the
 *    block records the phone mic (which can only be started while the app is visible, Android 14+).
 *  - standby (glasses menu paired, no block): quiet notification, type connectedDevice, so the relay connection
 *    ([RemoteLink]) survives and a block can be started from the glasses with the phone locked.
 * A lifecycle wrapper only (same pattern as Meta's BirdSpotter RealtimeSessionService): the state is
 * [FocusEngine]'s.
 */
class FocusService : Service(), EngineListener {
    private var reading: List<Any?>? = null
    private var fgType = -1 // foreground service type in force, -1 = not in the foreground yet

    private val engine get() = (application as FocusLensApp).engine
    private val remote get() = (application as FocusLensApp).remote

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_HELP -> engine.help("notification")
            ACTION_PAUSE -> engine.pauseMic()
            ACTION_RESUME -> engine.resumeMic()
            ACTION_END -> engine.stopFocus("notification")
            ACTION_RECONNECT -> engine.reconnectGlasses()
            ACTION_OFF -> Thread { remote.unpair() }.start() // forgets the pairing → update() ends standby
        }
        // (Re)registering calls onStatus at once → update() → startForeground within the 5 s deadline.
        reading = null
        engine.removeListener(this)
        engine.addListener(this)
        return START_NOT_STICKY
    }

    override fun onStatus(status: Map<String, Any?>) {
        val focus = status["focus"] as Map<*, *>
        val hint = status["hint"] as Map<*, *>
        // Redraw only when what the notification shows changes (status ticks every second).
        val r = listOf(focus["running"], focus["audioSource"], focus["mode"], focus["micPaused"], hint["phase"], hint["lastText"],
            (status["config"] as Map<*, *>)["language"], remote.enabled)
        if (r == reading) return
        reading = r
        update(status)
    }

    /** Standby, focus, or neither (stop); retypes the foreground service when the microphone is added or dropped. */
    fun update(status: Map<String, Any?> = engine.status()) {
        val focus = status["focus"] as Map<*, *>
        val running = focus["running"] == true
        if (!running && !remote.enabled) {
            fgType = -1
            stopSelf()
            return
        }
        val type = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or
            (if (running && focus["audioSource"] == "phone") ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0)
        val n = if (running) build(status) else standby()
        try {
            if (type != fgType) { startForeground(ID_FOCUS, n, type); fgType = type }
            else NotificationManagerCompat.from(this).notify(ID_FOCUS, n)
        } catch (e: Exception) {
            // Platform refused (e.g. mic type from the background). The engine keeps running while the process lives.
            Log.w("FocusLens", "startForeground refused: $e")
            if (fgType == -1) stopSelf()
            else runCatching { NotificationManagerCompat.from(this).notify(ID_FOCUS, n) }
        }
    }

    override fun onDestroy() {
        engine.removeListener(this)
        if (instance === this) instance = null
        super.onDestroy()
    }

    private fun standby(): Notification {
        val s = Strings
        Notifications.channels(this)
        return NotificationCompat.Builder(this, CHANNEL_STANDBY)
            .setSmallIcon(R.drawable.ic_stat_focus)
            .setContentTitle(s.standbyTitle)
            .setContentText(s.standbyBody)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setContentIntent(Notifications.openApp(this))
            .addAction(0, s.actionTurnOff, action(ACTION_OFF, 4))
            .build()
    }

    private fun build(status: Map<String, Any?>): Notification {
        val s = Strings
        Notifications.channels(this)
        val focus = status["focus"] as Map<*, *>
        val hint = status["hint"] as Map<*, *>
        val paused = focus["micPaused"] == true
        val mins = ((status["config"] as Map<*, *>)["bufferSec"] as Int + 59) / 60
        val title = "FocusLens · ${s.modeName(focus["mode"] as String)}"
        val text = when {
            hint["phase"] == "THINKING" -> s.thinking
            hint["lastText"] != null -> hint["lastText"] as String
            paused -> s.micPaused
            else -> s.listening.format(mins)
        }
        return NotificationCompat.Builder(this, CHANNEL_FOCUS)
            .setSmallIcon(R.drawable.ic_stat_focus)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setWhen((focus["startedAt"] as? Long) ?: System.currentTimeMillis())
            .setUsesChronometer(true)
            .setShowWhen(true)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(Notifications.openApp(this))
            .addAction(0, s.actionHelp, action(ACTION_HELP, 1))
            .addAction(0, if (paused) s.actionResume else s.actionPause, action(if (paused) ACTION_RESUME else ACTION_PAUSE, 2))
            .addAction(0, s.actionEnd, action(ACTION_END, 3))
            .build()
    }

    private fun action(a: String, code: Int) = PendingIntent.getService(
        this, code, Intent(this, FocusService::class.java).setAction(a),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    companion object {
        const val CHANNEL_FOCUS = "focus"
        const val CHANNEL_ALERTS = "alerts"
        const val CHANNEL_STANDBY = "standby"
        const val ID_FOCUS = 1
        const val ID_INTERRUPTED = 2
        const val ACTION_HELP = "com.focuslens.HELP"
        const val ACTION_PAUSE = "com.focuslens.PAUSE"
        const val ACTION_RESUME = "com.focuslens.RESUME"
        const val ACTION_END = "com.focuslens.END"
        const val ACTION_RECONNECT = "com.focuslens.RECONNECT"
        const val ACTION_OFF = "com.focuslens.OFF"

        @Volatile private var instance: FocusService? = null
        val active: Boolean get() = instance != null

        /** Standby is the same service: it decides between standby / focus / stop itself. */
        fun standby(ctx: Context) = start(ctx)

        /** Pairing or a block changed: let a running service re-evaluate (never starts one). */
        fun recheck() {
            Handler(Looper.getMainLooper()).post { instance?.update() }
        }

        fun start(ctx: Context) {
            try {
                ContextCompat.startForegroundService(ctx, Intent(ctx, FocusService::class.java))
            } catch (e: Exception) {
                Log.w("FocusLens", "FocusService start refused: $e")
            }
        }
    }
}

object Notifications {
    fun channels(ctx: Context, s: Strings = Strings) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(FocusService.CHANNEL_FOCUS, s.channelFocus, NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(NotificationChannel(FocusService.CHANNEL_STANDBY, s.channelStandby, NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(NotificationChannel(FocusService.CHANNEL_ALERTS, s.channelAlerts, NotificationManager.IMPORTANCE_DEFAULT))
    }

    fun openApp(ctx: Context): PendingIntent = PendingIntent.getActivity(
        ctx, 0, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    fun showInterrupted(ctx: Context, s: Strings = Strings) {
        channels(ctx)
        val reconnect = PendingIntent.getService(
            ctx, 10, Intent(ctx, FocusService::class.java).setAction(FocusService.ACTION_RECONNECT),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = NotificationCompat.Builder(ctx, FocusService.CHANNEL_ALERTS)
            .setSmallIcon(R.drawable.ic_stat_focus)
            .setContentTitle(s.interruptedTitle)
            .setContentText(s.interruptedBody)
            .setStyle(NotificationCompat.BigTextStyle().bigText(s.interruptedBody))
            .setContentIntent(openApp(ctx))
            .setAutoCancel(true)
            .addAction(0, s.actionReconnect, reconnect)
            .build()
        runCatching { NotificationManagerCompat.from(ctx).notify(FocusService.ID_INTERRUPTED, n) }
    }

    fun cancelInterrupted(ctx: Context) = NotificationManagerCompat.from(ctx).cancel(FocusService.ID_INTERRUPTED)
}
