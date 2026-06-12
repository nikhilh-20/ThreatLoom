package com.threatloom.app.domain.model

data class SubcategoryGroup(
    val tag: String,
    val displayName: String,
    val count: Int,
    val articles: List<ArticleWithSummary>
)
