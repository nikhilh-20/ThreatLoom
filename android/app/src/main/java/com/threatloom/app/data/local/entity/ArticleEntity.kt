package com.threatloom.app.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "articles",
    foreignKeys = [ForeignKey(
        entity = SourceEntity::class,
        parentColumns = ["id"],
        childColumns = ["source_id"],
        onDelete = ForeignKey.NO_ACTION
    )],
    indices = [
        Index(value = ["url"], unique = true),
        Index(value = ["source_id"]),
        Index(value = ["published_date"])
    ]
)
data class ArticleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "source_id") val sourceId: Long,
    val title: String,
    val url: String,
    val author: String? = null,
    @ColumnInfo(name = "published_date") val publishedDate: String? = null,
    @ColumnInfo(name = "fetched_date", defaultValue = "CURRENT_TIMESTAMP") val fetchedDate: String? = null,
    @ColumnInfo(name = "content_raw") val contentRaw: String? = null,
    @ColumnInfo(name = "image_url") val imageUrl: String? = null,
    /** When set, this article is a near-duplicate of the referenced article and is skipped during summarization. */
    @ColumnInfo(name = "duplicate_of_id") val duplicateOfId: Long? = null
)
