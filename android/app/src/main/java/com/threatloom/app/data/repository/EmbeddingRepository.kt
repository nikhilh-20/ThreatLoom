package com.threatloom.app.data.repository

import com.threatloom.app.data.local.dao.EmbeddingDao
import com.threatloom.app.data.local.entity.EmbeddingEntity
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class EmbeddingRepository @Inject constructor(
    private val embeddingDao: EmbeddingDao
) {
    suspend fun getAll(): List<Pair<Long, ByteArray>> {
        return embeddingDao.getAll().map { it.articleId to it.embedding }
    }

    suspend fun getByModel(modelUsed: String): List<Pair<Long, ByteArray>> {
        return embeddingDao.getByModel(modelUsed).map { it.articleId to it.embedding }
    }

    suspend fun getByModelSinceDate(modelUsed: String, sinceDate: String): List<Pair<Long, ByteArray>> {
        return embeddingDao.getByModelSinceDate(modelUsed, sinceDate).map { it.articleId to it.embedding }
    }

    suspend fun upsert(articleId: Long, embedding: ByteArray, modelUsed: String) {
        embeddingDao.upsert(EmbeddingEntity(articleId = articleId, embedding = embedding, modelUsed = modelUsed))
    }

    suspend fun countAll() = embeddingDao.countAll()
}
