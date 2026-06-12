package com.threatloom.app.domain.usecase

import com.threatloom.app.data.preferences.SettingsDataStore
import com.threatloom.app.data.remote.api.MalpediaApi
import com.threatloom.app.data.repository.ArticleRepository
import com.threatloom.app.data.repository.SourceRepository
import com.threatloom.app.util.AppLogger
import com.threatloom.app.util.DateUtils
import kotlinx.coroutines.flow.first
import java.net.URI
import java.util.regex.Pattern
import javax.inject.Inject

class FetchMalpediaUseCase @Inject constructor(
    private val malpediaApi: MalpediaApi,
    private val sourceRepository: SourceRepository,
    private val articleRepository: ArticleRepository,
    private val checkRelevanceUseCase: CheckRelevanceUseCase,
    private val settingsDataStore: SettingsDataStore,
    private val appLogger: AppLogger
) {
    companion object {
        private const val TAG = "FetchMalpedia"
        private const val MALPEDIA_SOURCE_URL = "https://malpedia.caad.fkie.fraunhofer.de/library"

        private val SKIP_EXTENSIONS = setOf(
            ".pdf", ".doc", ".docx", ".xls", ".xlsx", ".ppt", ".pptx",
            ".zip", ".rar", ".7z", ".gz", ".tar", ".tgz",
            ".exe", ".msi", ".dmg", ".apk", ".iso"
        )

        private val RE_ENTRY by lazy { Pattern.compile("@\\w+\\{[^,]+,(.*?)\\n\\}", Pattern.DOTALL) }
        private val RE_TITLE by lazy { Pattern.compile("title\\s*=\\s*\\{\\{(.+?)\\}\\}", Pattern.DOTALL) }
        private val RE_DATE by lazy { Pattern.compile("date\\s*=\\s*\\{(\\d{4}-\\d{2}-\\d{2})\\}") }
        private val RE_URL by lazy { Pattern.compile("url\\s*=\\s*\\{(.+?)\\}") }
        private val RE_AUTHOR by lazy { Pattern.compile("author\\s*=\\s*\\{(.+?)\\}") }
        private val RE_ORG by lazy { Pattern.compile("organization\\s*=\\s*\\{(.+?)\\}") }
    }

    private fun isFileUrl(url: String): Boolean {
        return try {
            val path = URI(url).path?.lowercase() ?: return false
            SKIP_EXTENSIONS.any { path.endsWith(it) }
        } catch (e: Exception) { false }
    }

    private data class BibEntry(
        val title: String,
        val url: String,
        val date: String?,
        val author: String?
    )

    private fun parseBibtex(text: String): List<BibEntry> {
        val entries = mutableListOf<BibEntry>()
        val matcher = RE_ENTRY.matcher(text)
        while (matcher.find()) {
            val body = matcher.group(1) ?: continue

            val urlMatch = RE_URL.matcher(body)
            if (!urlMatch.find()) continue
            val url = urlMatch.group(1)?.trim() ?: continue

            val titleMatch = RE_TITLE.matcher(body)
            if (!titleMatch.find()) continue
            val title = titleMatch.group(1)?.trim() ?: continue

            val dateMatch = RE_DATE.matcher(body)
            val date = if (dateMatch.find()) dateMatch.group(1) else null

            val authorMatch = RE_AUTHOR.matcher(body)
            val author = if (authorMatch.find()) authorMatch.group(1)?.trim() else null

            val orgMatch = RE_ORG.matcher(body)
            val org = if (orgMatch.find()) orgMatch.group(1)?.trim() else null

            val displayAuthor = when {
                author != null && org != null -> "$author ($org)"
                author != null -> author
                else -> org
            }

            entries.add(BibEntry(title, url, date, displayAuthor))
        }
        return entries
    }

    suspend operator fun invoke(lookbackDays: Int = 1): Int {
        val apiKey = settingsDataStore.malpediaApiKey.first().trim()
        if (apiKey.isBlank()) {
            appLogger.i(TAG, "Malpedia API key not configured, skipping")
            return 0
        }

        val sourceId = sourceRepository.upsertSource("Malpedia", MALPEDIA_SOURCE_URL)

        appLogger.i(TAG, "Fetching Malpedia library...")
        val bibtex = try {
            malpediaApi.getBibtex("APIToken $apiKey").string()
        } catch (e: Exception) {
            appLogger.e(TAG, "Failed to fetch Malpedia BibTeX: ${e.message}")
            return 0
        }

        val lastFetched = sourceRepository.getLastFetched(sourceId)
        val cutoff = if (lookbackDays > 0) DateUtils.cutoffIso(lookbackDays)
                     else lastFetched ?: DateUtils.cutoffIso(1)
        val allEntries = parseBibtex(bibtex)
        appLogger.i(TAG, "Parsed ${allEntries.size} BibTeX entries, cutoff=$cutoff")

        var skippedNoDate = 0
        var skippedTooOld = 0
        var skippedFileUrl = 0

        data class Candidate(
            val title: String, val url: String, val pubDate: String?, val author: String?
        )

        val candidates = mutableListOf<Candidate>()

        for (entry in allEntries) {
            if (entry.date == null) { skippedNoDate++; continue }

            // BibTeX dates are YYYY-MM-DD, convert to ISO for comparison
            val pubDateIso = "${entry.date}T00:00:00"
            if (!DateUtils.isAfter(pubDateIso, cutoff)) { skippedTooOld++; continue }

            if (isFileUrl(entry.url)) { skippedFileUrl++; continue }
            if (!(entry.url.startsWith("http://") || entry.url.startsWith("https://"))) continue

            candidates.add(Candidate(entry.title, entry.url, pubDateIso, entry.author))
        }

        appLogger.i(TAG, "${candidates.size} candidates (skipped: $skippedTooOld too old, " +
            "$skippedNoDate no date, $skippedFileUrl file URLs)")

        if (candidates.isEmpty()) {
            sourceRepository.updateLastFetched(sourceId)
            return 0
        }

        val titles = candidates.map { it.title }
        val relevance = checkRelevanceUseCase(titles)
        var totalNew = 0
        var irrelevantCount = 0
        var duplicateCount = 0

        for ((candidate, isRelevant) in candidates.zip(relevance)) {
            if (!isRelevant) { irrelevantCount++; continue }
            val id = articleRepository.insert(
                sourceId = sourceId, title = candidate.title,
                url = candidate.url, author = candidate.author,
                publishedDate = candidate.pubDate, imageUrl = null
            )
            if (id > 0) totalNew++ else duplicateCount++
        }

        sourceRepository.updateLastFetched(sourceId)
        appLogger.i(TAG, "Malpedia: $totalNew new, $duplicateCount duplicates, $irrelevantCount irrelevant")
        return totalNew
    }
}
