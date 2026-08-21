package com.threatloom.app.data.remote.dto

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class AnthropicUsage(
    val input_tokens: Int = 0,
    val output_tokens: Int = 0,
    val cache_creation_input_tokens: Int = 0,
    val cache_read_input_tokens: Int = 0,
    @Json(name = "server_tool_use") val serverToolUse: AnthropicServerToolUsage? = null
)

@JsonClass(generateAdapter = true)
data class AnthropicServerToolUsage(
    @Json(name = "web_search_requests") val webSearchRequests: Int = 0
)

@JsonClass(generateAdapter = true)
data class AnthropicResponse(
    val content: List<ContentBlock>,
    val usage: AnthropicUsage? = null,
    val stop_reason: String? = null
)

@JsonClass(generateAdapter = true)
data class ContentBlock(
    val type: String,
    val text: String? = null
)
