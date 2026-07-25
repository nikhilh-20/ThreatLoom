package com.threatloom.app.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A saved Intelligence chat. Unlike [SavedCategoryChatEntity] there is no category discriminator —
 * the Intelligence tab is a single database-wide feed, so all saved chats live in one flat list.
 */
@Entity(tableName = "saved_intelligence_chats")
data class SavedIntelligenceChatEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String? = null,
    val messages: String? = null,
    @ColumnInfo(name = "context_articles") val contextArticles: String? = null,
    @ColumnInfo(name = "total_cost") val totalCost: Double = 0.0,
    @ColumnInfo(name = "model_used") val modelUsed: String? = null,
    @ColumnInfo(name = "created_date", defaultValue = "CURRENT_TIMESTAMP") val createdDate: String? = null,
    @ColumnInfo(name = "updated_date", defaultValue = "CURRENT_TIMESTAMP") val updatedDate: String? = null
)
