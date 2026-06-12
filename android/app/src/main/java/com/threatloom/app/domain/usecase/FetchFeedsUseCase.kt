package com.threatloom.app.domain.usecase

import com.rometools.rome.io.SyndFeedInput
import com.threatloom.app.data.preferences.SettingsDataStore
import com.threatloom.app.data.remote.api.FeedService
import com.threatloom.app.data.repository.ArticleRepository
import com.threatloom.app.data.repository.SourceRepository
import com.threatloom.app.util.AppLogger
import com.threatloom.app.util.DateUtils
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Semaphore
import java.io.StringReader
import java.net.URI
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject

class FetchFeedsUseCase @Inject constructor(
    private val sourceRepository: SourceRepository,
    private val articleRepository: ArticleRepository,
    private val feedService: FeedService,
    private val checkRelevanceUseCase: CheckRelevanceUseCase,
    private val settingsDataStore: SettingsDataStore,
    private val appLogger: AppLogger
) {
    companion object {
        private const val TAG = "FetchFeeds"
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

    suspend operator fun invoke(lookbackDays: Int = 1): Int {
        val sources = sourceRepository.getEnabledSources()
        val concurrency = settingsDataStore.parallelRequests.first().coerceIn(1, 20)
        val totalNew = AtomicInteger(0)
        val semaphore = Semaphore(concurrency)

        // fetchSingleFeed returns the sourceId on success, null on per-feed failure,
        // and rethrows CancellationException. awaitAll() only returns if not cancelled,
        // so updateLastFetched is never called on an aborted run.
        val fetchedSourceIds = coroutineScope {
            sources.map { source ->
                async {
                    semaphore.acquire()
                    try {
                        fetchSingleFeed(source, lookbackDays, totalNew)
                    } finally {
                        semaphore.release()
                    }
                }
            }.awaitAll()
        }.filterNotNull()

        for (sourceId in fetchedSourceIds) {
            sourceRepository.updateLastFetched(sourceId)
        }

        appLogger.i(TAG, "Total new articles fetched: ${totalNew.get()}")
        return totalNew.get()
    }

    /** Returns the sourceId if the feed was fetched successfully, null on per-feed error. */
    private suspend fun fetchSingleFeed(
        source: com.threatloom.app.domain.model.Source,
        lookbackDays: Int,
        totalNew: AtomicInteger
    ): Long? {
        try {
            val sourceId = sourceRepository.upsertSource(source.name, source.url, source.enabled)
            appLogger.i(TAG, "Fetching feed: ${source.name}")

            val xmlContent = try {
                feedService.fetchUrl(source.url).string()
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                appLogger.d(TAG, "Feed download failed for ${source.name}: ${e.message}")
                return null
            }

            val feed = try {
                SyndFeedInput().build(StringReader(xmlContent))
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                appLogger.w(TAG, "Failed to parse feed ${source.name}: ${e.message}")
                return null
            }

            val lastFetched = sourceRepository.getLastFetched(sourceId)
            val cutoff = if (lookbackDays > 0) DateUtils.cutoffIso(lookbackDays)
                         else lastFetched ?: DateUtils.cutoffIso(1)

            data class Candidate(
                val title: String, val link: String, val pubDate: String?,
                val author: String?, val imageUrl: String?
            )

            val candidates = mutableListOf<Candidate>()
            var skippedNoLink = 0
            var skippedNoTitle = 0
            var skippedFileUrl = 0
            var skippedTooOld = 0
            var skippedNoDate = 0

            appLogger.i(TAG, "${source.name}: feed has ${feed.entries.size} entries, cutoff=$cutoff")

            for (entry in feed.entries) {
                val link = entry.link?.trim()
                if (link == null) { skippedNoLink++; continue }
                val title = entry.title?.trim()
                if (title == null) { skippedNoTitle++; continue }
                if (isFileUrl(link)) { skippedFileUrl++; continue }

                val pubDate = entry.publishedDate?.let { DateUtils.formatIso(it) }
                    ?: entry.updatedDate?.let { DateUtils.formatIso(it) }

                if (pubDate != null && !DateUtils.isAfter(pubDate, cutoff)) {
                    skippedTooOld++
                    appLogger.d(TAG, "  Skipped (too old, $pubDate): $title")
                    continue
                }
                if (pubDate == null && lookbackDays <= 0 && lastFetched != null) {
                    skippedNoDate++
                    continue
                }

                val imageUrl = entry.enclosures?.firstOrNull { it.type?.startsWith("image/") == true }?.url
                    ?: entry.foreignMarkup?.firstOrNull()?.let { null }

                candidates.add(Candidate(title, link, pubDate, entry.author, imageUrl))
            }

            appLogger.i(TAG, "${source.name}: ${candidates.size} candidates " +
                "(skipped: $skippedTooOld too old, $skippedFileUrl file URLs, " +
                "$skippedNoLink no link, $skippedNoTitle no title, $skippedNoDate no date)")

            if (candidates.isNotEmpty()) {
                val titles = candidates.map { it.title }
                val relevance = checkRelevanceUseCase(titles)
                var irrelevantCount = 0
                var duplicateCount = 0
                var feedNew = 0

                for ((candidate, isRelevant) in candidates.zip(relevance)) {
                    if (!isRelevant) { irrelevantCount++; continue }
                    val id = articleRepository.insert(
                        sourceId = sourceId, title = candidate.title,
                        url = candidate.link, author = candidate.author,
                        publishedDate = candidate.pubDate, imageUrl = candidate.imageUrl
                    )
                    if (id > 0) feedNew++ else duplicateCount++
                }
                totalNew.addAndGet(feedNew)
                appLogger.i(TAG, "${source.name}: $feedNew new, $duplicateCount duplicates, $irrelevantCount irrelevant")
            }

            return sourceId
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            appLogger.e(TAG, "Error fetching feed ${source.name}: ${e.message}")
            return null
        }
    }
}
