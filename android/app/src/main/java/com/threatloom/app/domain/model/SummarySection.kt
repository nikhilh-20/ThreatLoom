package com.threatloom.app.domain.model

/**
 * The fixed markdown sections every article summary is generated with (see SummarizeArticlesUseCase).
 * The chat router selects which of these to inject into the LLM context per turn, so a lightweight
 * "list all titles" query can be answered with EXECUTIVE only while a deep-dive pulls DETAILS/MITIGATIONS.
 *
 * `header` matches the `# <Header>` markdown emitted in `summaries.summary_text`.
 * `token` is the lowercase identifier the router emits in its JSON `sections` array.
 */
enum class SummarySection(val header: String, val token: String) {
    EXECUTIVE("Executive Summary", "executive"),
    DETAILS("Details", "details"),
    MITIGATIONS("Mitigations", "mitigations"),
    IOCS("IOCs", "iocs"),
    ANALYST_NOTES("Analyst Notes", "analyst_notes");

    companion object {
        fun fromToken(token: String): SummarySection? =
            entries.firstOrNull { it.token.equals(token.trim(), ignoreCase = true) }
    }
}
