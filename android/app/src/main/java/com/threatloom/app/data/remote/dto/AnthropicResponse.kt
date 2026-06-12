package com.threatloom.app.data.remote.dto

import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class AnthropicUsage(
    val input_tokens: Int = 0,
    val output_tokens: Int = 0,
    val cache_creation_input_tokens: Int = 0,
    val cache_read_input_tokens: Int = 0
)

@JsonClass(generateAdapter = true)
data class AnthropicResponse(
    val content: List<ContentBlock>,
    val usage: AnthropicUsage? = null
)

@JsonClass(generateAdapter = true)
data class ContentBlock(
    val type: String,
    val text: String? = null
)
