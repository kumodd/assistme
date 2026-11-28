package com.personalai.recorder.service

import android.Manifest
import android.app.Notification
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
import com.personalai.protocol.RecorderState
import com.personalai.recorder.R
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class NotificationHelper @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        const val CHANNEL_RECORDER = "recorder_status_channel"
        const val CHANNEL_ALERTS = "recorder_alerts_channel"
        const val NOTIFICATION_ID_RECORDER = 1001
        const val NOTIFICATION_ID_ALERT = 1002
        const val NOTIFICATION_ID_BLE_HOST = 1003
    }

    fun ensureChannels() {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val recorderChannel = NotificationChannel(
            CHANNEL_RECORDER,
            context.getString(R.string.notif_channel_recorder),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = context.getString(R.string.notif_channel_recorder_desc)
            setShowBadge(false)
        }
        val alertsChannel = NotificationChannel(
            CHANNEL_ALERTS,
            context.getString(R.string.notif_channel_alerts),
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = context.getString(R.string.notif_channel_alerts_desc)
        }
        nm.createNotificationChannel(recorderChannel)
        nm.createNotificationChannel(alertsChannel)
    }

    private fun createLaunchAppIntent(): Intent {
        return context.packageManager.getLaunchIntentForPackage(context.packageName)?.apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        } ?: Intent(Intent.ACTION_MAIN).apply {
            setPackage(context.packageName)
            addCategory(Intent.CATEGORY_LAUNCHER)
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
    }

    fun buildRecorderNotification(
        state: RecorderState,
        segmentCount: Int,
        interruptionReason: String?
    ): Notification {
        val openAppIntent = PendingIntent.getActivity(
            context,
            1,
            createLaunchAppIntent(),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val emergencyStopIntent = PendingIntent.getService(
            context,
            2,
            Intent(context, RecorderService::class.java).apply {
                action = RecorderService.ACTION_EMERGENCY_STOP
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val statusTitle = when (state) {
            RecorderState.SPEECH_DETECTED -> "Capturing Speech"
            RecorderState.SILENCE -> "Listening (Trailing Pause)"
            RecorderState.ACTIVE_LISTENING -> "Armed — Listening with Silero VAD"
            RecorderState.AUDIO_SILENCED -> "Microphone Silenced by System"
            RecorderState.ARMED -> "Armed Standby (Mic Paused)"
            RecorderState.STARTING -> "Starting Microphone..."
            RecorderState.STOPPING -> "Stopping Recorder..."
            else -> "Recorder Active ($state)"
        }

        val detailText = buildString {
            append("Segments saved: $segmentCount")
            if (!interruptionReason.isNullOrBlank()) {
                append(" • $interruptionReason")
            }
        }

        return NotificationCompat.Builder(context, CHANNEL_RECORDER)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle(statusTitle)
            .setContentText(detailText)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(openAppIntent)
            .addAction(
                android.R.drawable.ic_media_pause,
                "Emergency Stop",
                emergencyStopIntent
            )
            .build()
    }

    fun postAlertNotification(title: String, message: String) {
        ensureChannels()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        val openAppIntent = PendingIntent.getActivity(
            context,
            3,
            createLaunchAppIntent(),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ALERTS)
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setContentTitle(title)
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setAutoCancel(true)
            .setContentIntent(openAppIntent)
            .build()

        NotificationManagerCompat.from(context).notify(NOTIFICATION_ID_ALERT, notification)
    }

    fun buildBleHostNotification(bleStateLabel: String, pairedDeviceName: String?): Notification {
        ensureChannels()
        val openAppIntent = PendingIntent.getActivity(
            context,
            4,
            createLaunchAppIntent(),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val subtitle = if (pairedDeviceName.isNullOrBlank()) {
            "State: $bleStateLabel • Awaiting Device A pairing"
        } else {
            "State: $bleStateLabel • Peer: $pairedDeviceName"
        }

        return NotificationCompat.Builder(context, CHANNEL_RECORDER)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle("BLE Control Server Active")
            .setContentText(subtitle)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(openAppIntent)
            .build()
    }
}
