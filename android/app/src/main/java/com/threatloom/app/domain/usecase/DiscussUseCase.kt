package com.threatloom.app.domain.usecase

import com.threatloom.app.data.remote.dto.ChatMessageDto
import com.threatloom.app.data.repository.SummaryRepository
import com.threatloom.app.domain.model.ArticleWithSummary
import com.threatloom.app.domain.model.ChatMessage
import com.threatloom.app.domain.model.LlmFeature
import com.threatloom.app.domain.service.LlmService
import javax.inject.Inject

class DiscussUseCase @Inject constructor(
    private val llmService: LlmService,
    private val semanticSearchUseCase: SemanticSearchUseCase,
    private val summaryRepository: SummaryRepository
) {
    companion object {
        private const val MAX_CONTEXT_CHARS = 30000
        private const val MAX_CONVERSATION_MESSAGES = 10

        const val CONCLUSION_MARKER = "[DEBATE_CONCLUDED]"
        private const val WEB_SEARCH_ADDENDUM = "\n\nYou have access to a web search tool. Use it when: (1) the user's question needs current or live information not covered by the context provided above, or (2) the provided articles lack a concrete real-world example to illustrate a technique or concept the user is asking about. Whenever you source an example via web search, cite it as a markdown link in the format [Title](URL) so the source is clearly attributed and tappable. Search only until you have enough information to answer confidently — a small number of targeted searches is usually sufficient; do not search exhaustively."

        private const val SYSTEM_PROMPT = """You are a seasoned cybersecurity analyst and discussion partner. You hold informed opinions and are willing to defend them, but you genuinely engage with the human's arguments.

Draw on the article context provided AND your broader knowledge of the cybersecurity threat landscape. When relevant, cross-reference patterns you see across multiple articles. When illustrating a point with examples, prefer specific details from the provided articles — named malware, real behaviors, concrete techniques — over generic hypothetical scenarios. If the articles lack a suitable example and web search is available, search for a real one and cite the source URL.

Challenge the user's thinking when appropriate. Ask follow-up questions. Be direct and substantive — this is a debate, not a Q&A session.

If you disagree with the user's position, say so clearly and explain why. If they make a strong point that shifts your view, acknowledge it.

When the human concedes your position, or you are genuinely persuaded by theirs, or you both clearly converge on a shared view, write a brief closing summary of where you landed and end your message with the marker [DEBATE_CONCLUDED] on its own final line. Only emit this marker when the debate is genuinely resolved — otherwise keep engaging and do not write it."""
    }

    private fun buildContext(articles: List<ArticleWithSummary>, fallbackSummary: String?): String {
        if (articles.isNotEmpty()) {
            val parts = mutableListOf<String>()
            var totalChars = 0
            for ((i, art) in articles.withIndex()) {
                val entry = "---\nArticle ${i + 1}: ${art.title}\nSource: ${art.sourceName ?: "Unknown"} | Date: ${art.publishedDate ?: "Unknown"}\n\n${art.summaryText ?: ""}\n"
                if (totalChars + entry.length > MAX_CONTEXT_CHARS) break
                parts.add(entry)
                totalChars += entry.length
            }
            return "Relevant articles from the knowledge base:\n\n${parts.joinToString("\n")}"
        }
        if (!fallbackSummary.isNullOrBlank()) {
            return "Article context (semantic search unavailable — using originating article only):\n\n$fallbackSummary"
        }
        return "No article context available. Respond based on your general knowledge."
    }

    suspend operator fun invoke(
        messages: List<ChatMessage>,
        originatingArticleId: Long,
        debateTopic: String,
        webSearchEnabled: Boolean = false,
        onProgress: (String) -> Unit = {}
    ): ChatMessage {
        if (!llmService.hasApiKey(LlmFeature.DISCUSS)) return ChatMessage("assistant", "Please configure your API key in Settings.")

        val model = llmService.getModelName(LlmFeature.DISCUSS)
        val userMessages = messages.filter { it.role == "user" }
        val query = userMessages.lastOrNull()?.content ?: debateTopic

        onProgress("Retrieving articles…")
        val searchResults = semanticSearchUseCase(query, topK = 12)
        val fallbackSummary = if (searchResults.isEmpty()) summaryRepository.getSummaryText(originatingArticleId) else null
        val context = buildContext(searchResults, fallbackSummary)

        val systemPrompt = if (webSearchEnabled) SYSTEM_PROMPT + WEB_SEARCH_ADDENDUM else SYSTEM_PROMPT
        val llmMessages = mutableListOf(
            ChatMessageDto("system", systemPrompt),
            ChatMessageDto("system", "Debate topic: $debateTopic\n\n$context")
        )
        val recent = messages.takeLast(MAX_CONVERSATION_MESSAGES)
        llmMessages.addAll(recent.map { ChatMessageDto(it.role, it.content) })

        onProgress(if (webSearchEnabled) "Thinking (web search available)…" else "Thinking…")
        return try {
            val result = llmService.chatCompletion(
                feature = LlmFeature.DISCUSS,
                messages = llmMessages,
                temperature = 0.7f,
                maxTokens = 1500,
                cacheSystemPrompt = true,
                enableWebSearch = webSearchEnabled
            )
            val concluded = result.content.contains(CONCLUSION_MARKER)
            val cleaned = result.content.replace(CONCLUSION_MARKER, "").trimEnd()
            ChatMessage("assistant", cleaned, modelUsed = model, concluded = concluded, webSearchCount = result.webSearchCalls)
        } catch (e: Exception) {
            ChatMessage("assistant", "Error: ${e.message}")
        }
    }
}
