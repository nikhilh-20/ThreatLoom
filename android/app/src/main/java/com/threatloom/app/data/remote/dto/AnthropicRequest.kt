package com.threatloom.app.data.remote.dto

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class CacheControl(val type: String = "ephemeral")

@JsonClass(generateAdapter = true)
data class AnthropicSystemBlock(
    val type: String = "text",
    val text: String,
    @Json(name = "cache_control") val cacheControl: CacheControl? = null
)

@JsonClass(generateAdapter = true)
data class AnthropicRequest(
    val model: String,
    @Json(name = "max_tokens") val maxTokens: Int = 2000,
    val system: List<AnthropicSystemBlock>? = null,
    val messages: List<AnthropicMessageDto>,
    val temperature: Float = 0.3f
)

@JsonClass(generateAdapter = true)
data class AnthropicMessageDto(
    val role: String,
    val content: String
)
