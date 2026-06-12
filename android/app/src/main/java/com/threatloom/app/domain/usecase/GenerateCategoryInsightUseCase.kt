package com.threatloom.app.domain.usecase

import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import com.threatloom.app.data.remote.dto.*
import com.threatloom.app.data.repository.CategoryInsightRepository
import com.threatloom.app.domain.category.CategoryRules
import com.threatloom.app.domain.model.CategoryInsight
import com.threatloom.app.domain.service.CostTracker
import com.threatloom.app.domain.service.LlmService
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import javax.inject.Inject
import kotlinx.coroutines.delay

class GenerateCategoryInsightUseCase @Inject constructor(
    private val categorizeArticlesUseCase: CategorizeArticlesUseCase,
    private val categoryInsightRepository: CategoryInsightRepository,
    private val llmService: LlmService,
    private val costTracker: CostTracker
) {
    companion object {
        private const val TREND_FORECAST_PROMPT = """You are a senior cybersecurity threat-intelligence strategist.

You are given a set of recent threat-intelligence article summaries, all belonging to the
category "{category}".

Produce a JSON object with exactly two keys:

1. "trend" — A concise analysis (3-6 paragraphs of markdown) of how this threat category is
   evolving right now. Cover:
   * Evolving TTPs (tactics, techniques, and procedures)
   * New tools, malware families, or infrastructure being adopted
   * Shifts in targeting (industries, geographies, victim profiles)
   * Notable behavioral changes compared to earlier activity

2. "forecast" — A forward-looking assessment (2-4 paragraphs of markdown) predicting where
   this category is headed over the next 3-6 months. Cover:
   * Most likely developments and escalation paths
   * Emerging risks defenders should prepare for
   * Recommended priority areas for security teams

Use markdown formatting (headings, bold, bullet lists) to make the text scannable.
Be specific and cite patterns from the provided articles.
Respond ONLY with valid JSON."""
    }

    private val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
    private val resultAdapter = moshi.adapter(TrendForecastResult::class.java)

    private fun computeHash(articles: List<com.threatloom.app.domain.model.ArticleWithSummary>): String {
        val tuples = articles.sortedBy { it.id }.map { "${it.id}:${(it.summaryText ?: "").length}" }
        val md = MessageDigest.getInstance("SHA-256")
        return md.digest(tuples.toString().toByteArray()).take(8).joinToString("") { "%02x".format(it) }
    }

    private fun isCacheValid(cached: CategoryInsight?, currentHash: String): Boolean {
        if (cached == null || cached.articleHash != currentHash) return false
        val createdDate = cached.createdDate ?: return false
        return try {
            val created = Instant.parse(createdDate)
            Duration.between(created, Instant.now()).toHours() < 24
        } catch (e: Exception) {
            false
        }
    }

    suspend operator fun invoke(
        categoryName: String,
        subcategoryTag: String? = null,
        preFilteredArticles: List<com.threatloom.app.domain.model.ArticleWithSummary>? = null
    ): CategoryInsight? {
        if (!llmService.hasApiKey()) return null

        val model = llmService.getModelName()
        val articles = preFilteredArticles ?: categorizeArticlesUseCase.getArticlesForCategory(categoryName)
        if (articles.size < 3) return null

        val cacheKey = if (subcategoryTag != null) "$categoryName::$subcategoryTag" else categoryName
        val currentHash = computeHash(articles)

        // Check cache (hash + 24 h TTL)
        val cached = categoryInsightRepository.getByCategory(cacheKey)
        if (isCacheValid(cached, currentHash)) return cached

        val contextLabel = if (subcategoryTag != null) {
            "$categoryName > ${CategoryRules.formatEntityName(subcategoryTag)}"
        } else categoryName

        val execSummaryRegex = Regex("""# Executive Summary\s*\n([\s\S]*?)(?=\n#|\n*$)""", RegexOption.IGNORE_CASE)
        val lines = articles.map { art ->
            val dateStr = art.publishedDate ?: "unknown date"
            val summary = art.summaryText ?: ""
            val execMatch = execSummaryRegex.find(summary)?.groupValues?.get(1)?.trim() ?: summary.take(500)
            "- **${art.title}** ($dateStr): $execMatch"
        }

        var inputText = lines.joinToString("\n")
        if (inputText.length > 20000) inputText = inputText.take(20000) + "\n\n[Truncated...]"

        val prompt = TREND_FORECAST_PROMPT.replace("{category}", contextLabel)

        // 3-attempt retry with exponential backoff
        for (attempt in 0 until 3) {
            try {
                val llmResult = llmService.chatCompletion(
                    systemPrompt = prompt,
                    messages = listOf(
                        ChatMessageDto("user", "Category: $contextLabel\nArticle count: ${articles.size}\n\n$inputText")
                    ),
                    temperature = 0.4f,
                    maxTokens = 2000,
                    jsonMode = true,
                    cacheSystemPrompt = true
                )
                costTracker.addTokens(llmResult.inputTokens, llmResult.outputTokens)

                val result = resultAdapter.fromJson(llmResult.content) ?: return null

                categoryInsightRepository.upsert(cacheKey, result.trend, result.forecast, articles.size, currentHash, model)
                return CategoryInsight(cacheKey, result.trend, result.forecast, articles.size, currentHash, model)
            } catch (e: Exception) {
                if (attempt < 2) delay((attempt + 1) * 1000L)
            }
        }
        return null
    }
}
