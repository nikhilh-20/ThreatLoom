package com.threatloom.app.domain.model

data class DashboardStats(
    val totalArticles: Int = 0,
    val totalSources: Int = 0,
    val totalSummaries: Int = 0,
    val articlesLast24h: Int = 0,
    val totalEmbedded: Int = 0,
    val scrapeFailed: Int = 0,
    val unsummarized: Int = 0,
    val summaryFailed: Int = 0
)
