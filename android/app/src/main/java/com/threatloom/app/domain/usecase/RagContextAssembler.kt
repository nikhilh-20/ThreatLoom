package com.threatloom.app.domain.usecase

import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import com.threatloom.app.domain.model.ArticleWithSummary
import com.threatloom.app.domain.model.ContextArticle
import com.threatloom.app.domain.model.SummarySection
import com.threatloom.app.util.AppLogger
import com.threatloom.app.util.DateUtils
import com.threatloom.app.util.SummarySections
import java.util.Calendar
import java.util.TimeZone
import javax.inject.Inject
import kotlin.math.ceil

/**
 * Shared RAG-context machinery for the app's retrieval-backed chats (category, intelligence, debate).
 *
 * Centralises three things those chats used to duplicate:
 *  - [sinceDateFromQuery]: natural-language recency window ("last 7 days") → ISO cut-off.
 *  - [mergeAppendOnly]: union freshly retrieved articles into the rolling working set WITHOUT dropping
 *    or reordering prior ones, so grounding never swaps mid-conversation and the cached prompt prefix
 *    stays stable for Anthropic prompt caching.
 *  - [buildContext]: render only the router-selected summary sections of each article, within a char
 *    budget, behind a caller-supplied header.
 */
class RagContextAssembler @Inject constructor(
    private val appLogger: AppLogger
) {
    companion object {
        const val MAX_CONTEXT_CHARS = 30000
    }

    private val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
    private val listAdapter = moshi.adapter<List<String>>(
        Types.newParameterizedType(List::class.java, String::class.java)
    )

    /** Extract a lookback window (in days) from natural-language time references in the query. */
    private fun extractSinceDays(query: String): Int? {
        val q = query.lowercase()
        val hoursPattern = Regex("""(?:last|past)\s+(\d+)\s+hours?|(\d+)\s+hours?\s+ago""")
        hoursPattern.find(q)?.let { m ->
            val hours = (m.groupValues[1].ifEmpty { m.groupValues[2] }).toIntOrNull() ?: return@let
            return maxOf(1, ceil(hours / 24.0).toInt())
        }
        Regex("""(?:last|past)\s+(\d+)\s+days?""").find(q)?.let { m ->
            return m.groupValues[1].toIntOrNull()
        }
        Regex("""\b(\d+)\s+hours?\b""").find(q)?.let { m ->
            val hours = m.groupValues[1].toIntOrNull() ?: return@let
            return maxOf(1, ceil(hours / 24.0).toInt())
        }
        if ("yesterday" in q) return 1
        if ("last week" in q || "past week" in q || "this week" in q) return 7
        if ("last month" in q || "past month" in q || "this month" in q) return 30
        return null
    }

    /** ISO cut-off date derived from any recency phrase in [query], or null if none. */
    fun sinceDateFromQuery(query: String): String? {
        val days = extractSinceDays(query) ?: return null
        val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        cal.add(Calendar.DAY_OF_YEAR, -days)
        return DateUtils.formatIso(cal.time)
    }

    /**
     * Union freshly retrieved [results] into the [prior] working set, append-only: existing articles
     * keep their position (stable cache prefix) and only gain sections; new articles are appended.
     */
    fun mergeAppendOnly(
        prior: List<ContextArticle>,
        results: List<ArticleWithSummary>,
        sections: List<SummarySection>
    ): List<ContextArticle> {
        val byId = LinkedHashMap<Long, ContextArticle>()
        for (c in prior) byId[c.article.id] = c
        for (art in results) {
            val existing = byId[art.id]
            byId[art.id] = if (existing == null) {
                ContextArticle(art, sections)
            } else {
                existing.copy(sections = (existing.sections + sections).distinct())
            }
        }
        return byId.values.toList()
    }

    /**
     * Render [working] into an LLM context string. Only each article's selected sections are emitted,
     * within [MAX_CONTEXT_CHARS]. [header] receives (shownCount, total) and returns the prefix text.
     */
    fun buildContext(
        working: List<ContextArticle>,
        total: Int,
        emptyText: String,
        tag: String,
        header: (shown: Int, total: Int) -> String
    ): String {
        if (working.isEmpty()) return emptyText

        val parts = mutableListOf<String>()
        var totalChars = 0
        for ((i, item) in working.withIndex()) {
            val art = item.article
            val tags = try { listAdapter.fromJson(art.tags ?: "[]") ?: emptyList() } catch (e: Exception) { emptyList() }
            val tagsStr = tags.joinToString(", ")
            val body = SummarySections.render(art.summaryText, item.sections)
            val sectionsLabel = item.sections.joinToString(", ") { it.header }
            val entry = "---\nArticle ${i + 1}: ${art.title}\nSource: ${art.sourceName ?: "Unknown"} | Date: ${art.publishedDate ?: "Unknown"} | Relevance: ${art.relevanceScore ?: 0f}\nTags: $tagsStr\nSections shown: $sectionsLabel\n\n$body\n"
            if (totalChars + entry.length > MAX_CONTEXT_CHARS && parts.isNotEmpty()) break
            parts.add(entry)
            totalChars += entry.length
        }
        appLogger.d(tag, "Context built: ${parts.size}/${working.size} articles, $totalChars chars (cap $MAX_CONTEXT_CHARS)")
        return header(parts.size, total) + parts.joinToString("\n")
    }
}
