package com.threatloom.app.util

import com.threatloom.app.domain.model.SummarySection

/**
 * Splits an article's `summary_text` (uniform `# <Header>` markdown) into its named sections and
 * renders back only the sections the chat router asked for. Decoupling the *ranking* text (full
 * summary, embedded for recall) from the *context* text (a chosen subset) is what lets many more
 * articles fit the LLM context budget without dropping to keyword-only retrieval.
 */
object SummarySections {

    // Matches a markdown H1 header line, capturing the header title.
    private val headerRegex = Regex("""(?m)^#\s+(.+?)\s*$""")

    /** Parse a full summary into { section -> body text }. Unknown headers are ignored. */
    fun parse(summaryText: String?): Map<SummarySection, String> {
        if (summaryText.isNullOrBlank()) return emptyMap()
        val matches = headerRegex.findAll(summaryText).toList()
        if (matches.isEmpty()) return emptyMap()

        val result = LinkedHashMap<SummarySection, String>()
        for ((i, m) in matches.withIndex()) {
            val headerTitle = m.groupValues[1].trim()
            val section = SummarySection.entries.firstOrNull { it.header.equals(headerTitle, ignoreCase = true) }
                ?: continue
            val bodyStart = m.range.last + 1
            val bodyEnd = if (i + 1 < matches.size) matches[i + 1].range.first else summaryText.length
            result[section] = summaryText.substring(bodyStart, bodyEnd).trim()
        }
        return result
    }

    /**
     * Render the requested [sections] of [summaryText] as markdown. Sections are emitted in the
     * canonical enum order (not the router's arbitrary order) for stable, cache-friendly output.
     * Falls back to the whole summary if parsing yields nothing (defensive: never drop grounding).
     */
    fun render(summaryText: String?, sections: List<SummarySection>): String {
        if (summaryText.isNullOrBlank()) return ""
        val parsed = parse(summaryText)
        if (parsed.isEmpty()) return summaryText.trim()

        val wanted = if (sections.isEmpty()) listOf(SummarySection.EXECUTIVE) else sections
        val ordered = SummarySection.entries.filter { it in wanted && parsed.containsKey(it) }
        if (ordered.isEmpty()) {
            // Requested sections absent for this article — fall back to Executive Summary if present.
            parsed[SummarySection.EXECUTIVE]?.let { return "## ${SummarySection.EXECUTIVE.header}\n$it" }
            return summaryText.trim()
        }
        return ordered.joinToString("\n\n") { "## ${it.header}\n${parsed[it]}" }
    }
}
