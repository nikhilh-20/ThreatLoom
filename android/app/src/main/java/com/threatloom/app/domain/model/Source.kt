package com.threatloom.app.domain.model

data class Source(
    val id: Long = 0,
    val name: String,
    val url: String,
    val enabled: Boolean = true,
    val lastFetched: String? = null
)
