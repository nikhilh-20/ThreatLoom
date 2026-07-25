package com.threatloom.app.domain.model

/**
 * One article currently loaded into a category chat's rolling context, together with which summary
 * [sections] have been injected for it. The chat's working set is a `List<ContextArticle>` that grows
 * append-only across turns: retrieval unions new articles/sections in but never drops or rewrites
 * prior ones, so (a) earlier grounding never disappears mid-conversation and (b) the cached prompt
 * prefix stays stable for Anthropic prompt caching.
 */
data class ContextArticle(
    val article: ArticleWithSummary,
    val sections: List<SummarySection>
)

/** Assistant reply plus the updated rolling context working set, shared by the RAG-backed chats. */
data class RagChatResult(
    val message: ChatMessage,
    val context: List<ContextArticle>
)
