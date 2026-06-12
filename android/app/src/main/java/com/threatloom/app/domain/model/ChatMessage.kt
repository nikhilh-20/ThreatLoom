package com.threatloom.app.domain.model

data class ChatMessage(
    val role: String,
    val content: String,
    val articles: List<ArticleWithSummary>? = null,
    val modelUsed: String? = null,
    val concluded: Boolean = false
)
