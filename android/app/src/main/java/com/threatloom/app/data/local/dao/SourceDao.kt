package com.threatloom.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.threatloom.app.data.local.entity.SourceEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface SourceDao {
    @Query("SELECT * FROM sources ORDER BY name")
    fun getAllSources(): Flow<List<SourceEntity>>

    @Query("SELECT * FROM sources WHERE enabled = 1 ORDER BY name")
    suspend fun getEnabledSources(): List<SourceEntity>

    @Query("SELECT id FROM sources WHERE url = :url")
    suspend fun getSourceIdByUrl(url: String): Long?

    @Query("SELECT last_fetched FROM sources WHERE id = :sourceId")
    suspend fun getLastFetched(sourceId: Long): String?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(source: SourceEntity): Long

    @Query("UPDATE sources SET last_fetched = datetime('now') WHERE id = :sourceId")
    suspend fun updateLastFetched(sourceId: Long)

    @Query("UPDATE sources SET enabled = :enabled WHERE id = :sourceId")
    suspend fun setEnabled(sourceId: Long, enabled: Boolean)

    @Query("DELETE FROM sources WHERE id = :sourceId")
    suspend fun delete(sourceId: Long)

    @Query("SELECT COUNT(*) FROM sources WHERE enabled = 1")
    suspend fun countEnabled(): Int
}
