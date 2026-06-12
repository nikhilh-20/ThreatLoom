package com.threatloom.app.domain.usecase

import com.threatloom.app.data.remote.api.FeedService
import com.threatloom.app.data.repository.ArticleRepository
import com.threatloom.app.util.AppLogger
import com.threatloom.app.util.HtmlTextExtractor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import java.net.URI
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject

class ScrapeArticlesUseCase @Inject constructor(
    private val articleRepository: ArticleRepository,
    private val feedService: FeedService,
    private val htmlExtractor: HtmlTextExtractor,
    private val appLogger: AppLogger
) {
    companion object {
        private const val TAG = "ScrapeArticles"
        private val SKIP_EXTENSIONS = setOf(
            ".pdf", ".doc", ".docx", ".xls", ".xlsx", ".ppt", ".pptx",
            ".zip", ".rar", ".7z", ".gz", ".tar", ".tgz",
            ".exe", ".msi", ".dmg", ".apk", ".iso"
        )
    }

    private fun isFileUrl(url: String): Boolean {
        return try {
            val path = URI(url).path?.lowercase() ?: return false
            SKIP_EXTENSIONS.any { path.endsWith(it) }
        } catch (e: Exception) { false }
    }

    /**
     * Scrapes all unscraped articles in parallel with the given concurrency.
     * Returns total number of articles processed.
     */
    suspend operator fun invoke(
        concurrency: Int = 5,
        onEach: (suspend () -> Unit)? = null
    ): Int = coroutineScope {
        val articles = articleRepository.getUnscraped(500)
        if (articles.isEmpty()) return@coroutineScope 0

        val semaphore = Semaphore(concurrency)
        val scraped = AtomicInteger(0)

        articles.forEachIndexed { i, article ->
            launch {
                semaphore.acquire()
                try {
                    if (isFileUrl(article.url)) {
                        appLogger.i(TAG, "Skipping file URL, removing article ${article.id}")
                        articleRepository.delete(article.id)
                    } else {
                        appLogger.i(TAG, "Scraping [${i + 1}/${articles.size}]: ${article.url}")
                        try {
                            val html = feedService.fetchHtml(article.url).string()
                            val text = htmlExtractor.extract(article.url, html)
                            if (text != null) {
                                articleRepository.updateContent(article.id, text)
                                scraped.incrementAndGet()
                                appLogger.i(TAG, "  Scraped ${text.length} chars for article ${article.id}")
                            } else {
                                articleRepository.updateContent(article.id, "")
                                appLogger.w(TAG, "  Could not extract content for article ${article.id}")
                            }
                        } catch (e: Exception) {
                            if (e is CancellationException) throw e
                            articleRepository.updateContent(article.id, "")
                            appLogger.w(TAG, "  Scrape failed for article ${article.id}: ${e.message}")
                        }
                    }
                    onEach?.invoke()
                } finally {
                    semaphore.release()
                }
            }
        }

        // coroutineScope waits for all launched coroutines
        articles.size
    }
}
