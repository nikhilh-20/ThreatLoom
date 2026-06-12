package com.threatloom.app.data.repository

import com.threatloom.app.data.remote.dto.ChatMessageDto
import com.threatloom.app.domain.service.LlmService
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class IntelligenceRepository @Inject constructor(
    private val llmService: LlmService
) {
    suspend fun chatCompletion(
        messages: List<ChatMessageDto>,
        temperature: Float = 0.3f,
        maxTokens: Int = 2000
    ): String? {
        return try {
            llmService.chatCompletion(
                messages = messages,
                temperature = temperature,
                maxTokens = maxTokens
            ).content
        } catch (e: Exception) {
            null
        }
    }
}
