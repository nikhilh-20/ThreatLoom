package com.threatloom.app.domain.usecase

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import com.threatloom.app.data.remote.dto.ChatMessageDto
import com.threatloom.app.domain.model.ChatMessage
import com.threatloom.app.domain.model.LlmFeature
import com.threatloom.app.domain.model.SummarySection
import com.threatloom.app.domain.service.LlmService
import com.threatloom.app.util.AppLogger
import javax.inject.Inject

/** Router decision for a single chat turn. */
data class RetrievalPlan(
    val needsRetrieval: Boolean,
    val sections: List<SummarySection>,
    val query: String
)

/**
 * Cheap pre-RAG routing call. Given the recent conversation and the articles already loaded in
 * context, it decides whether the turn needs fresh retrieval and which summary sections to pull.
 *
 * This fixes two things at once:
 *  - Follow-ups like "any others?" no longer trigger a fresh semantic search on meaningless text
 *    (which used to swap the grounding out from under the conversation).
 *  - The context stays identical across "no retrieval" turns, so the cached prompt prefix is reused.
 *
 * The router is advisory: even if it wrongly asks to retrieve, the caller merges results append-only,
 * so prior grounding is never lost. On any failure it falls back to "retrieve, executive section".
 */
class ChatRouterUseCase @Inject constructor(
    private val llmService: LlmService,
    private val appLogger: AppLogger
) {
    companion object {
        private const val TAG = "ChatRouter"
        private const val MAX_ROUTER_MESSAGES = 6

        private const val ROUTER_SYSTEM = """You are a retrieval router for a cybersecurity threat-intelligence chat assistant. A set of article summaries may already be loaded in the conversation context. Decide how to handle the user's LATEST message.

Respond with ONLY a JSON object, no prose:
{
  "needs_retrieval": boolean,
  "sections": string[],
  "query": string
}

Field rules:
- "needs_retrieval": true if NEW articles must be fetched to answer; false if the already-loaded articles listed below are sufficient.
    - false for follow-ups answerable from loaded articles, e.g. "any others?", "just these two?", "tell me more about the second one", "summarize that", "which of those mention X".
    - true when the user raises a NEW topic, malware family, threat actor, technique, or timeframe, OR when no articles are loaded yet.
- "sections": the MINIMAL summary sections to load for retrieved articles. Allowed values ONLY: "executive", "details", "mitigations", "iocs", "analyst_notes".
    - Listing / overview / "what articles" / breadth queries -> ["executive"]
    - How a technique or attack chain works, technical behavior -> ["details"]
    - Defense, mitigation, remediation, hardening -> ["mitigations"]
    - Indicators, hashes, domains, IPs -> ["iocs"]
    - Combine only when clearly needed. If needs_retrieval is false, return [].
- "query": a SELF-CONTAINED search query capturing what to retrieve, resolving pronouns/references using the conversation. Empty string "" if needs_retrieval is false."""
    }

    private val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
    private val adapter = moshi.adapter(RouterResponseDto::class.java)

    @JsonClass(generateAdapter = true)
    data class RouterResponseDto(
        @Json(name = "needs_retrieval") val needsRetrieval: Boolean = true,
        val sections: List<String>? = null,
        val query: String? = null
    )

    suspend operator fun invoke(
        messages: List<ChatMessage>,
        loadedTitles: List<String>
    ): RetrievalPlan {
        val lastUser = messages.lastOrNull { it.role == "user" }?.content?.trim().orEmpty()
        val fallback = RetrievalPlan(
            needsRetrieval = true,
            sections = listOf(SummarySection.EXECUTIVE),
            query = lastUser
        )

        if (!llmService.hasApiKey(LlmFeature.CHAT_ROUTER)) {
            appLogger.i(TAG, "No API key for CHAT_ROUTER; using fallback plan (retrieve/executive)")
            return fallback
        }

        val loadedBlock = if (loadedTitles.isEmpty()) "(none — no articles loaded yet)"
        else loadedTitles.mapIndexed { i, t -> "${i + 1}. $t" }.joinToString("\n")
        val system = "$ROUTER_SYSTEM\n\nArticles currently loaded in context:\n$loadedBlock"

        val recent = messages.takeLast(MAX_ROUTER_MESSAGES)
            .filter { it.content.isNotBlank() }
            .map { ChatMessageDto(if (it.role == "assistant") "assistant" else "user", it.content) }

        appLogger.d(TAG, "Routing turn: loadedTitles=${loadedTitles.size}, lastUser=\"${lastUser.take(80)}\"")

        return try {
            val result = llmService.chatCompletion(
                feature = LlmFeature.CHAT_ROUTER,
                systemPrompt = system,
                messages = recent.ifEmpty { listOf(ChatMessageDto("user", lastUser)) },
                temperature = 0f,
                maxTokens = 300,
                jsonMode = true
            )
            val dto = adapter.fromJson(result.content)
            if (dto == null) {
                appLogger.e(TAG, "Router JSON parse returned null; content=\"${result.content.take(200)}\"")
                return fallback
            }
            val sections = (dto.sections ?: emptyList())
                .mapNotNull { SummarySection.fromToken(it) }
                .distinct()
                .ifEmpty { if (dto.needsRetrieval) listOf(SummarySection.EXECUTIVE) else emptyList() }
            val query = dto.query?.trim().takeUnless { it.isNullOrBlank() } ?: lastUser
            val plan = RetrievalPlan(
                needsRetrieval = dto.needsRetrieval,
                sections = sections,
                query = if (dto.needsRetrieval) query else ""
            )
            appLogger.i(
                TAG,
                "Router plan: needsRetrieval=${plan.needsRetrieval}, sections=${plan.sections.map { it.token }}, query=\"${plan.query.take(80)}\""
            )
            plan
        } catch (e: Exception) {
            appLogger.e(TAG, "Router call failed: ${e.message}; using fallback plan")
            fallback
        }
    }
}
