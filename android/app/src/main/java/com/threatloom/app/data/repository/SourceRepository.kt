package com.threatloom.app.data.repository

import com.threatloom.app.data.local.dao.SourceDao
import com.threatloom.app.data.local.entity.SourceEntity
import com.threatloom.app.data.preferences.DefaultFeeds
import com.threatloom.app.domain.model.FeedConfig
import com.threatloom.app.domain.model.Source
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SourceRepository @Inject constructor(
    private val sourceDao: SourceDao
) {
    fun getAllSources(): Flow<List<Source>> = sourceDao.getAllSources().map { list ->
        list.map { it.toDomain() }
    }

    suspend fun getEnabledSources(): List<Source> = sourceDao.getEnabledSources().map { it.toDomain() }

    suspend fun upsertSource(name: String, url: String, enabled: Boolean = true): Long {
        val existing = sourceDao.getSourceIdByUrl(url)
        if (existing != null) return existing
        return sourceDao.upsert(SourceEntity(name = name, url = url, enabled = if (enabled) 1 else 0))
    }

    suspend fun getSourceIdByUrl(url: String): Long? = sourceDao.getSourceIdByUrl(url)

    suspend fun getLastFetched(sourceId: Long): String? = sourceDao.getLastFetched(sourceId)

    suspend fun updateLastFetched(sourceId: Long) = sourceDao.updateLastFetched(sourceId)

    suspend fun setEnabled(sourceId: Long, enabled: Boolean) = sourceDao.setEnabled(sourceId, enabled)

    suspend fun delete(sourceId: Long) = sourceDao.delete(sourceId)

    suspend fun countEnabled(): Int = sourceDao.countEnabled()

    suspend fun initializeDefaultFeeds() {
        for (feed in DefaultFeeds.feeds) {
            val existing = sourceDao.getSourceIdByUrl(feed.url)
            if (existing == null) {
                sourceDao.upsert(SourceEntity(name = feed.name, url = feed.url, enabled = if (feed.enabled) 1 else 0))
            }
        }
    }

    private fun SourceEntity.toDomain() = Source(
        id = id, name = name, url = url,
        enabled = enabled == 1, lastFetched = lastFetched
    )
}
