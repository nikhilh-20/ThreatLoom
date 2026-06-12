package com.threatloom.app.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "summaries",
    foreignKeys = [ForeignKey(
        entity = ArticleEntity::class,
        parentColumns = ["id"],
        childColumns = ["article_id"],
        onDelete = ForeignKey.NO_ACTION
    )],
    indices = [Index(value = ["article_id"], unique = true)]
)
data class SummaryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "article_id") val articleId: Long,
    @ColumnInfo(name = "summary_text") val summaryText: String,
    @ColumnInfo(name = "key_points") val keyPoints: String? = null,
    val tags: String? = null,
    @ColumnInfo(name = "model_used") val modelUsed: String? = null,
    @ColumnInfo(name = "created_date", defaultValue = "CURRENT_TIMESTAMP") val createdDate: String? = null
)
