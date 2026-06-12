package com.threatloom.app.domain.model

data class CategoryGroup(
    val name: String,
    val count: Int,
    val articles: List<ArticleWithSummary>
)
