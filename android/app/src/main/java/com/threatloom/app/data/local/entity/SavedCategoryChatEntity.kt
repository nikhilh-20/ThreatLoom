package com.threatloom.app.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "saved_category_chats",
    indices = [Index(value = ["category_name"])]
)
data class SavedCategoryChatEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "category_name") val categoryName: String,
    val title: String? = null,
    val messages: String? = null,
    @ColumnInfo(name = "total_cost") val totalCost: Double = 0.0,
    @ColumnInfo(name = "model_used") val modelUsed: String? = null,
    @ColumnInfo(name = "created_date", defaultValue = "CURRENT_TIMESTAMP") val createdDate: String? = null,
    @ColumnInfo(name = "updated_date", defaultValue = "CURRENT_TIMESTAMP") val updatedDate: String? = null
)
