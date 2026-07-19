package com.threatloom.app.domain.model

/** Identifies a distinct LLM call site that can have its own provider/model override. */
enum class LlmFeature(val displayName: String) {
    ARTICLE_CHAT("Article Chat"),
    CATEGORY_CHAT("Category Chat"),
    INTELLIGENCE_CHAT("Intelligence Chat"),
    DISCUSS("Discuss (Debate)"),
    CATEGORY_INSIGHT("Category Insight"),
    TREND_ANALYSIS("Trend Analysis"),
    SUMMARIZATION("Article Summarization"),

    // Not exposed in the per-feature Model Settings screen (out of the initially agreed 7),
    // but still need their own resolution key so they aren't silently pulled into
    // whatever override SUMMARIZATION/INTELLIGENCE_CHAT ends up with.
    RELEVANCE_CHECK("Relevance Check"),
    QUIZ("Quiz")
}
