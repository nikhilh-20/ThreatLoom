package com.threatloom.app.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "debates",
    foreignKeys = [ForeignKey(
        entity = ArticleEntity::class,
        parentColumns = ["id"],
        childColumns = ["article_id"],
        onDelete = ForeignKey.NO_ACTION
    )],
    indices = [Index(value = ["article_id"], unique = true)]
)
data class DebateEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "article_id") val articleId: Long,
    @ColumnInfo(name = "debate_topic") val debateTopic: String? = null,
    val messages: String? = null,
    @ColumnInfo(name = "total_cost") val totalCost: Double = 0.0,
    @ColumnInfo(name = "model_used") val modelUsed: String? = null,
    val concluded: Boolean = false,
    @ColumnInfo(name = "created_date", defaultValue = "CURRENT_TIMESTAMP") val createdDate: String? = null
)
