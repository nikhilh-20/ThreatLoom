package com.threatloom.app.domain.usecase

import com.threatloom.app.data.remote.dto.ChatMessageDto
import com.threatloom.app.data.repository.SummaryRepository
import com.threatloom.app.domain.model.ChatMessage
import com.threatloom.app.domain.model.ContextArticle
import com.threatloom.app.domain.model.LlmFeature
import com.threatloom.app.domain.model.RagChatResult
import com.threatloom.app.domain.model.SummarySection
import com.threatloom.app.domain.service.LlmService
import com.threatloom.app.util.AppLogger
import javax.inject.Inject

class DiscussUseCase @Inject constructor(
    private val llmService: LlmService,
    private val semanticSearchUseCase: SemanticSearchUseCase,
    private val summaryRepository: SummaryRepository,
    private val chatRouterUseCase: ChatRouterUseCase,
    private val ragContextAssembler: RagContextAssembler,
    private val appLogger: AppLogger
) {
    companion object {
        private const val TAG = "Discuss"
        private const val MAX_CONVERSATION_MESSAGES = 10
        private const val TOP_K = 12
        // Debate wants substance, so it always injects both the overview and the technical detail.
        private val DEBATE_SECTIONS = listOf(SummarySection.EXECUTIVE, SummarySection.DETAILS)

        const val CONCLUSION_MARKER = "[DEBATE_CONCLUDED]"
        private const val WEB_SEARCH_ADDENDUM = "\n\nYou have access to a web search tool. Use it when: (1) the user's question needs current or live information not covered by the context provided above, or (2) the provided articles lack a concrete real-world example to illustrate a technique or concept the user is asking about. Whenever you source an example via web search, cite it as a markdown link in the format [Title](URL) so the source is clearly attributed and tappable. Search only until you have enough information to answer confidently — a small number of targeted searches is usually sufficient; do not search exhaustively."

        private const val SYSTEM_PROMPT = """You are a seasoned cybersecurity analyst and discussion partner. You hold informed opinions and are willing to defend them, but you genuinely engage with the human's arguments.

Draw on the article context provided AND your broader knowledge of the cybersecurity threat landscape. When relevant, cross-reference patterns you see across multiple articles. When illustrating a point with examples, prefer specific details from the provided articles — named malware, real behaviors, concrete techniques — over generic hypothetical scenarios. If the articles lack a suitable example and web search is available, search for a real one and cite the source URL.

The provided articles are a ranked subset of the knowledge base and may show only selected sections; if an article you previously referenced is not in the current context, it was still real — never claim you fabricated it.

Challenge the user's thinking when appropriate. Ask follow-up questions. Be direct and substantive — this is a debate, not a Q&A session.

If you disagree with the user's position, say so clearly and explain why. If they make a strong point that shifts your view, acknowledge it.

When the human concedes your position, or you are genuinely persuaded by theirs, or you both clearly converge on a shared view, write a brief closing summary of where you landed and end your message with the marker [DEBATE_CONCLUDED] on its own final line. Only emit this marker when the debate is genuinely resolved — otherwise keep engaging and do not write it."""
    }

    suspend operator fun invoke(
        messages: List<ChatMessage>,
        originatingArticleId: Long,
        debateTopic: String,
        priorContext: List<ContextArticle> = emptyList(),
        webSearchEnabled: Boolean = false,
        onProgress: (String) -> Unit = {}
    ): RagChatResult {
        if (!llmService.hasApiKey(LlmFeature.DISCUSS)) {
            return RagChatResult(ChatMessage("assistant", "Please configure your API key in Settings."), priorContext)
        }

        val model = llmService.getModelName(LlmFeature.DISCUSS)
        val userMessages = messages.filter { it.role == "user" }

        // Step 1: route — a rebuttal ("that's not convincing") must NOT swap the grounding via a blind
        // re-search. The router decides whether fresh retrieval is warranted and rewrites the query.
        onProgress("Planning…")
        val loadedTitles = priorContext.map { it.article.title }
        val plan = chatRouterUseCase(messages, loadedTitles)
        val effectiveQuery = plan.query.ifBlank { userMessages.lastOrNull()?.content ?: debateTopic }
        val sinceDate = ragContextAssembler.sinceDateFromQuery(effectiveQuery)

        // Step 2: retrieve (only when asked), merge append-only so prior grounding is never dropped.
        var working = priorContext
        if (plan.needsRetrieval) {
            onProgress("Retrieving articles…")
            val results = semanticSearchUseCase(effectiveQuery, TOP_K, sinceDate)
            val before = working.size
            working = ragContextAssembler.mergeAppendOnly(priorContext, results, DEBATE_SECTIONS)
            appLogger.i(
                TAG,
                "Retrieval ON: query=\"${effectiveQuery.take(80)}\", ranked=${results.size}, workingSet $before -> ${working.size}"
            )
        } else {
            onProgress("Reviewing loaded articles…")
            appLogger.i(TAG, "Retrieval OFF: reusing ${working.size} loaded articles (cache-stable context)")
        }

        val context = if (working.isNotEmpty()) {
            ragContextAssembler.buildContext(
                working = working,
                total = working.size,
                emptyText = "",
                tag = TAG
            ) { _, _ -> "Relevant articles from the knowledge base (a ranked subset — earlier citations remain valid even if not shown here):\n\n" }
        } else {
            // Fallback: semantic search yielded nothing and nothing is loaded — anchor on the origin article.
            val fallback = summaryRepository.getSummaryText(originatingArticleId)
            if (!fallback.isNullOrBlank()) {
                "Article context (semantic search unavailable — using originating article only):\n\n$fallback"
            } else {
                "No article context available. Respond based on your general knowledge."
            }
        }

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
            appLogger.i(
                TAG,
                "LLM done: model=$model, in=${result.inputTokens}, out=${result.outputTokens}, cacheWrite=${result.cacheWriteTokens}, cacheRead=${result.cacheReadTokens}, webSearch=${result.webSearchCalls}"
            )
            val concluded = result.content.contains(CONCLUSION_MARKER)
            val cleaned = result.content.replace(CONCLUSION_MARKER, "").trimEnd()
            RagChatResult(ChatMessage("assistant", cleaned, modelUsed = model, concluded = concluded, webSearchCount = result.webSearchCalls), working)
        } catch (e: Exception) {
            appLogger.e(TAG, "LLM call failed: ${e.message}")
            RagChatResult(ChatMessage("assistant", "Error: ${e.message}"), working)
        }
    }
}
