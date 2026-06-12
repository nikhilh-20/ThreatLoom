package com.threatloom.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.threatloom.app.data.local.entity.DebateEntity

@Dao
interface DebateDao {
    @Query("SELECT * FROM debates WHERE article_id = :articleId")
    suspend fun getByArticleId(articleId: Long): DebateEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(debate: DebateEntity): Long

    @Query("DELETE FROM debates WHERE article_id = :articleId")
    suspend fun deleteByArticleId(articleId: Long)
}
