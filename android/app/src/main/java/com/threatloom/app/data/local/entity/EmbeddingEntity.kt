package com.threatloom.app.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.PrimaryKey

@Entity(
    tableName = "article_embeddings",
    foreignKeys = [ForeignKey(
        entity = ArticleEntity::class,
        parentColumns = ["id"],
        childColumns = ["article_id"],
        onDelete = ForeignKey.NO_ACTION
    )]
)
data class EmbeddingEntity(
    @PrimaryKey @ColumnInfo(name = "article_id") val articleId: Long,
    val embedding: ByteArray,
    @ColumnInfo(name = "model_used") val modelUsed: String,
    @ColumnInfo(name = "created_date", defaultValue = "CURRENT_TIMESTAMP") val createdDate: String? = null
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is EmbeddingEntity) return false
        return articleId == other.articleId && embedding.contentEquals(other.embedding)
    }
    override fun hashCode(): Int = 31 * articleId.hashCode() + embedding.contentHashCode()
}
