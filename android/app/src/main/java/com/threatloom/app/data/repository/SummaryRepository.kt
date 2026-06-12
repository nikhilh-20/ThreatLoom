package com.threatloom.app.data.repository

import com.threatloom.app.data.local.dao.SummaryDao
import com.threatloom.app.data.local.entity.SummaryEntity
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SummaryRepository @Inject constructor(
    private val summaryDao: SummaryDao
) {
    suspend fun upsert(
        articleId: Long, summaryText: String, keyPoints: String?,
        tags: String?, modelUsed: String?
    ) {
        summaryDao.upsert(SummaryEntity(
            articleId = articleId, summaryText = summaryText,
            keyPoints = keyPoints, tags = tags,
            modelUsed = modelUsed
        ))
    }

    suspend fun getSummaryText(articleId: Long): String? = summaryDao.getByArticleId(articleId)?.summaryText

    suspend fun countAll() = summaryDao.countAll()
    suspend fun countFailed() = summaryDao.countFailed()
    suspend fun deleteFailedSummaries() = summaryDao.deleteFailedSummaries()
}
