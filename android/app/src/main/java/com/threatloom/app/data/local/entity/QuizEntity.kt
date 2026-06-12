package com.threatloom.app.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "quizzes",
    foreignKeys = [ForeignKey(
        entity = ArticleEntity::class,
        parentColumns = ["id"],
        childColumns = ["article_id"],
        onDelete = ForeignKey.NO_ACTION
    )],
    indices = [Index(value = ["article_id"], unique = true)]
)
data class QuizEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "article_id") val articleId: Long,
    val questions: String? = null,
    @ColumnInfo(name = "debate_topic") val debateTopic: String? = null,
    @ColumnInfo(name = "score_best") val scoreBest: Int? = null,
    @ColumnInfo(name = "score_total") val scoreTotal: Int? = null,
    @ColumnInfo(name = "created_date", defaultValue = "CURRENT_TIMESTAMP") val createdDate: String? = null
)
