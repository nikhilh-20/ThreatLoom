package com.threatloom.app.data.remote.dto

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class OpenAiRequest(
    val model: String,
    val messages: List<ChatMessageDto>,
    val temperature: Float? = null,
    @Json(name = "max_tokens") val maxTokens: Int? = null,
    @Json(name = "max_completion_tokens") val maxCompletionTokens: Int? = null,
    @Json(name = "response_format") val responseFormat: ResponseFormat? = null
)

@JsonClass(generateAdapter = true)
data class ChatMessageDto(
    val role: String,
    val content: String
)

@JsonClass(generateAdapter = true)
data class ResponseFormat(
    val type: String = "json_object"
)
