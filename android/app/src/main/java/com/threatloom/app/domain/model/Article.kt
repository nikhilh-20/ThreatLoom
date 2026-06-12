package com.threatloom.app.domain.model

data class Article(
    val id: Long = 0,
    val sourceId: Long,
    val title: String,
    val url: String,
    val author: String? = null,
    val publishedDate: String? = null,
    val fetchedDate: String? = null,
    val contentRaw: String? = null,
    val imageUrl: String? = null,
    val sourceName: String? = null
)
