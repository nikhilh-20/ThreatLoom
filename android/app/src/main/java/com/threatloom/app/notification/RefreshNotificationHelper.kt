package com.threatloom.app.notification

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.work.WorkManager
import com.threatloom.app.MainActivity
import com.threatloom.app.R
import java.util.UUID

/**
 * Builds the ongoing foreground-service notification shown while [com.threatloom.app.worker.RefreshPipelineWorker]
 * is running. Separate channel from [NotificationHelper]'s daily article-pick notification.
 */
object RefreshNotificationHelper {

    const val CHANNEL_ID = "threatloom_refresh_progress"
    const val NOTIFICATION_ID = 2001

    fun createChannel(context: Context) {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Refresh Progress",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Shows progress while ThreatLoom refreshes articles in the background"
            setShowBadge(false)
        }
        context.getSystemService(NotificationManager::class.java)
            .createNotificationChannel(channel)
    }

    fun build(
        context: Context,
        workId: UUID,
        text: String,
        current: Int = 0,
        total: Int = 0,
        indeterminate: Boolean = false
    ): Notification {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val contentIntent = PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val cancelIntent = WorkManager.getInstance(context).createCancelPendingIntent(workId)

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Refreshing Threat Loom")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(contentIntent)
            .addAction(0, "Cancel", cancelIntent)

        if (total > 0 || indeterminate) {
            builder.setProgress(total, current, indeterminate)
        }

        return builder.build()
    }
}
