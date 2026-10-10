package com.nila.visit.remind

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import java.time.ZoneId

/**
 * Rings one reminder. Scheduling and cancelling live in [Reminders].
 */
class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Reminders.ensureChannel(context)
        val n = NotificationCompat.Builder(context, Reminders.CHANNEL)
            .setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentTitle(intent.getStringExtra("title") ?: "Reminder")
            .setContentText(intent.getStringExtra("body") ?: "")
            .setStyle(NotificationCompat.BigTextStyle().bigText(intent.getStringExtra("body") ?: ""))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()
        context.getSystemService(NotificationManager::class.java)
            .notify(intent.getIntExtra("id", 0), n)
    }
}

object Reminders {
    const val CHANNEL = "visit-reminders"

    /**
     * Silent in the lab: this app is tested on a phone carried in public, and a
     * test visit's reminders must not ring out loud. The integrated feature
     * will use a normal channel the parent can tune in Settings.
     */
    fun ensureChannel(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) != null) return
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Medicine and visit reminders", NotificationManager.IMPORTANCE_HIGH)
                .apply { setSound(null, null); enableVibration(false) }
        )
    }

    /** Schedule every reminder; returns how many were set. Replaces this visit's earlier set. */
    fun schedule(context: Context, visitId: String, reminders: List<Planner.Reminder>): Int {
        cancel(context, visitId)
        ensureChannel(context)
        val am = context.getSystemService(AlarmManager::class.java)
        val exact = Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()
        val prefs = context.getSharedPreferences("reminders", Context.MODE_PRIVATE)
        val ids = ArrayList<Int>()
        reminders.forEachIndexed { i, r ->
            val id = (visitId.hashCode() and 0xFFFF) * 1000 + i
            val pi = PendingIntent.getBroadcast(
                context, id,
                Intent(context, ReminderReceiver::class.java)
                    .putExtra("id", id).putExtra("title", r.title).putExtra("body", r.body),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            val at = r.at.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
            if (exact) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            else am.setWindow(AlarmManager.RTC_WAKEUP, at, 10 * 60_000L, pi)
            ids += id
        }
        prefs.edit().putString(visitId, ids.joinToString(",")).apply()
        return ids.size
    }

    fun cancel(context: Context, visitId: String) {
        val prefs = context.getSharedPreferences("reminders", Context.MODE_PRIVATE)
        val am = context.getSystemService(AlarmManager::class.java)
        prefs.getString(visitId, null)?.split(',')?.mapNotNull { it.toIntOrNull() }?.forEach { id ->
            val pi = PendingIntent.getBroadcast(
                context, id, Intent(context, ReminderReceiver::class.java),
                PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
            )
            if (pi != null) { am.cancel(pi); pi.cancel() }
        }
        prefs.edit().remove(visitId).apply()
    }
}
