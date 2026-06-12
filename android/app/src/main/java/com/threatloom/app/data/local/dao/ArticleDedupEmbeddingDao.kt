package com.threatloom.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.threatloom.app.data.local.entity.ArticleDedupEmbeddingEntity

/**
 * A dedup embedding for an article that has already been summarized, used as a
 * reference to detect near-duplicate coverage arriving in a later run.
 */
data class DedupReference(
    val article_id: Long,
    val embedding: ByteArray,
    val published_date: String?,
    val fetched_date: String?,
    val source_name: String?
)

@Dao
interface ArticleDedupEmbeddingDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(embedding: ArticleDedupEmbeddingEntity)

    /**
     * Dedup embeddings for articles fetched on/after [cutoff] that already have a real
     * (non-failed) summary and are not themselves marked as duplicates. These are the
     * "already processed" articles a new candidate is compared against.
     */
    @Query("""
        SELECT de.article_id AS article_id, de.embedding AS embedding,
               a.published_date AS published_date, a.fetched_date AS fetched_date,
               s.name AS source_name
        FROM article_dedup_embeddings de
        JOIN articles a ON a.id = de.article_id
        JOIN sources s ON s.id = a.source_id
        JOIN summaries sm ON sm.article_id = a.id
        WHERE a.fetched_date >= :cutoff
          AND a.duplicate_of_id IS NULL
          AND sm.model_used != 'failed'
    """)
    suspend fun getReferencesSince(cutoff: String): List<DedupReference>

    @Query("DELETE FROM article_dedup_embeddings WHERE article_id = :articleId")
    suspend fun deleteByArticleId(articleId: Long)

    @Query("DELETE FROM article_dedup_embeddings WHERE article_id IN (SELECT id FROM articles WHERE fetched_date >= :cutoffDate)")
    suspend fun deleteFetchedSince(cutoffDate: String)
}
