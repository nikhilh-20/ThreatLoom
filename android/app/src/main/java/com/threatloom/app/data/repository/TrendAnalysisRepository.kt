package com.threatloom.app.data.repository

import com.threatloom.app.data.local.dao.TrendAnalysisDao
import com.threatloom.app.data.local.entity.TrendAnalysisEntity
import com.threatloom.app.domain.model.TrendAnalysis
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TrendAnalysisRepository @Inject constructor(
    private val trendAnalysisDao: TrendAnalysisDao
) {
    suspend fun getByCategory(categoryName: String): List<TrendAnalysis> {
        return trendAnalysisDao.getByCategory(categoryName).map { it.toDomain() }
    }

    suspend fun getByCategoryAndPeriod(
        categoryName: String,
        periodType: String,
        periodLabel: String
    ): TrendAnalysis? {
        return trendAnalysisDao.getByCategoryAndPeriod(categoryName, periodType, periodLabel)?.toDomain()
    }

    suspend fun upsert(
        categoryName: String,
        periodType: String,
        periodLabel: String,
        trendText: String,
        articleCount: Int,
        articleHash: String,
        modelUsed: String
    ) {
        trendAnalysisDao.upsert(
            TrendAnalysisEntity(
                categoryName = categoryName,
                periodType = periodType,
                periodLabel = periodLabel,
                trendText = trendText,
                articleCount = articleCount,
                articleHash = articleHash,
                modelUsed = modelUsed
            )
        )
    }

    suspend fun deleteByCategory(categoryName: String) {
        trendAnalysisDao.deleteByCategory(categoryName)
    }

    private fun TrendAnalysisEntity.toDomain() = TrendAnalysis(
        categoryName = categoryName,
        periodType = periodType,
        periodLabel = periodLabel,
        trendText = trendText,
        articleCount = articleCount,
        articleHash = articleHash
    )
}
