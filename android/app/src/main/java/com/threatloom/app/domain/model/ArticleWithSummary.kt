package com.threatloom.app.domain.model

data class ArticleWithSummary(
    val id: Long,
    val title: String,
    val url: String,
    val author: String? = null,
    val publishedDate: String? = null,
    val fetchedDate: String? = null,
    val imageUrl: String? = null,
    val sourceName: String? = null,
    val summaryText: String? = null,
    val keyPoints: String? = null,
    val tags: String? = null,
    val relevanceScore: Float? = null
)
