package com.threatloom.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.threatloom.app.data.local.entity.SavedChatEntity

@Dao
interface SavedChatDao {
    @Query("SELECT * FROM saved_chats WHERE article_id = :articleId ORDER BY updated_date DESC")
    suspend fun getAllByArticleId(articleId: Long): List<SavedChatEntity>

    @Query("SELECT * FROM saved_chats ORDER BY updated_date DESC")
    suspend fun getAll(): List<SavedChatEntity>

    @Query("SELECT * FROM saved_chats WHERE id = :id")
    suspend fun getById(id: Long): SavedChatEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: SavedChatEntity): Long

    @Query("DELETE FROM saved_chats WHERE id = :id")
    suspend fun deleteById(id: Long)
}
