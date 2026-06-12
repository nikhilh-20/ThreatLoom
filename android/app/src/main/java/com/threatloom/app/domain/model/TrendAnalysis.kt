package com.threatloom.app.domain.model

data class TrendAnalysis(
    val categoryName: String,
    val periodType: String,
    val periodLabel: String,
    val trendText: String,
    val articleCount: Int,
    val articleHash: String
)
