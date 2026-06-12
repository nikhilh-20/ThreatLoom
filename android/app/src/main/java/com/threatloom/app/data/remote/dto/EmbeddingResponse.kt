package com.threatloom.app.data.remote.dto

import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class EmbeddingResponse(
    val data: List<EmbeddingData>
)

@JsonClass(generateAdapter = true)
data class EmbeddingData(
    val embedding: List<Float>
)
