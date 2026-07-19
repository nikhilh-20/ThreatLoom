package com.threatloom.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.threatloom.app.data.local.entity.SavedCategoryChatEntity

@Dao
interface SavedCategoryChatDao {
    @Query("SELECT * FROM saved_category_chats WHERE category_name = :categoryName ORDER BY updated_date DESC")
    suspend fun getAllByCategory(categoryName: String): List<SavedCategoryChatEntity>

    @Query("SELECT * FROM saved_category_chats WHERE id = :id")
    suspend fun getById(id: Long): SavedCategoryChatEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: SavedCategoryChatEntity): Long

    @Query("DELETE FROM saved_category_chats WHERE id = :id")
    suspend fun deleteById(id: Long)
}
