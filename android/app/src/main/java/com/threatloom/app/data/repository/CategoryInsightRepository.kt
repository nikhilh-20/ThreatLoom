package com.threatloom.app.data.repository

import com.threatloom.app.data.local.dao.CategoryInsightDao
import com.threatloom.app.data.local.entity.CategoryInsightEntity
import com.threatloom.app.domain.model.CategoryInsight
import com.threatloom.app.util.DateUtils
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CategoryInsightRepository @Inject constructor(
    private val categoryInsightDao: CategoryInsightDao
) {
    suspend fun getByCategory(categoryName: String): CategoryInsight? {
        return categoryInsightDao.getByCategory(categoryName)?.toDomain()
    }

    suspend fun upsert(
        categoryName: String, trendText: String, forecastText: String,
        articleCount: Int, articleHash: String, modelUsed: String
    ) {
        val createdDate = categoryInsightDao.getByCategory(categoryName)?.createdDate ?: DateUtils.nowIso()
        categoryInsightDao.upsert(CategoryInsightEntity(
            categoryName = categoryName, trendText = trendText,
            forecastText = forecastText, articleCount = articleCount,
            articleHash = articleHash, modelUsed = modelUsed, createdDate = createdDate
        ))
    }

    private fun CategoryInsightEntity.toDomain() = CategoryInsight(
        categoryName = categoryName, trendText = trendText,
        forecastText = forecastText, articleCount = articleCount ?: 0,
        articleHash = articleHash, modelUsed = modelUsed, createdDate = createdDate
    )
}
