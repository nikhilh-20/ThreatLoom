package com.threatloom.app.domain.model

data class Summary(
    val id: Long = 0,
    val articleId: Long,
    val summaryText: String,
    val keyPoints: String? = null,
    val tags: String? = null,
    val modelUsed: String? = null,
    val createdDate: String? = null
)
