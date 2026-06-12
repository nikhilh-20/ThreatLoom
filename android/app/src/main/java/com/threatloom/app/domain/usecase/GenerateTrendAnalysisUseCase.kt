package com.threatloom.app.domain.usecase

import com.threatloom.app.data.preferences.SettingsDataStore
import com.threatloom.app.data.remote.dto.ChatMessageDto
import com.threatloom.app.data.remote.dto.QuarterlyTrendResult
import com.threatloom.app.data.repository.TrendAnalysisRepository
import com.threatloom.app.domain.model.ArticleWithSummary
import com.threatloom.app.domain.model.TrendAnalysis
import com.threatloom.app.domain.service.CostTracker
import com.threatloom.app.domain.service.LlmService
import com.threatloom.app.util.AppLogger
import com.threatloom.app.util.DateUtils
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Semaphore
import java.security.MessageDigest
import java.util.Calendar
import java.util.TimeZone
import javax.inject.Inject

class GenerateTrendAnalysisUseCase @Inject constructor(
    private val categorizeArticlesUseCase: CategorizeArticlesUseCase,
    private val trendAnalysisRepository: TrendAnalysisRepository,
    private val llmService: LlmService,
    private val settingsDataStore: SettingsDataStore,
    private val costTracker: CostTracker,
    private val appLogger: AppLogger
) {
    companion object {
        private const val TAG = "TrendAnalysis"
        private const val BATCH_SIZE = 50
        private const val MAX_SUMMARY_CHARS = 300

        private const val BATCH_SUMMARY_PROMPT = """You are a senior cybersecurity threat intelligence analyst.
Summarize the key cybersecurity themes from these {category} articles into a concise overview.
Focus on: common attack patterns, notable threat actors, affected sectors, and emerging techniques.
Produce a JSON object with one key:
- "trend": A concise summary (2-3 paragraphs of markdown) of the main themes.
Respond ONLY with valid JSON."""

        private const val QUARTERLY_TREND_FIRST_PROMPT = """You are a senior cybersecurity threat-intelligence strategist.
Analyze cybersecurity trends in {category} for {period} based on {count} articles.
Produce a JSON object with exactly three keys:
- "trend": A detailed analysis (3-5 paragraphs of markdown) of how threats in this category evolved during this quarter.
- "key_developments": A JSON array of 3-7 strings, each a concise bullet describing a key development.
- "outlook": A forward-looking paragraph on what to expect next quarter based on these trends.
Use markdown formatting. Be specific and cite patterns from the provided summaries.
Respond ONLY with valid JSON."""

        private const val QUARTERLY_TREND_SUBSEQUENT_PROMPT = """You are a senior cybersecurity threat-intelligence strategist.
Analyze cybersecurity trends in {category} for {period} based on {count} articles.

Previous quarter's trend analysis:
{prev_trend}

Produce a JSON object with exactly three keys:
- "trend": A detailed analysis (3-5 paragraphs of markdown) of how threats evolved this quarter. Explicitly compare and correlate with the previous quarter's trends — what continued, what changed, what's new.
- "key_developments": A JSON array of 3-7 strings, each a concise bullet describing a key development.
- "outlook": A forward-looking paragraph on what to expect next quarter based on observed trajectory.
Use markdown formatting. Be specific and cite patterns from the provided summaries.
Respond ONLY with valid JSON."""

        private const val YEARLY_TREND_FIRST_PROMPT = """You are a senior cybersecurity threat-intelligence strategist.
Synthesize these quarterly analyses for {category} in {year} into a comprehensive yearly trend report.

{quarterly_summaries}

Produce a JSON object with exactly three keys:
- "trend": A comprehensive yearly analysis (4-6 paragraphs of markdown) synthesizing all quarters. Identify overarching themes, major shifts, and year-defining developments.
- "key_developments": A JSON array of 5-10 strings, each a concise bullet describing the year's most significant developments.
- "outlook": A forward-looking assessment (2-3 paragraphs) predicting where this category is headed in the coming year.
Use markdown formatting. Be specific.
Respond ONLY with valid JSON."""

        private const val YEARLY_TREND_SUBSEQUENT_PROMPT = """You are a senior cybersecurity threat-intelligence strategist.
Synthesize these quarterly analyses for {category} in {year} into a comprehensive yearly trend report.

{quarterly_summaries}

Previous year's trend analysis:
{prev_trend}

Produce a JSON object with exactly three keys:
- "trend": A comprehensive yearly analysis (4-6 paragraphs of markdown) synthesizing all quarters. Explicitly compare with the previous year — what intensified, what declined, what emerged as new.
- "key_developments": A JSON array of 5-10 strings, each a concise bullet describing the year's most significant developments.
- "outlook": A forward-looking assessment (2-3 paragraphs) predicting where this category is headed in the coming year based on multi-year trajectory.
Use markdown formatting. Be specific.
Respond ONLY with valid JSON."""
    }

    private val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
    private val resultAdapter = moshi.adapter(QuarterlyTrendResult::class.java)

    data class QuarterKey(val year: Int, val quarter: Int) : Comparable<QuarterKey> {
        val label: String get() = "$year-Q$quarter"
        override fun compareTo(other: QuarterKey): Int {
            val yearCmp = year.compareTo(other.year)
            return if (yearCmp != 0) yearCmp else quarter.compareTo(other.quarter)
        }
    }

    suspend operator fun invoke(
        categoryName: String,
        preFilteredArticles: List<ArticleWithSummary>? = null,
        onProgress: (String) -> Unit = {}
    ): Pair<List<TrendAnalysis>, List<TrendAnalysis>> {
        if (!llmService.hasApiKey()) {
            onProgress("No API key configured")
            return Pair(emptyList(), emptyList())
        }

        val model = llmService.getModelName()
        val parallelRequests = settingsDataStore.parallelRequests.first().coerceIn(1, 20)

        onProgress("Loading articles for $categoryName...")
        val articles = preFilteredArticles ?: categorizeArticlesUseCase.getArticlesForCategory(categoryName)
        if (articles.size < 3) {
            onProgress("Not enough articles (${articles.size}) for trend analysis")
            return Pair(emptyList(), emptyList())
        }

        // Group articles by quarter
        val quarterGroups = groupByQuarter(articles)
        if (quarterGroups.isEmpty()) {
            onProgress("No articles with valid dates found")
            return Pair(emptyList(), emptyList())
        }

        onProgress("Found ${articles.size} articles across ${quarterGroups.size} quarters")

        // Generate quarterly trends sequentially (each references previous)
        val quarterlyTrends = mutableListOf<TrendAnalysis>()
        val sortedQuarters = quarterGroups.keys.sorted()

        for ((index, quarterKey) in sortedQuarters.withIndex()) {
            val quarterArticles = quarterGroups[quarterKey] ?: continue
            onProgress("Analyzing ${quarterKey.label} (${quarterArticles.size} articles)...")

            val hash = computeHash(quarterArticles)

            // Check cache
            val cached = trendAnalysisRepository.getByCategoryAndPeriod(
                categoryName, "quarterly", quarterKey.label
            )
            if (cached != null && cached.articleHash == hash) {
                appLogger.i(TAG, "Using cached quarterly trend for ${quarterKey.label}")
                quarterlyTrends.add(cached)
                continue
            }

            val previousTrend = if (index > 0) quarterlyTrends.lastOrNull()?.trendText else null
            val summaryText = buildArticleSummaries(
                quarterArticles, categoryName, parallelRequests, onProgress, quarterKey.label
            )

            val trendText = generateQuarterlyTrend(
                categoryName, quarterKey, quarterArticles.size,
                summaryText, previousTrend
            )

            if (trendText != null) {
                trendAnalysisRepository.upsert(
                    categoryName, "quarterly", quarterKey.label,
                    trendText, quarterArticles.size, hash, model
                )
                val trend = TrendAnalysis(
                    categoryName, "quarterly", quarterKey.label,
                    trendText, quarterArticles.size, hash
                )
                quarterlyTrends.add(trend)
            }
        }

        // Generate yearly trends
        val yearlyTrends = mutableListOf<TrendAnalysis>()
        val years = sortedQuarters.map { it.year }.distinct().sorted()

        for ((index, year) in years.withIndex()) {
            onProgress("Synthesizing yearly trend for $year...")

            val yearQuarterly = quarterlyTrends.filter { it.periodLabel.startsWith("$year-") }
            if (yearQuarterly.isEmpty()) continue

            val yearArticleCount = yearQuarterly.sumOf { it.articleCount }
            val yearHash = yearQuarterly.joinToString(":") { it.articleHash }

            // Check cache
            val cached = trendAnalysisRepository.getByCategoryAndPeriod(
                categoryName, "yearly", year.toString()
            )
            if (cached != null && cached.articleHash == yearHash) {
                appLogger.i(TAG, "Using cached yearly trend for $year")
                yearlyTrends.add(cached)
                continue
            }

            val previousYearTrend = if (index > 0) yearlyTrends.lastOrNull()?.trendText else null
            val trendText = generateYearlyTrend(
                categoryName, year, yearQuarterly, previousYearTrend
            )

            if (trendText != null) {
                trendAnalysisRepository.upsert(
                    categoryName, "yearly", year.toString(),
                    trendText, yearArticleCount, yearHash, model
                )
                val trend = TrendAnalysis(
                    categoryName, "yearly", year.toString(),
                    trendText, yearArticleCount, yearHash
                )
                yearlyTrends.add(trend)
            }
        }

        onProgress("Trend analysis complete")
        return Pair(quarterlyTrends, yearlyTrends)
    }

    private fun groupByQuarter(articles: List<ArticleWithSummary>): Map<QuarterKey, List<ArticleWithSummary>> {
        val groups = mutableMapOf<QuarterKey, MutableList<ArticleWithSummary>>()
        for (article in articles) {
            val date = DateUtils.parseIso(article.publishedDate) ?: continue
            val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
            cal.time = date
            val year = cal.get(Calendar.YEAR)
            val month = cal.get(Calendar.MONTH) // 0-based
            val quarter = (month / 3) + 1
            val key = QuarterKey(year, quarter)
            groups.getOrPut(key) { mutableListOf() }.add(article)
        }
        return groups
    }

    private val execSummaryRegex = Regex("""# Executive Summary\s*\n([\s\S]*?)(?=\n#|\n*$)""", RegexOption.IGNORE_CASE)

    private fun extractExecSummary(article: ArticleWithSummary): String {
        val summary = article.summaryText ?: return article.title
        val match = execSummaryRegex.find(summary)?.groupValues?.get(1)?.trim()
        val text = match ?: summary.take(MAX_SUMMARY_CHARS)
        return text.take(MAX_SUMMARY_CHARS)
    }

    private suspend fun buildArticleSummaries(
        articles: List<ArticleWithSummary>,
        categoryName: String,
        parallelRequests: Int,
        onProgress: (String) -> Unit,
        periodLabel: String
    ): String {
        if (articles.size <= BATCH_SIZE) {
            return articles.joinToString("\n") { art ->
                val dateStr = art.publishedDate ?: "unknown date"
                "- **${art.title}** ($dateStr): ${extractExecSummary(art)}"
            }
        }

        // Batch processing for large quarters
        val batches = articles.chunked(BATCH_SIZE)
        onProgress("$periodLabel: Processing ${batches.size} batches in parallel...")

        val semaphore = Semaphore(parallelRequests)
        val batchSummaries = coroutineScope {
            batches.mapIndexed { idx, batch ->
                async {
                    semaphore.acquire()
                    try {
                        val batchText = batch.joinToString("\n") { art ->
                            val dateStr = art.publishedDate ?: "unknown date"
                            "- **${art.title}** ($dateStr): ${extractExecSummary(art)}"
                        }
                        summarizeBatch(categoryName, batchText)
                            ?: "Batch ${idx + 1}: ${batch.size} articles (summary unavailable)"
                    } finally {
                        semaphore.release()
                    }
                }
            }.awaitAll()
        }

        // Merge batch summaries
        return batchSummaries.joinToString("\n\n---\n\n")
    }

    private suspend fun summarizeBatch(categoryName: String, batchText: String): String? {
        return try {
            val prompt = BATCH_SUMMARY_PROMPT.replace("{category}", categoryName)
            val llmResult = llmService.chatCompletion(
                systemPrompt = prompt,
                messages = listOf(ChatMessageDto("user", batchText)),
                temperature = 0.3f,
                maxTokens = 1500,
                jsonMode = true,
                cacheSystemPrompt = true
            )
            costTracker.addTokens(llmResult.inputTokens, llmResult.outputTokens)
            val result = moshi.adapter(Map::class.java).fromJson(llmResult.content)
            @Suppress("UNCHECKED_CAST")
            (result as? Map<String, Any>)?.get("trend") as? String
        } catch (e: Exception) {
            appLogger.e(TAG, "Batch summarization failed: ${e.message}")
            null
        }
    }

    private suspend fun generateQuarterlyTrend(
        categoryName: String,
        quarterKey: QuarterKey,
        articleCount: Int,
        summaryText: String,
        previousTrend: String?
    ): String? {
        return try {
            val promptTemplate = if (previousTrend != null) {
                QUARTERLY_TREND_SUBSEQUENT_PROMPT
                    .replace("{category}", categoryName)
                    .replace("{period}", quarterKey.label)
                    .replace("{count}", articleCount.toString())
                    .replace("{prev_trend}", previousTrend.take(3000))
            } else {
                QUARTERLY_TREND_FIRST_PROMPT
                    .replace("{category}", categoryName)
                    .replace("{period}", quarterKey.label)
                    .replace("{count}", articleCount.toString())
            }

            val llmResult = llmService.chatCompletion(
                systemPrompt = promptTemplate,
                messages = listOf(
                    ChatMessageDto("user", "Category: $categoryName\nPeriod: ${quarterKey.label}\nArticle count: $articleCount\n\n$summaryText")
                ),
                temperature = 0.4f,
                maxTokens = 2500,
                jsonMode = true,
                cacheSystemPrompt = true
            )
            costTracker.addTokens(llmResult.inputTokens, llmResult.outputTokens)

            val result = resultAdapter.fromJson(llmResult.content) ?: return null
            formatTrendResult(result)
        } catch (e: Exception) {
            appLogger.e(TAG, "Quarterly trend generation failed for ${quarterKey.label}: ${e.message}")
            null
        }
    }

    private suspend fun generateYearlyTrend(
        categoryName: String,
        year: Int,
        quarterlyTrends: List<TrendAnalysis>,
        previousYearTrend: String?
    ): String? {
        return try {
            val quarterlySummaries = quarterlyTrends.joinToString("\n\n") { qt ->
                "### ${qt.periodLabel} (${qt.articleCount} articles)\n${qt.trendText}"
            }

            val promptTemplate = if (previousYearTrend != null) {
                YEARLY_TREND_SUBSEQUENT_PROMPT
                    .replace("{category}", categoryName)
                    .replace("{year}", year.toString())
                    .replace("{quarterly_summaries}", quarterlySummaries.take(8000))
                    .replace("{prev_trend}", previousYearTrend.take(3000))
            } else {
                YEARLY_TREND_FIRST_PROMPT
                    .replace("{category}", categoryName)
                    .replace("{year}", year.toString())
                    .replace("{quarterly_summaries}", quarterlySummaries.take(8000))
            }

            val llmResult = llmService.chatCompletion(
                systemPrompt = promptTemplate,
                messages = listOf(
                    ChatMessageDto("user", "Category: $categoryName\nYear: $year\nQuarters covered: ${quarterlyTrends.size}")
                ),
                temperature = 0.4f,
                maxTokens = 3000,
                jsonMode = true,
                cacheSystemPrompt = true
            )
            costTracker.addTokens(llmResult.inputTokens, llmResult.outputTokens)

            val result = resultAdapter.fromJson(llmResult.content) ?: return null
            formatTrendResult(result)
        } catch (e: Exception) {
            appLogger.e(TAG, "Yearly trend generation failed for $year: ${e.message}")
            null
        }
    }

    private fun formatTrendResult(result: QuarterlyTrendResult): String {
        val sb = StringBuilder()
        sb.appendLine(result.trend)
        if (result.keyDevelopments.isNotEmpty()) {
            sb.appendLine()
            sb.appendLine("**Key Developments:**")
            result.keyDevelopments.forEach { sb.appendLine("- $it") }
        }
        if (result.outlook.isNotBlank()) {
            sb.appendLine()
            sb.appendLine("**Outlook:**")
            sb.appendLine(result.outlook)
        }
        return sb.toString().trim()
    }

    private fun computeHash(articles: List<ArticleWithSummary>): String {
        val tuples = articles.sortedBy { it.id }.map { "${it.id}:${(it.summaryText ?: "").length}" }
        val md = MessageDigest.getInstance("SHA-256")
        return md.digest(tuples.toString().toByteArray()).take(8).joinToString("") { "%02x".format(it) }
    }
}
