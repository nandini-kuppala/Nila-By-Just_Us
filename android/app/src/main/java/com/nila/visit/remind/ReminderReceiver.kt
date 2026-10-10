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
        val open = PendingIntent.getActivity(
            context, 0,
            Intent(context, com.nila.MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val n = NotificationCompat.Builder(context, Reminders.CHANNEL)
            .setSmallIcon(com.nila.R.drawable.ic_stat_nila)
            .setContentIntent(open)
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
     * A normal channel: a medicine reminder that makes no sound is one that
     * gets missed. The parent can quieten it in the phone's settings.
     */
    fun ensureChannel(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) != null) return
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Medicine and doctor visit reminders", NotificationManager.IMPORTANCE_HIGH)
                .apply { description = "Medicines, vaccines and next visits from a recorded doctor visit" }
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
