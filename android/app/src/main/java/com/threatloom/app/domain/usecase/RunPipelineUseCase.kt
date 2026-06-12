package com.threatloom.app.domain.usecase

import com.threatloom.app.data.preferences.SettingsDataStore
import com.threatloom.app.data.repository.ArticleRepository
import com.threatloom.app.domain.service.CostTracker
import com.threatloom.app.domain.service.LlmService
import com.threatloom.app.util.AppLogger
import com.threatloom.app.util.DateUtils
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject

data class PipelineProgress(
    val stage: String,
    val detail: String,
    val current: Int = 0,
    val total: Int = 0
) {
    /** 0f..1f overall progress across all 4 stages (fetch, scrape, summarize, embed) */
    val overallFraction: Float get() {
        val stageWeight = when (stage) {
            "fetch" -> 0
            "scrape" -> 1
            "confirm" -> 2
            "summarize" -> 2
            "embed" -> 3
            "done" -> 4
            else -> 0
        }
        val stageFraction = if (total > 0) current.toFloat() / total else 1f
        return ((stageWeight + stageFraction) / 4f).coerceIn(0f, 1f)
    }
}

data class CostEstimate(
    val articleCount: Int,
    val estimatedCost: Double,
    val modelName: String
)

data class ActualCostInfo(
    val articleCount: Int,
    val actualCost: Double,
    val modelName: String
)

class RunPipelineUseCase @Inject constructor(
    private val fetchFeedsUseCase: FetchFeedsUseCase,
    private val fetchMalpediaUseCase: FetchMalpediaUseCase,
    private val scrapeArticlesUseCase: ScrapeArticlesUseCase,
    private val deduplicateArticlesUseCase: DeduplicateArticlesUseCase,
    private val summarizeArticlesUseCase: SummarizeArticlesUseCase,
    private val embedArticlesUseCase: EmbedArticlesUseCase,
    private val articleRepository: ArticleRepository,
    private val settingsDataStore: SettingsDataStore,
    private val costTracker: CostTracker,
    private val llmService: LlmService,
    private val appLogger: AppLogger
) {
    companion object {
        private const val TAG = "Pipeline"
    }

    suspend operator fun invoke(
        lookbackDays: Int = 1,
        onProgress: (suspend (PipelineProgress) -> Unit)? = null,
        onConfirmCost: (suspend (CostEstimate) -> CompletableDeferred<Boolean>)? = null,
        onActualCost: ((ActualCostInfo) -> Unit)? = null,
        onRateLimited: (() -> Unit)? = null
    ) {
        costTracker.reset()
        val concurrency = settingsDataStore.parallelRequests.first().coerceIn(1, 20)
        val cutoffDisplay = if (lookbackDays > 0) {
            val cutoffDate = DateUtils.cutoffIso(lookbackDays)
            DateUtils.formatDisplay(cutoffDate)
        } else "last fetch"
        onProgress?.invoke(PipelineProgress("fetch", "Fetching feeds since $cutoffDisplay…"))
        appLogger.i(TAG, "Starting pipeline (lookback=${lookbackDays}d, concurrency=$concurrency)...")

        val newFromFeeds = fetchFeedsUseCase(lookbackDays)
        appLogger.i(TAG, "Fetched $newFromFeeds new articles from RSS feeds")

        onProgress?.invoke(PipelineProgress("fetch", "Fetching Malpedia library…"))
        val newFromMalpedia = fetchMalpediaUseCase(lookbackDays)
        appLogger.i(TAG, "Fetched $newFromMalpedia new articles from Malpedia")

        val newArticles = newFromFeeds + newFromMalpedia
        appLogger.i(TAG, "Total new articles: $newArticles")

        // Scrape — parallel (processes all unscraped articles)
        val toScrape = articleRepository.countUnscraped()
        onProgress?.invoke(PipelineProgress("scrape", "Scraping 0/$toScrape articles…", 0, toScrape))
        val scrapeCounter = AtomicInteger(0)
        val totalScraped = scrapeArticlesUseCase(concurrency = concurrency) {
            val count = scrapeCounter.incrementAndGet()
            onProgress?.invoke(PipelineProgress("scrape", "Scraping $count/$toScrape articles…", count, toScrape))
        }
        appLogger.i(TAG, "Scraped $totalScraped articles")

        // Deduplicate — mark near-duplicate coverage (same topic within 24h) so only the
        // longest article in each cluster is summarized. Silent no-op without an OpenAI key.
        onProgress?.invoke(PipelineProgress("scrape", "Checking for duplicate coverage…", toScrape, toScrape))
        val deduped = deduplicateArticlesUseCase()
        if (deduped > 0) appLogger.i(TAG, "Marked $deduped duplicate articles (skipped summarization)")

        // Cost confirmation before summarization
        val toSummarize = articleRepository.countUnsummarized()
        var summarizationSkipped = false

        if (toSummarize > 0 && onConfirmCost != null && llmService.hasApiKey()) {
            val model = llmService.getModelName()
            val estimate = costTracker.estimateSummarizationCost(toSummarize, model)
            val costEstimate = CostEstimate(toSummarize, estimate, model)

            onProgress?.invoke(PipelineProgress("confirm", "Awaiting cost confirmation…", 0, toSummarize))
            val deferred = onConfirmCost(costEstimate)
            val approved = deferred.await()

            if (!approved) {
                summarizationSkipped = true
                appLogger.i(TAG, "Summarization declined by user")
            }
        }

        var actualSummarized = 0
        var summarizeFailed = 0
        if (!summarizationSkipped && toSummarize > 0) {
            // Summarize — parallel (processes all unsummarized articles)
            onProgress?.invoke(PipelineProgress("summarize", "Summarizing 0/$toSummarize articles…", 0, toSummarize))
            val summarizeCounter = AtomicInteger(0)
            actualSummarized = summarizeArticlesUseCase(concurrency = concurrency, onRateLimited = onRateLimited) {
                val count = summarizeCounter.incrementAndGet()
                onProgress?.invoke(PipelineProgress("summarize", "Summarizing $count/$toSummarize articles…", count, toSummarize))
            }
            summarizeFailed = toSummarize - actualSummarized
            appLogger.i(TAG, "Summarized $actualSummarized/$toSummarize articles ($summarizeFailed failed)")

            val model = llmService.getModelName()
            val actualCost = costTracker.getSessionCost(model)
            onActualCost?.invoke(ActualCostInfo(actualSummarized, actualCost, model))
            appLogger.i(TAG, "Pipeline summarization complete (actual cost: \$${"%.2f".format(actualCost)})")
        } else if (summarizationSkipped) {
            appLogger.i(TAG, "Summarization skipped by user")
        }

        // Embed — batch loop until all unembedded articles are processed.
        // Runs unconditionally so previously-summarized articles are also indexed.
        // EmbedArticlesUseCase returns 0 silently when no OpenAI key is configured.
        var totalEmbedded = 0
        onProgress?.invoke(PipelineProgress("embed", "Generating embeddings…"))
        var batch: Int
        do {
            batch = embedArticlesUseCase(limit = 50)
            totalEmbedded += batch
        } while (batch > 0)
        if (totalEmbedded > 0) appLogger.i(TAG, "Generated embeddings for $totalEmbedded articles")

        val doneDetail = buildString {
            if (newArticles > 0) append("$newArticles new")
            if (deduped > 0) {
                if (isNotEmpty()) append(", ")
                append("$deduped deduped")
            }
            if (!summarizationSkipped && toSummarize > 0) {
                if (isNotEmpty()) append(", ")
                append("$actualSummarized summarized")
                if (summarizeFailed > 0) append(" ($summarizeFailed failed)")
            }
            if (totalEmbedded > 0) {
                if (isNotEmpty()) append(", ")
                append("$totalEmbedded indexed")
            }
            if (isEmpty()) append(if (summarizationSkipped) "summarization skipped" else "no new articles since $cutoffDisplay")
        }
        onProgress?.invoke(PipelineProgress("done", "Done — $doneDetail", 4, 4))
        appLogger.i(TAG, "Pipeline complete")
    }
}
