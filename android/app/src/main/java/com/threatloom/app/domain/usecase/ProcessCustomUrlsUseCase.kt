package com.threatloom.app.domain.usecase

import com.threatloom.app.data.preferences.SettingsDataStore
import com.threatloom.app.data.repository.ArticleRepository
import com.threatloom.app.data.repository.SourceRepository
import com.threatloom.app.domain.service.CostTracker
import com.threatloom.app.domain.service.LlmService
import com.threatloom.app.util.AppLogger
import com.threatloom.app.util.DateUtils
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import java.net.URI
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject

class ProcessCustomUrlsUseCase @Inject constructor(
    private val articleRepository: ArticleRepository,
    private val sourceRepository: SourceRepository,
    private val scrapeArticlesUseCase: ScrapeArticlesUseCase,
    private val summarizeArticlesUseCase: SummarizeArticlesUseCase,
    private val embedArticlesUseCase: EmbedArticlesUseCase,
    private val settingsDataStore: SettingsDataStore,
    private val costTracker: CostTracker,
    private val llmService: LlmService,
    private val appLogger: AppLogger
) {
    companion object {
        private const val TAG = "ProcessCustomUrls"
        private const val MANUAL_SOURCE_NAME = "Manual"
        private const val MANUAL_SOURCE_URL = "manual://custom"
    }

    suspend operator fun invoke(
        urls: List<String>,
        onProgress: (suspend (PipelineProgress) -> Unit)? = null,
        onConfirmCost: (suspend (CostEstimate) -> CompletableDeferred<Boolean>)? = null,
        onActualCost: ((ActualCostInfo) -> Unit)? = null,
        onRateLimited: (() -> Unit)? = null
    ): Int {
        costTracker.reset()
        val concurrency = settingsDataStore.parallelRequests.first().coerceIn(1, 20)

        // Get or create the "Manual" pseudo-source
        val sourceId = sourceRepository.upsertSource(MANUAL_SOURCE_NAME, MANUAL_SOURCE_URL, enabled = true)

        // Insert valid, non-duplicate URLs
        val now = DateUtils.nowIso()
        var inserted = 0
        for (raw in urls) {
            val url = raw.trim()
            if (!url.startsWith("http://") && !url.startsWith("https://")) continue
            val title = titleFromUrl(url)
            val id = articleRepository.insert(
                sourceId = sourceId,
                title = title,
                url = url,
                author = null,
                publishedDate = now,
                imageUrl = null
            )
            if (id > 0L) {
                inserted++
                appLogger.i(TAG, "Inserted article $id: $url")
            } else {
                appLogger.i(TAG, "Skipped duplicate URL: $url")
            }
        }

        if (inserted == 0) {
            onProgress?.invoke(PipelineProgress("done", "No new URLs inserted (duplicates or invalid)"))
            return 0
        }

        // Scrape
        val toScrape = articleRepository.countUnscraped()
        onProgress?.invoke(PipelineProgress("scrape", "Scraping 0/$toScrape articles…", 0, toScrape))
        val scrapeCounter = AtomicInteger(0)
        scrapeArticlesUseCase(concurrency = concurrency) {
            val count = scrapeCounter.incrementAndGet()
            onProgress?.invoke(PipelineProgress("scrape", "Scraping $count/$toScrape articles…", count, toScrape))
        }

        // Cost confirmation before summarization
        val toSummarize = articleRepository.countUnsummarized()
        var summarizationSkipped = false
        if (toSummarize > 0 && onConfirmCost != null && llmService.hasApiKey()) {
            val model = llmService.getModelName()
            val estimate = costTracker.estimateSummarizationCost(toSummarize, model)
            val costEstimate = CostEstimate(toSummarize, estimate, model)
            onProgress?.invoke(PipelineProgress("confirm", "Awaiting cost confirmation…", 0, toSummarize))
            val deferred = onConfirmCost(costEstimate)
            if (!deferred.await()) {
                summarizationSkipped = true
                appLogger.i(TAG, "Summarization declined by user")
            }
        }

        var actualSummarized = 0
        var summarizeFailed = 0
        if (!summarizationSkipped && toSummarize > 0) {
            onProgress?.invoke(PipelineProgress("summarize", "Summarizing 0/$toSummarize articles…", 0, toSummarize))
            val summarizeCounter = AtomicInteger(0)
            actualSummarized = summarizeArticlesUseCase(concurrency = concurrency, onRateLimited = onRateLimited) {
                val count = summarizeCounter.incrementAndGet()
                onProgress?.invoke(PipelineProgress("summarize", "Summarizing $count/$toSummarize articles…", count, toSummarize))
            }
            summarizeFailed = toSummarize - actualSummarized
            appLogger.i(TAG, "Summarized $actualSummarized/$toSummarize ($summarizeFailed failed)")

            val model = llmService.getModelName()
            val actualCost = costTracker.getSessionCost(model)
            onActualCost?.invoke(ActualCostInfo(actualSummarized, actualCost, model))
        }

        // Embed
        onProgress?.invoke(PipelineProgress("embed", "Generating embeddings…"))
        var totalEmbedded = 0
        var batch: Int
        do {
            batch = embedArticlesUseCase(limit = 50)
            totalEmbedded += batch
        } while (batch > 0)

        val doneDetail = buildString {
            append("$inserted inserted")
            if (!summarizationSkipped && toSummarize > 0) {
                append(", $actualSummarized summarized")
                if (summarizeFailed > 0) append(" ($summarizeFailed failed)")
            }
            if (totalEmbedded > 0) append(", $totalEmbedded indexed")
        }
        onProgress?.invoke(PipelineProgress("done", "Done — $doneDetail", 4, 4))
        return inserted
    }

    private fun titleFromUrl(url: String): String {
        return try {
            val path = URI(url).path ?: return url
            path.trimEnd('/')
                .substringAfterLast('/')
                .replace("-", " ")
                .replace("_", " ")
                .replace(Regex("\\.[a-zA-Z0-9]{2,5}$"), "")
                .trim()
                .replaceFirstChar { it.uppercase() }
                .take(150)
                .ifBlank { url }
        } catch (_: Exception) { url }
    }
}
