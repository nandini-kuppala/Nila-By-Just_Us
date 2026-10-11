package com.nila.skin.ui

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.NotificationsNone
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.ExistingWorkPolicy
import androidx.work.workDataOf
import com.nila.skin.ui.components.InfoNote
import com.nila.skin.ui.components.OutlinePillButton
import com.nila.skin.ui.screens.fmtDateTime
import com.nila.skin.ui.theme.Tone
import java.util.concurrent.TimeUnit

/**
 * Gentle reminders, only when the parent asks for one.
 *
 * The check-in after a new food and the "same spot tomorrow" photo only work
 * if the parent comes back at the right time, so the screen offers a one-tap
 * reminder. They are silent (low-importance channel, no sound, no vibration)
 * and scheduled with WorkManager on the phone; nothing goes to a server.
 */
object Reminders {
    const val CHANNEL = "little_signs_gentle"

    fun schedule(context: Context, key: String, title: String, text: String, delayMs: Long) {
        val req = OneTimeWorkRequestBuilder<ReminderWorker>()
            .setInitialDelay(delayMs.coerceAtLeast(0), TimeUnit.MILLISECONDS)
            .setInputData(workDataOf("title" to title, "text" to text, "id" to key.hashCode()))
            .addTag("little-signs")
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(key, ExistingWorkPolicy.REPLACE, req)
    }

    fun allowed(context: Context): Boolean =
        Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = context.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Gentle reminders", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Check-ins you asked for: after a new food, or to photo a spot again. Silent."
                setSound(null, null)
                enableVibration(false)
            })
        }
    }
}

class ReminderWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val ctx = applicationContext
        if (!Reminders.allowed(ctx)) return Result.success()
        Reminders.ensureChannel(ctx)
        val open = ctx.packageManager.getLaunchIntentForPackage(ctx.packageName)
        val pi = open?.let { PendingIntent.getActivity(ctx, 0, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT) }
        val n = NotificationCompat.Builder(ctx, Reminders.CHANNEL)
            .setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentTitle(inputData.getString("title") ?: "Little Signs")
            .setContentText(inputData.getString("text") ?: "")
            .setStyle(NotificationCompat.BigTextStyle().bigText(inputData.getString("text") ?: ""))
            .setSilent(true)
            .setAutoCancel(true)
            .setContentIntent(pi)
            .build()
        runCatching { NotificationManagerCompat.from(ctx).notify(inputData.getInt("id", 1), n) }
        return Result.success()
    }
}

/** "Remind me at 3:40 pm": asks for notification permission the first time, then schedules. */
@Composable
fun RemindButton(key: String, label: String, title: String, text: String, atMs: Long, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var set by remember(key) { mutableStateOf(false) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) { Reminders.schedule(context, key, title, text, atMs - System.currentTimeMillis()); set = true }
    }
    if (set) InfoNote("Reminder set for ${fmtDateTime(atMs)}. It will be silent.", icon = Icons.Outlined.NotificationsNone, tone = Tone.TEAL, modifier = modifier)
    else OutlinePillButton(label, icon = Icons.Outlined.NotificationsNone, modifier = modifier, onClick = {
        if (Reminders.allowed(context)) { Reminders.schedule(context, key, title, text, atMs - System.currentTimeMillis()); set = true }
        else if (Build.VERSION.SDK_INT >= 33) ask.launch(Manifest.permission.POST_NOTIFICATIONS)
    })
}
