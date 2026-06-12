package com.threatloom.app.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.PrimaryKey

@Entity(
    tableName = "article_correlations",
    foreignKeys = [
        ForeignKey(entity = ArticleEntity::class, parentColumns = ["id"], childColumns = ["article_id_1"], onDelete = ForeignKey.NO_ACTION),
        ForeignKey(entity = ArticleEntity::class, parentColumns = ["id"], childColumns = ["article_id_2"], onDelete = ForeignKey.NO_ACTION)
    ]
)
data class CorrelationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "article_id_1") val articleId1: Long,
    @ColumnInfo(name = "article_id_2") val articleId2: Long,
    @ColumnInfo(name = "correlation_type") val correlationType: String? = null,
    val confidence: Float? = null,
    val description: String? = null,
    @ColumnInfo(name = "created_date", defaultValue = "CURRENT_TIMESTAMP") val createdDate: String? = null
)
