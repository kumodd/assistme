package com.personalai.primary.memory

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.personalai.primary.MainActivity
import com.personalai.primary.data.db.TaskReminderEntity
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ReminderScheduler @Inject constructor(
    @ApplicationContext private val context: Context
) {
    fun scheduleTaskReminder(task: TaskReminderEntity): Boolean {
        val dueAt = task.dueAtMs ?: return false
        val delayMs = (dueAt - System.currentTimeMillis()).coerceAtLeast(10_000L)

        val inputData = Data.Builder()
            .putString(ReminderNotificationWorker.KEY_TASK_ID, task.taskId)
            .putString(ReminderNotificationWorker.KEY_TITLE, task.title)
            .putString(ReminderNotificationWorker.KEY_NOTES, task.notes)
            .build()

        val request = OneTimeWorkRequestBuilder<ReminderNotificationWorker>()
            .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
            .setInputData(inputData)
            .build()

        WorkManager.getInstance(context).enqueueUniqueWork(
            "memory_reminder_${task.taskId}",
            ExistingWorkPolicy.REPLACE,
            request
        )
        return true
    }

    fun cancelTaskReminder(taskId: String) {
        WorkManager.getInstance(context).cancelUniqueWork("memory_reminder_$taskId")
    }
}

class ReminderNotificationWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val title = inputData.getString(KEY_TITLE) ?: return Result.success()
        val notes = inputData.getString(KEY_NOTES).orEmpty()
        val taskId = inputData.getString(KEY_TASK_ID) ?: "reminder"

        val nm = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_REMINDERS,
                "Personal AI Memory Reminders",
                NotificationManager.IMPORTANCE_HIGH
            )
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(applicationContext, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return Result.success()
        }

        val openIntent = PendingIntent.getActivity(
            applicationContext,
            taskId.hashCode(),
            Intent(applicationContext, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_REMINDERS)
            .setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentTitle("Memory Reminder: $title")
            .setContentText(notes.ifBlank { "Scheduled from your recorded conversation" })
            .setStyle(NotificationCompat.BigTextStyle().bigText(notes.ifBlank { title }))
            .setAutoCancel(true)
            .setContentIntent(openIntent)
            .build()

        NotificationManagerCompat.from(applicationContext).notify(taskId.hashCode(), notification)
        return Result.success()
    }

    companion object {
        const val CHANNEL_REMINDERS = "memory_reminders_channel"
        const val KEY_TASK_ID = "task_id"
        const val KEY_TITLE = "title"
        const val KEY_NOTES = "notes"
    }
}
