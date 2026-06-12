package com.threatloom.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.threatloom.app.data.local.entity.SummaryEntity

@Dao
interface SummaryDao {
    @Query("SELECT * FROM summaries WHERE article_id = :articleId")
    suspend fun getByArticleId(articleId: Long): SummaryEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(summary: SummaryEntity): Long

    @Query("SELECT COUNT(*) FROM summaries WHERE model_used != 'failed'")
    suspend fun countAll(): Int

    @Query("SELECT COUNT(*) FROM summaries WHERE model_used = 'failed'")
    suspend fun countFailed(): Int

    @Query("DELETE FROM summaries WHERE model_used = 'failed'")
    suspend fun deleteFailedSummaries()

    @Query("DELETE FROM summaries WHERE article_id = :articleId")
    suspend fun deleteByArticleId(articleId: Long)

    @Query("DELETE FROM summaries WHERE article_id IN (SELECT id FROM articles WHERE fetched_date >= :cutoffDate)")
    suspend fun deleteFetchedSince(cutoffDate: String)
}
