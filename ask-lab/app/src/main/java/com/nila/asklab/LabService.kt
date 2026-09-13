package com.nila.asklab

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder

/**
 * Keeps the process alive for the length of a run.
 *
 * The lab holds 1.2 GB of weights and the phone it runs on keeps another app in
 * front of it, so a backgrounded lab is the obvious thing for the system to
 * reclaim. It was reclaimed, twice, part-way through an eval. A foreground
 * service is the documented way to say "this process is doing work the user
 * asked for", and it costs one notification.
 */
class LabService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, notification())
        LabEngine.start(this)
        when (intent?.getStringExtra(EXTRA_RUN)) {
            "eval" -> LabEngine.runEval { stopSelf() }
            else -> intent?.getStringExtra(EXTRA_ASK)?.let { LabEngine.ask(it) }
        }
        return START_NOT_STICKY
    }

    private fun notification(): Notification {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL, "Ask lab", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL)
        } else {
            @Suppress("DEPRECATION") Notification.Builder(this)
        }
        return builder
            .setContentTitle("Nila Ask lab")
            .setContentText("Running the probe set")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val CHANNEL = "asklab"
        private const val NOTIFICATION_ID = 41
        const val EXTRA_RUN = "run"
        const val EXTRA_ASK = "ask"

        fun run(context: Context, run: String? = null, ask: String? = null) {
            val intent = Intent(context, LabService::class.java)
                .putExtra(EXTRA_RUN, run)
                .putExtra(EXTRA_ASK, ask)
            context.startForegroundService(intent)
        }
    }
}
