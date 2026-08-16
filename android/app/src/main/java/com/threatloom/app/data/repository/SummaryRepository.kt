package com.threatloom.app.data.repository

import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import com.threatloom.app.data.local.dao.SummaryDao
import com.threatloom.app.data.local.entity.SummaryEntity
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SummaryRepository @Inject constructor(
    private val summaryDao: SummaryDao
) {
    private val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
    private val tagsListAdapter = moshi.adapter<List<String>>(
        Types.newParameterizedType(List::class.java, String::class.java)
    )

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

    suspend fun updateTags(articleId: Long, tags: List<String>) {
        summaryDao.updateTags(articleId, tagsListAdapter.toJson(tags))
    }

    suspend fun countMissingTlcTags() = summaryDao.countMissingTlcTags()
    suspend fun getArticleIdsMissingTlcTags() = summaryDao.getArticleIdsMissingTlcTags()
}
