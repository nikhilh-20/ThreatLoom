package com.threatloom.app.domain.model

data class CategoryInsight(
    val categoryName: String,
    val trendText: String? = null,
    val forecastText: String? = null,
    val articleCount: Int = 0,
    val articleHash: String? = null,
    val modelUsed: String? = null,
    val createdDate: String? = null
)
