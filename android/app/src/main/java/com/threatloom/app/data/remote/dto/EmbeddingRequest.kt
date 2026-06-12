package com.threatloom.app.data.remote.dto

import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class EmbeddingRequest(
    val model: String = "text-embedding-3-small",
    val input: List<String>
)
