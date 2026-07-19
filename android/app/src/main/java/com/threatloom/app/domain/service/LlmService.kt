package com.threatloom.app.domain.service

import com.threatloom.app.data.preferences.SettingsDataStore
import com.threatloom.app.data.remote.api.AnthropicApi
import com.threatloom.app.data.remote.api.OpenAiApi
import com.threatloom.app.data.remote.dto.*
import com.threatloom.app.domain.model.LlmFeature
import com.threatloom.app.util.AppLogger
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

data class LlmResult(
    val content: String,
    val inputTokens: Int = 0,
    val outputTokens: Int = 0,
    val cacheWriteTokens: Int = 0,
    val cacheReadTokens: Int = 0,
    val webSearchCalls: Int = 0
)

@Singleton
class LlmService @Inject constructor(
    private val openAiApi: OpenAiApi,
    private val anthropicApi: AnthropicApi,
    private val settingsDataStore: SettingsDataStore,
    private val costTracker: CostTracker,
    private val appLogger: AppLogger
) {
    companion object {
        // Anthropic's web_search tool enforces this as a hard per-request cap (max_uses).
        private const val WEB_SEARCH_MAX_USES = 5
    }

    suspend fun chatCompletion(
        feature: LlmFeature,
        systemPrompt: String? = null,
        messages: List<ChatMessageDto>,
        temperature: Float = 0.3f,
        maxTokens: Int = 2000,
        jsonMode: Boolean = false,
        cacheSystemPrompt: Boolean = false,
        enableWebSearch: Boolean = false
    ): LlmResult {
        val provider = resolveProvider(feature)
        return if (provider == "anthropic") {
            callAnthropic(feature, systemPrompt, messages, temperature, maxTokens, jsonMode, cacheSystemPrompt, enableWebSearch)
        } else if (enableWebSearch) {
            callOpenAiResponses(feature, systemPrompt, messages, temperature, maxTokens)
        } else {
            callOpenAi(feature, systemPrompt, messages, temperature, maxTokens, jsonMode)
        }
    }

    suspend fun hasApiKey(feature: LlmFeature): Boolean {
        val provider = resolveProvider(feature)
        val key = if (provider == "anthropic") {
            settingsDataStore.anthropicApiKey.first()
        } else {
            settingsDataStore.openaiApiKey.first()
        }
        return key.isNotBlank()
    }

    suspend fun getModelName(feature: LlmFeature): String {
        val provider = resolveProvider(feature)
        return resolveModel(feature, provider)
    }

    private suspend fun resolveProvider(feature: LlmFeature): String =
        settingsDataStore.featureProvider(feature).first().ifBlank { settingsDataStore.llmProvider.first() }

    private suspend fun resolveModel(feature: LlmFeature, provider: String): String {
        val override = settingsDataStore.featureModel(feature).first()
        if (override.isNotBlank()) return override
        return if (provider == "anthropic") {
            settingsDataStore.anthropicModel.first()
        } else {
            settingsDataStore.openaiModel.first()
        }
    }

    private suspend fun callOpenAi(
        feature: LlmFeature,
        systemPrompt: String?,
        messages: List<ChatMessageDto>,
        temperature: Float,
        maxTokens: Int,
        jsonMode: Boolean
    ): LlmResult {
        val model = resolveModel(feature, "openai")
        val allMessages = if (systemPrompt != null) {
            listOf(ChatMessageDto("system", systemPrompt)) + messages
        } else {
            messages
        }
        // Newer OpenAI reasoning models (o-series, gpt-5*) require max_completion_tokens,
        // don't support custom temperature, and reject response_format. Mirror the web client.
        val isReasoning = listOf("o1", "o3", "o4", "gpt-5").any { model.startsWith(it) }
        val request = if (isReasoning) {
            OpenAiRequest(
                model = model,
                messages = allMessages,
                maxCompletionTokens = maxTokens * 3  // pad for reasoning-token overhead
            )
        } else {
            OpenAiRequest(
                model = model,
                messages = allMessages,
                temperature = temperature,
                maxTokens = maxTokens,
                responseFormat = if (jsonMode) ResponseFormat("json_object") else null
            )
        }
        val response = openAiApi.chatCompletion(request)
        var content = response.choices.firstOrNull()?.message?.content
            ?: throw IllegalStateException("No response from OpenAI")
        // Reasoning models don't get response_format, so JSON is enforced only by the prompt;
        // strip any code fences the model may add so Moshi can parse the response.
        if (jsonMode) content = stripCodeFences(content)
        val totalInput = response.usage?.prompt_tokens ?: 0
        val outputTok = response.usage?.completion_tokens ?: 0
        val cachedTok = response.usage?.promptTokensDetails?.cachedTokens ?: 0
        val inputTok = totalInput - cachedTok
        costTracker.addUsage(inputTok, outputTok, cacheWrite = 0, cacheRead = cachedTok)
        return LlmResult(content, inputTok, outputTok, cacheWriteTokens = 0, cacheReadTokens = cachedTok)
    }

    private suspend fun callAnthropic(
        feature: LlmFeature,
        systemPrompt: String?,
        messages: List<ChatMessageDto>,
        temperature: Float,
        maxTokens: Int,
        jsonMode: Boolean,
        cacheSystemPrompt: Boolean,
        enableWebSearch: Boolean = false
    ): LlmResult {
        val model = resolveModel(feature, "anthropic")

        // Collect system parts in order: explicit systemPrompt, then system-role messages
        val systemParts = mutableListOf<String>()
        if (systemPrompt != null) systemParts.add(systemPrompt)

        val nonSystemMessages = mutableListOf<AnthropicMessageDto>()
        for (msg in messages) {
            if (msg.role == "system") {
                systemParts.add(msg.content)
            } else {
                nonSystemMessages.add(AnthropicMessageDto(role = msg.role, content = msg.content))
            }
        }

        // Append JSON mode instruction to the first (stable) block
        if (jsonMode) {
            val jsonNote = "\n\nIMPORTANT: You must respond with valid JSON only. No text before or after the JSON."
            if (systemParts.isNotEmpty()) {
                systemParts[0] = systemParts[0] + jsonNote
            } else {
                systemParts.add("You must respond with valid JSON only. No text before or after the JSON.")
            }
        }

        // Build system block list; first block gets cache_control when caching is requested
        val systemBlocks = if (systemParts.isNotEmpty()) {
            systemParts.mapIndexed { i, text ->
                AnthropicSystemBlock(
                    text = text,
                    cacheControl = if (cacheSystemPrompt && i == 0) CacheControl() else null
                )
            }
        } else {
            null
        }

        // Anthropic requires at least one user message
        if (nonSystemMessages.isEmpty()) {
            nonSystemMessages.add(AnthropicMessageDto(role = "user", content = "Please proceed."))
        }

        val mergedMessages = mergeConsecutiveMessages(nonSystemMessages)

        val response = anthropicApi.createMessage(
            AnthropicRequest(
                model = model,
                maxTokens = maxTokens,
                system = systemBlocks,
                messages = mergedMessages,
                temperature = temperature,
                tools = if (enableWebSearch) listOf(AnthropicTool(maxUses = WEB_SEARCH_MAX_USES)) else null
            )
        )
        var content = response.content.filter { it.type == "text" }.mapNotNull { it.text }.joinToString("\n")
        if (content.isBlank()) throw IllegalStateException("No response from Anthropic")
        if (jsonMode) content = stripCodeFences(content)
        val inputTok = response.usage?.input_tokens ?: 0
        val outputTok = response.usage?.output_tokens ?: 0
        val cacheWriteTok = response.usage?.cache_creation_input_tokens ?: 0
        val cacheReadTok = response.usage?.cache_read_input_tokens ?: 0
        costTracker.addUsage(inputTok, outputTok, cacheWriteTok, cacheReadTok)
        val searchCalls = response.usage?.serverToolUse?.webSearchRequests ?: 0
        costTracker.addWebSearchCalls(searchCalls)
        appLogger.d("LlmService", "Anthropic response: model=$model, webSearchEnabled=$enableWebSearch, searchCalls=$searchCalls")
        return LlmResult(content, inputTok, outputTok, cacheWriteTok, cacheReadTok, webSearchCalls = searchCalls)
    }

    private suspend fun callOpenAiResponses(
        feature: LlmFeature,
        systemPrompt: String?,
        messages: List<ChatMessageDto>,
        temperature: Float,
        maxTokens: Int
    ): LlmResult {
        val model = resolveModel(feature, "openai")
        val input = messages.map { OpenAiResponsesInputItem(role = it.role, content = it.content) }
        val response = openAiApi.createResponse(
            OpenAiResponsesRequest(
                model = model,
                input = input,
                instructions = systemPrompt,
                tools = listOf(OpenAiResponsesTool()),
                temperature = temperature,
                maxOutputTokens = maxTokens
            )
        )
        val message = response.output.firstOrNull { it.type == "message" }
        val content = message?.content.orEmpty().mapNotNull { it.text }.joinToString("\n")
        if (content.isBlank()) throw IllegalStateException("No response from OpenAI")
        val totalInput = response.usage?.inputTokens ?: 0
        val outputTok = response.usage?.outputTokens ?: 0
        val cachedTok = response.usage?.inputTokensDetails?.cachedTokens ?: 0
        val inputTok = totalInput - cachedTok
        costTracker.addUsage(inputTok, outputTok, cacheWrite = 0, cacheRead = cachedTok)
        val searchCalls = response.output.count { it.type == "web_search_call" }
        costTracker.addWebSearchCalls(searchCalls)
        appLogger.d("LlmService", "OpenAI Responses API result: model=$model, searchCalls=$searchCalls")
        return LlmResult(content, inputTok, outputTok, cacheWriteTokens = 0, cacheReadTokens = cachedTok, webSearchCalls = searchCalls)
    }

    private fun stripCodeFences(text: String): String {
        val trimmed = text.trim()
        if (trimmed.startsWith("```")) {
            val firstNewline = trimmed.indexOf('\n')
            val lastFence = trimmed.lastIndexOf("```")
            if (firstNewline != -1 && lastFence > firstNewline) {
                return trimmed.substring(firstNewline + 1, lastFence).trim()
            }
        }
        return trimmed
    }

    private fun mergeConsecutiveMessages(messages: List<AnthropicMessageDto>): List<AnthropicMessageDto> {
        if (messages.isEmpty()) return messages
        val result = mutableListOf<AnthropicMessageDto>()
        for (msg in messages) {
            val last = result.lastOrNull()
            if (last != null && last.role == msg.role) {
                result[result.size - 1] = AnthropicMessageDto(
                    role = msg.role,
                    content = last.content + "\n\n" + msg.content
                )
            } else {
                result.add(msg)
            }
        }
        // Ensure first message is from "user"
        if (result.firstOrNull()?.role != "user") {
            result.add(0, AnthropicMessageDto(role = "user", content = "Please proceed with the following context."))
        }
        return result
    }
}
