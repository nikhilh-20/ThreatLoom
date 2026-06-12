package com.threatloom.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.threatloom.app.data.local.entity.TrendAnalysisEntity

@Dao
interface TrendAnalysisDao {

    @Query("SELECT * FROM trend_analyses WHERE category_name = :categoryName ORDER BY period_type, period_label")
    suspend fun getByCategory(categoryName: String): List<TrendAnalysisEntity>

    @Query("SELECT * FROM trend_analyses WHERE category_name = :categoryName AND period_type = :periodType AND period_label = :periodLabel")
    suspend fun getByCategoryAndPeriod(categoryName: String, periodType: String, periodLabel: String): TrendAnalysisEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: TrendAnalysisEntity)

    @Query("DELETE FROM trend_analyses WHERE category_name = :categoryName")
    suspend fun deleteByCategory(categoryName: String)
}
