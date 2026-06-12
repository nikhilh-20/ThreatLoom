package com.threatloom.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.threatloom.app.data.local.entity.CorrelationEntity

@Dao
interface CorrelationDao {
    @Query("SELECT * FROM article_correlations WHERE article_id_1 = :articleId OR article_id_2 = :articleId")
    suspend fun getForArticle(articleId: Long): List<CorrelationEntity>

    @Insert
    suspend fun insert(correlation: CorrelationEntity): Long

    @Query("DELETE FROM article_correlations WHERE article_id_1 = :articleId OR article_id_2 = :articleId")
    suspend fun deleteForArticle(articleId: Long)

    @Query("DELETE FROM article_correlations WHERE article_id_1 IN (SELECT id FROM articles WHERE fetched_date >= :cutoffDate) OR article_id_2 IN (SELECT id FROM articles WHERE fetched_date >= :cutoffDate)")
    suspend fun deleteFetchedSince(cutoffDate: String)
}
