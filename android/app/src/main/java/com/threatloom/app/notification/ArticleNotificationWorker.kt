package com.threatloom.app.notification

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.threatloom.app.data.local.dao.ArticleDao
import com.threatloom.app.data.local.dao.ArticleWithSummaryTuple
import com.threatloom.app.domain.category.CategoryRules
import com.threatloom.app.util.DateUtils
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import org.json.JSONArray

@HiltWorker
class ArticleNotificationWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted workerParams: WorkerParameters,
    private val articleDao: ArticleDao
) : CoroutineWorker(context, workerParams) {

    override suspend fun doWork(): Result {
        try {
            val article = pickMalwareArticle()
            if (article != null) {
                NotificationHelper.showNotification(applicationContext, article.id, article.title)
            }
        } finally {
            NotificationScheduler.scheduleNext(applicationContext)
        }
        return Result.success()
    }

    private suspend fun pickMalwareArticle(): ArticleWithSummaryTuple? {
        val recent = articleDao.getTaggedArticles(DateUtils.cutoffIso(7))
        val recentMalware = recent.filter { isMalware(it) }
        if (recentMalware.isNotEmpty()) return recentMalware.random()

        val all = articleDao.getTaggedArticles(null)
        val allMalware = all.filter { isMalware(it) }
        return allMalware.randomOrNull()
    }

    private fun isMalware(article: ArticleWithSummaryTuple): Boolean {
        val tagsJson = article.tags ?: return false
        return try {
            val arr = JSONArray(tagsJson)
            (0 until arr.length()).any { i ->
                CategoryRules.tagToCategory(arr.getString(i)) == "Malware"
            }
        } catch (_: Exception) {
            false
        }
    }
}
