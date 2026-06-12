package com.threatloom.app.domain.service

import com.threatloom.app.data.preferences.SettingsDataStore
import com.threatloom.app.data.remote.api.AnthropicApi
import com.threatloom.app.data.remote.api.OpenAiApi
import com.threatloom.app.data.remote.dto.*
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

data class LlmResult(
    val content: String,
    val inputTokens: Int = 0,
    val outputTokens: Int = 0,
    val cacheWriteTokens: Int = 0,
    val cacheReadTokens: Int = 0
)

@Singleton
class LlmService @Inject constructor(
    private val openAiApi: OpenAiApi,
    private val anthropicApi: AnthropicApi,
    private val settingsDataStore: SettingsDataStore,
    private val costTracker: CostTracker
) {
    suspend fun chatCompletion(
        systemPrompt: String? = null,
        messages: List<ChatMessageDto>,
        temperature: Float = 0.3f,
        maxTokens: Int = 2000,
        jsonMode: Boolean = false,
        cacheSystemPrompt: Boolean = false
    ): LlmResult {
        val provider = settingsDataStore.llmProvider.first()
        return if (provider == "anthropic") {
            callAnthropic(systemPrompt, messages, temperature, maxTokens, jsonMode, cacheSystemPrompt)
        } else {
            callOpenAi(systemPrompt, messages, temperature, maxTokens, jsonMode)
        }
    }

    suspend fun hasApiKey(): Boolean {
        val provider = settingsDataStore.llmProvider.first()
        val key = if (provider == "anthropic") {
            settingsDataStore.anthropicApiKey.first()
        } else {
            settingsDataStore.openaiApiKey.first()
        }
        return key.isNotBlank()
    }

    suspend fun getModelName(): String {
        val provider = settingsDataStore.llmProvider.first()
        return if (provider == "anthropic") {
            settingsDataStore.anthropicModel.first()
        } else {
            settingsDataStore.openaiModel.first()
        }
    }

    private suspend fun callOpenAi(
        systemPrompt: String?,
        messages: List<ChatMessageDto>,
        temperature: Float,
        maxTokens: Int,
        jsonMode: Boolean
    ): LlmResult {
        val model = settingsDataStore.openaiModel.first()
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
        systemPrompt: String?,
        messages: List<ChatMessageDto>,
        temperature: Float,
        maxTokens: Int,
        jsonMode: Boolean,
        cacheSystemPrompt: Boolean
    ): LlmResult {
        val model = settingsDataStore.anthropicModel.first()

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
                temperature = temperature
            )
        )
        var content = response.content.firstOrNull { it.type == "text" }?.text
            ?: throw IllegalStateException("No response from Anthropic")
        if (jsonMode) content = stripCodeFences(content)
        val inputTok = response.usage?.input_tokens ?: 0
        val outputTok = response.usage?.output_tokens ?: 0
        val cacheWriteTok = response.usage?.cache_creation_input_tokens ?: 0
        val cacheReadTok = response.usage?.cache_read_input_tokens ?: 0
        costTracker.addUsage(inputTok, outputTok, cacheWriteTok, cacheReadTok)
        return LlmResult(content, inputTok, outputTok, cacheWriteTok, cacheReadTok)
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
