package com.threatloom.app.data.remote.dto

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class OpenAiResponsesResponse(
    val output: List<OpenAiResponsesOutputItem>,
    val usage: OpenAiResponsesUsage? = null
)

@JsonClass(generateAdapter = true)
data class OpenAiResponsesOutputItem(
    val type: String,
    val content: List<OpenAiResponsesContentPart>? = null
)

@JsonClass(generateAdapter = true)
data class OpenAiResponsesContentPart(
    val type: String? = null,
    val text: String? = null,
    val annotations: List<OpenAiUrlCitation>? = null
)

@JsonClass(generateAdapter = true)
data class OpenAiUrlCitation(
    val url: String? = null,
    val title: String? = null
)

@JsonClass(generateAdapter = true)
data class OpenAiResponsesUsage(
    @Json(name = "input_tokens") val inputTokens: Int = 0,
    @Json(name = "output_tokens") val outputTokens: Int = 0,
    @Json(name = "input_tokens_details") val inputTokensDetails: OpenAiResponsesInputTokensDetails? = null
)

@JsonClass(generateAdapter = true)
data class OpenAiResponsesInputTokensDetails(
    @Json(name = "cached_tokens") val cachedTokens: Int = 0
)
