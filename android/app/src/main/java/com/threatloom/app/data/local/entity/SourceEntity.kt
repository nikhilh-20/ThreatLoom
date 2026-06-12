package com.threatloom.app.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "sources",
    indices = [Index(value = ["url"], unique = true)]
)
data class SourceEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val url: String,
    val enabled: Int = 1,
    @ColumnInfo(name = "last_fetched") val lastFetched: String? = null
)
