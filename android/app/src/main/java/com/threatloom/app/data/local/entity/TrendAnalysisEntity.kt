package com.threatloom.app.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "trend_analyses",
    indices = [
        Index(
            value = ["category_name", "period_type", "period_label"],
            unique = true
        )
    ]
)
data class TrendAnalysisEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "category_name") val categoryName: String,
    @ColumnInfo(name = "period_type") val periodType: String,
    @ColumnInfo(name = "period_label") val periodLabel: String,
    @ColumnInfo(name = "trend_text") val trendText: String,
    @ColumnInfo(name = "article_count") val articleCount: Int,
    @ColumnInfo(name = "article_hash") val articleHash: String,
    @ColumnInfo(name = "model_used") val modelUsed: String,
    @ColumnInfo(name = "created_date", defaultValue = "CURRENT_TIMESTAMP") val createdDate: String? = null
)
