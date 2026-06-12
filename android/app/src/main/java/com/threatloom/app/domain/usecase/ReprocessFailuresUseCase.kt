package com.threatloom.app.domain.usecase

import com.threatloom.app.data.preferences.SettingsDataStore
import com.threatloom.app.data.repository.ArticleRepository
import com.threatloom.app.data.repository.SummaryRepository
import com.threatloom.app.domain.service.CostTracker
import com.threatloom.app.domain.service.LlmService
import com.threatloom.app.util.AppLogger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject

/**
 * Reprocesses failed/pending articles with the same cost gate as the main pipeline, so the
 * stats-section actions show a pre-summarization estimate dialog and a post-run actual-cost dialog.
 *
 * Mirrors the tail of [RunPipelineUseCase] (cost gate -> summarize -> embed). [Mode.RESCRAPE]
 * additionally re-scrapes the failed articles first, then summarizes them (matching the web's
 * scrape-failure reprocess), so it too surfaces a cost estimate + actual cost.
 */
class ReprocessFailuresUseCase @Inject constructor(
    private val articleRepository: ArticleRepository,
    private val summaryRepository: SummaryRepository,
    private val scrapeArticlesUseCase: ScrapeArticlesUseCase,
    private val summarizeArticlesUseCase: SummarizeArticlesUseCase,
    private val embedArticlesUseCase: EmbedArticlesUseCase,
    private val settingsDataStore: SettingsDataStore,
    private val costTracker: CostTracker,
    private val llmService: LlmService,
    private val appLogger: AppLogger
) {
    enum class Mode { RESCRAPE, RESUMMARIZE, SUMMARIZE_UNSUMMARIZED }

    companion object {
        private const val TAG = "Reprocess"
    }

    suspend operator fun invoke(
        mode: Mode,
        onProgress: (suspend (PipelineProgress) -> Unit)? = null,
        onConfirmCost: (suspend (CostEstimate) -> CompletableDeferred<Boolean>)? = null,
        onActualCost: ((ActualCostInfo) -> Unit)? = null,
        onRateLimited: (() -> Unit)? = null
    ) {
        costTracker.reset()
        val concurrency = settingsDataStore.parallelRequests.first().coerceIn(1, 20)
        appLogger.i(TAG, "Starting reprocess (mode=$mode, concurrency=$concurrency)...")

        // 1. Prep / scrape
        when (mode) {
            Mode.RESCRAPE -> {
                articleRepository.resetScrapeFailed()
                val toScrape = articleRepository.countUnscraped()
                onProgress?.invoke(PipelineProgress("scrape", "Re-scraping 0/$toScrape articles…", 0, toScrape))
                val scrapeCounter = AtomicInteger(0)
                scrapeArticlesUseCase(concurrency = concurrency) {
                    val count = scrapeCounter.incrementAndGet()
                    onProgress?.invoke(PipelineProgress("scrape", "Re-scraping $count/$toScrape articles…", count, toScrape))
                }
            }
            Mode.RESUMMARIZE -> summaryRepository.deleteFailedSummaries()
            Mode.SUMMARIZE_UNSUMMARIZED -> { /* nothing to prep */ }
        }

        // 2. Cost gate (mirrors RunPipelineUseCase)
        val toSummarize = articleRepository.countUnsummarized()
        var summarizationSkipped = false

        if (toSummarize > 0 && onConfirmCost != null && llmService.hasApiKey()) {
            val model = llmService.getModelName()
            val estimate = costTracker.estimateSummarizationCost(toSummarize, model)
            onProgress?.invoke(PipelineProgress("confirm", "Awaiting cost confirmation…", 0, toSummarize))
            val approved = onConfirmCost(CostEstimate(toSummarize, estimate, model)).await()
            if (!approved) {
                summarizationSkipped = true
                appLogger.i(TAG, "Summarization declined by user")
            }
        }

        // 3. Summarize
        var actualSummarized = 0
        if (!summarizationSkipped && toSummarize > 0) {
            onProgress?.invoke(PipelineProgress("summarize", "Summarizing 0/$toSummarize articles…", 0, toSummarize))
            val summarizeCounter = AtomicInteger(0)
            actualSummarized = summarizeArticlesUseCase(concurrency = concurrency, onRateLimited = onRateLimited) {
                val count = summarizeCounter.incrementAndGet()
                onProgress?.invoke(PipelineProgress("summarize", "Summarizing $count/$toSummarize articles…", count, toSummarize))
            }
            val model = llmService.getModelName()
            val actualCost = costTracker.getSessionCost(model)
            onActualCost?.invoke(ActualCostInfo(actualSummarized, actualCost, model))
            appLogger.i(TAG, "Reprocess summarized $actualSummarized/$toSummarize (actual cost: \$${"%.2f".format(actualCost)})")
        }

        // 4. Embed (so reprocessed articles get indexed; embedding doesn't touch costTracker)
        onProgress?.invoke(PipelineProgress("embed", "Generating embeddings…"))
        var batch: Int
        do {
            batch = embedArticlesUseCase(limit = 50)
        } while (batch > 0)

        onProgress?.invoke(PipelineProgress("done", "Done", 4, 4))
        appLogger.i(TAG, "Reprocess complete")
    }
}
