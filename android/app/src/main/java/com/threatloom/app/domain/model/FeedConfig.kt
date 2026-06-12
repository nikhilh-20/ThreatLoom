package com.threatloom.app.domain.model

data class FeedConfig(
    val name: String,
    val url: String,
    val enabled: Boolean = true
)
