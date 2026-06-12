package com.threatloom.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.threatloom.app.data.local.entity.EmbeddingEntity

@Dao
interface EmbeddingDao {
    @Query("SELECT * FROM article_embeddings")
    suspend fun getAll(): List<EmbeddingEntity>

    @Query("SELECT * FROM article_embeddings WHERE model_used = :modelUsed")
    suspend fun getByModel(modelUsed: String): List<EmbeddingEntity>

    @Query("""
        SELECT ae.* FROM article_embeddings ae
        JOIN articles a ON a.id = ae.article_id
        WHERE ae.model_used = :modelUsed
          AND COALESCE(a.published_date, a.fetched_date) >= :sinceDate
    """)
    suspend fun getByModelSinceDate(modelUsed: String, sinceDate: String): List<EmbeddingEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(embedding: EmbeddingEntity)

    @Query("SELECT COUNT(*) FROM article_embeddings")
    suspend fun countAll(): Int

    @Query("DELETE FROM article_embeddings WHERE article_id = :articleId")
    suspend fun deleteByArticleId(articleId: Long)

    @Query("DELETE FROM article_embeddings WHERE article_id IN (SELECT id FROM articles WHERE fetched_date >= :cutoffDate)")
    suspend fun deleteFetchedSince(cutoffDate: String)
}
