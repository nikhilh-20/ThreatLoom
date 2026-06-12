package com.threatloom.app.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "category_insights",
    indices = [Index(value = ["category_name"], unique = true)]
)
data class CategoryInsightEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "category_name") val categoryName: String,
    @ColumnInfo(name = "trend_text") val trendText: String? = null,
    @ColumnInfo(name = "forecast_text") val forecastText: String? = null,
    @ColumnInfo(name = "article_count") val articleCount: Int? = null,
    @ColumnInfo(name = "article_hash") val articleHash: String? = null,
    @ColumnInfo(name = "model_used") val modelUsed: String? = null,
    @ColumnInfo(name = "created_date", defaultValue = "CURRENT_TIMESTAMP") val createdDate: String? = null
)
