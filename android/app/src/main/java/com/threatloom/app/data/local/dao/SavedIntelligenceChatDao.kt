package com.threatloom.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.threatloom.app.data.local.entity.SavedIntelligenceChatEntity

@Dao
interface SavedIntelligenceChatDao {
    @Query("SELECT * FROM saved_intelligence_chats ORDER BY updated_date DESC")
    suspend fun getAll(): List<SavedIntelligenceChatEntity>

    @Query("SELECT * FROM saved_intelligence_chats WHERE id = :id")
    suspend fun getById(id: Long): SavedIntelligenceChatEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: SavedIntelligenceChatEntity): Long

    @Query("DELETE FROM saved_intelligence_chats WHERE id = :id")
    suspend fun deleteById(id: Long)
}
