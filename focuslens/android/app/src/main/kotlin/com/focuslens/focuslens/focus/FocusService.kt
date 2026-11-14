package com.focuslens.focuslens.focus

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.focuslens.focuslens.FocusLensApp
import com.focuslens.focuslens.MainActivity
import com.focuslens.focuslens.R

/**
 * Keeps the process and the microphone alive while a focus block runs with the phone locked in a pocket.
 * A lifecycle wrapper only (same pattern as Meta's BirdSpotter RealtimeSessionService): the state is
 * [FocusEngine]'s. Its notification is the pocketed session's only phone surface: Help / Pause / End.
 */
class FocusService : Service(), EngineListener {
    private var reading: List<Any?>? = null

    private val engine get() = (application as FocusLensApp).engine

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_HELP -> engine.help("notification")
            ACTION_PAUSE -> engine.pauseMic()
            ACTION_RESUME -> engine.resumeMic()
            ACTION_END -> { engine.stopFocus("notification"); return START_NOT_STICKY }
            ACTION_RECONNECT -> engine.reconnectGlasses()
        }
        val status = engine.status()
        if ((status["focus"] as Map<*, *>)["running"] != true) {
            stopSelf()
            return START_NOT_STICKY
        }
        try {
            startForeground(
                ID_FOCUS, build(status),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
            )
        } catch (e: Exception) {
            // Platform refused (e.g. started from background). The engine keeps running while the process lives.
            Log.w("FocusLens", "startForeground refused: $e")
            stopSelf()
            return START_NOT_STICKY
        }
        engine.removeListener(this)
        engine.addListener(this)
        return START_NOT_STICKY
    }

    override fun onStatus(status: Map<String, Any?>) {
        val focus = status["focus"] as Map<*, *>
        if (focus["running"] != true) { stopSelf(); return }
        val hint = status["hint"] as Map<*, *>
        // Redraw only when what the notification shows changes (status ticks every second).
        val r = listOf(focus["mode"], focus["micPaused"], hint["phase"], hint["lastText"], (status["config"] as Map<*, *>)["language"])
        if (r == reading) return
        reading = r
        runCatching { NotificationManagerCompat.from(this).notify(ID_FOCUS, build(status)) }
    }

    override fun onDestroy() {
        engine.removeListener(this)
        super.onDestroy()
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
        const val ID_FOCUS = 1
        const val ID_INTERRUPTED = 2
        const val ACTION_HELP = "com.focuslens.HELP"
        const val ACTION_PAUSE = "com.focuslens.PAUSE"
        const val ACTION_RESUME = "com.focuslens.RESUME"
        const val ACTION_END = "com.focuslens.END"
        const val ACTION_RECONNECT = "com.focuslens.RECONNECT"

        fun start(ctx: Context) {
            try {
                ContextCompat.startForegroundService(ctx, Intent(ctx, FocusService::class.java))
            } catch (e: Exception) {
                Log.w("FocusLens", "FocusService start refused: $e")
            }
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, FocusService::class.java))
        }
    }
}

object Notifications {
    fun channels(ctx: Context, s: Strings = Strings) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(FocusService.CHANNEL_FOCUS, s.channelFocus, NotificationManager.IMPORTANCE_LOW))
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
