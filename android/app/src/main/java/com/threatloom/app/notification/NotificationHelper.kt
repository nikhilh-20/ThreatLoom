package com.threatloom.app.notification

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.threatloom.app.MainActivity
import com.threatloom.app.R

object NotificationHelper {

    const val ARTICLE_CHANNEL_ID = "threatloom_articles"
    const val EXTRA_ARTICLE_ID = "extra_article_id"
    private const val NOTIFICATION_ID = 1001

    fun createChannel(context: Context) {
        val channel = NotificationChannel(
            ARTICLE_CHANNEL_ID,
            "Malware Articles",
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = "Daily Malware threat intelligence article picks"
        }
        context.getSystemService(NotificationManager::class.java)
            .createNotificationChannel(channel)
    }

    fun showNotification(context: Context, articleId: Long, title: String) {
        // Plain extra rather than a URI deep link: NavController restarts the whole task when it
        // handles a deep link whose intent carries FLAG_ACTIVITY_NEW_TASK, which the framework
        // forces onto any PendingIntent started from a non-Activity context.
        val intent = Intent(context, MainActivity::class.java).apply {
            putExtra(EXTRA_ARTICLE_ID, articleId)
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            articleId.toInt(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, ARTICLE_CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Threat Loom")
            .setContentText(title)
            .setStyle(NotificationCompat.BigTextStyle().bigText(title))
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        context.getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, notification)
    }
}
