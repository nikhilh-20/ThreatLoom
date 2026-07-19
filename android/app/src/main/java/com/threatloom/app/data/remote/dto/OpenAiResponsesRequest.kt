package com.threatloom.app.data.remote.dto

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class OpenAiResponsesRequest(
    val model: String,
    val input: List<OpenAiResponsesInputItem>,
    val instructions: String? = null,
    val tools: List<OpenAiResponsesTool>? = null,
    val temperature: Float? = null,
    @Json(name = "max_output_tokens") val maxOutputTokens: Int? = null
)

@JsonClass(generateAdapter = true)
data class OpenAiResponsesInputItem(
    val role: String,
    val content: String
)

// No call-count-limiting field exists here on purpose: the Responses API's `max_tool_calls`
// parameter that caps tool invocations is documented only for the deep-research models
// (o3-deep-research/o4-mini-deep-research), not gpt-5.5/gpt-5.6, so it can't be applied to
// the chat models this app uses. Only the system-prompt-level soft limit applies for OpenAI.
@JsonClass(generateAdapter = true)
data class OpenAiResponsesTool(
    val type: String = "web_search",
    @Json(name = "search_context_size") val searchContextSize: String = "medium"
)
