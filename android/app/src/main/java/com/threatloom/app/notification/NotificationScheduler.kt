package com.threatloom.app.notification

import android.content.Context
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.util.concurrent.TimeUnit
import kotlin.random.Random

object NotificationScheduler {

    private const val WORK_NAME = "threatloom_article_notification"
    private const val MIN_DELAY_HOURS = 3.0
    private const val MAX_DELAY_HOURS = 7.0
    private val BLACKOUT_END_HOUR = LocalTime.of(8, 0)

    fun scheduleFirst(context: Context) {
        enqueue(context, ExistingWorkPolicy.KEEP)
    }

    fun scheduleNext(context: Context) {
        enqueue(context, ExistingWorkPolicy.REPLACE)
    }

    private fun enqueue(context: Context, policy: ExistingWorkPolicy) {
        val delayMs = computeDelayMs()
        val request = OneTimeWorkRequestBuilder<ArticleNotificationWorker>()
            .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
            .build()
        WorkManager.getInstance(context)
            .enqueueUniqueWork(WORK_NAME, policy, request)
    }

    private fun computeDelayMs(): Long {
        val randomDelayHours = MIN_DELAY_HOURS + Random.nextDouble() * (MAX_DELAY_HOURS - MIN_DELAY_HOURS)
        val randomDelayMinutes = (randomDelayHours * 60).toLong()

        var candidate = LocalDateTime.now().plusMinutes(randomDelayMinutes)

        // If candidate falls in the midnight–8AM blackout window, push to 8AM + up to 60 min jitter
        if (candidate.toLocalTime().isBefore(BLACKOUT_END_HOUR)) {
            val jitterMinutes = Random.nextLong(0, 61)
            candidate = candidate.toLocalDate().atTime(BLACKOUT_END_HOUR).plusMinutes(jitterMinutes)
        }

        val nowMs = System.currentTimeMillis()
        val candidateMs = candidate.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        return maxOf(candidateMs - nowMs, 1_000L)
    }
}
