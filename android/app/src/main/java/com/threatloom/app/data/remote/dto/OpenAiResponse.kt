package com.threatloom.app.data.remote.dto

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class OpenAiPromptTokensDetails(
    @Json(name = "cached_tokens") val cachedTokens: Int = 0
)

@JsonClass(generateAdapter = true)
data class OpenAiUsage(
    val prompt_tokens: Int = 0,
    val completion_tokens: Int = 0,
    @Json(name = "prompt_tokens_details") val promptTokensDetails: OpenAiPromptTokensDetails? = null
)

@JsonClass(generateAdapter = true)
data class OpenAiResponse(
    val choices: List<Choice>,
    val usage: OpenAiUsage? = null
)

@JsonClass(generateAdapter = true)
data class Choice(
    val message: MessageContent
)

@JsonClass(generateAdapter = true)
data class MessageContent(
    val content: String
)
