package com.threatloom.app.domain.usecase

import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import com.threatloom.app.data.remote.api.FeedService
import com.threatloom.app.data.remote.dto.GitHubCommitDto
import com.threatloom.app.data.repository.ArticleRepository
import com.threatloom.app.data.repository.SourceRepository
import com.threatloom.app.util.AppLogger
import com.threatloom.app.util.DateUtils
import kotlinx.coroutines.CancellationException
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.regex.Pattern
import javax.inject.Inject

/**
 * Ingests Kaido's personal blog (https://nikhilh-20.github.io/blog/), which has no RSS
 * feed. blog/README.md is a hand-maintained Markdown table of contents whose relative
 * links map 1:1 to live post URLs, so it's parsed in place of a feed. Some linked pages
 * are themselves index pages (e.g. a monthly round-up listing several analyses one
 * directory level deeper) rather than real posts — those are detected and expanded
 * recursively rather than ingested directly. Every resolved leaf post is ingested
 * unconditionally (no relevance gate — this is curated content), and its category is
 * forced separately at summarization time (see SummarizeArticlesUseCase).
 */
class FetchKaidoBlogUseCase @Inject constructor(
    private val feedService: FeedService,
    private val sourceRepository: SourceRepository,
    private val articleRepository: ArticleRepository,
    private val appLogger: AppLogger
) {
    companion object {
        private const val TAG = "FetchKaidoBlog"
        const val BLOG_SOURCE_NAME = "Kaido's Blog"
        const val BLOG_SOURCE_URL = "https://nikhilh-20.github.io/blog/"
        private const val BLOG_PATH = "blog"
        private const val COMMITS_API_BASE =
            "https://api.github.com/repos/nikhilh-20/nikhilh-20.github.io/commits"
        private const val USER_AGENT = "ThreatLoom-Android/1.0 (+https://github.com/nikhilh-20/ThreatLoom)"
        private const val GITHUB_ACCEPT = "application/vnd.github+json"
        private const val MAX_DEPTH = 4

        // Matches "* [Title](./relative/path)" — the literal "./" prefix is required so
        // absolute/external links (e.g. the TOOLING section's GitHub repo link) are skipped.
        private val RE_LINK by lazy {
            Pattern.compile("""^\s*[-*]\s*\[(.+?)\]\(\./([^)]+)\)\s*$""", Pattern.MULTILINE)
        }

        private val githubDateFormat by lazy {
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }
        }

        private fun readmeUrl(ghPath: String) =
            "https://raw.githubusercontent.com/nikhilh-20/nikhilh-20.github.io/main/$ghPath/README.md"
    }

    private data class LeafPost(val title: String, val liveUrl: String, val ghPath: String)

    private val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
    private val commitsAdapter = moshi.adapter<List<GitHubCommitDto>>(
        Types.newParameterizedType(List::class.java, GitHubCommitDto::class.java)
    )

    private fun parseReadme(text: String): List<Pair<String, String>> {
        val results = mutableListOf<Pair<String, String>>()
        val matcher = RE_LINK.matcher(text)
        while (matcher.find()) {
            val title = matcher.group(1)?.trim() ?: continue
            val relPath = matcher.group(2)?.trim()?.trim('/') ?: continue
            if (title.isNotEmpty() && relPath.isNotEmpty()) results.add(title to relPath)
        }
        return results
    }

    /** Fetches raw README.md content at a repo path, e.g. "blog" or a nested post dir. */
    private suspend fun fetchReadme(ghPath: String): String? {
        return try {
            feedService.fetchUrl(url = readmeUrl(ghPath), userAgent = USER_AGENT).string()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            appLogger.w(TAG, "Failed to fetch README at $ghPath: ${e.message}")
            null
        }
    }

    /**
     * Recursively expands README links into leaf posts. A link is treated as an
     * index/container page — and expanded instead of ingested — when its own README.md
     * contains further relative list links. A link whose README.md has no further list
     * links is a genuine leaf post.
     */
    private suspend fun resolveLeafPosts(
        ghPath: String,
        livePrefix: String,
        links: List<Pair<String, String>>,
        depth: Int = 0
    ): List<LeafPost> {
        val leaves = mutableListOf<LeafPost>()
        for ((title, relPath) in links) {
            val childGhPath = "$ghPath/$relPath"
            val childLiveUrl = "$livePrefix$relPath/"

            if (depth >= MAX_DEPTH) {
                leaves.add(LeafPost(title, childLiveUrl, childGhPath))
                continue
            }

            val childReadme = fetchReadme(childGhPath) ?: continue
            val childLinks = parseReadme(childReadme)
            if (childLinks.isNotEmpty()) {
                leaves.addAll(resolveLeafPosts(childGhPath, "$livePrefix$relPath/", childLinks, depth + 1))
            } else {
                leaves.add(LeafPost(title, childLiveUrl, childGhPath))
            }
        }
        return leaves
    }

    /**
     * Resolves a post's publish date via one GitHub commit-history API call, using the
     * most recent commit touching ghPath as an accessible proxy for "publish date"
     * (finding the true earliest commit would require paginating to the last page, not
     * worth the extra API calls here). Returns null on any failure — the article is
     * still ingested, just without a resolved date.
     */
    private suspend fun lookupPublishedDate(ghPath: String): String? {
        return try {
            val json = feedService.fetchUrl(
                url = "$COMMITS_API_BASE?path=$ghPath&per_page=1",
                userAgent = USER_AGENT,
                accept = GITHUB_ACCEPT
            ).string()
            val commits = commitsAdapter.fromJson(json)
            val dateStr = commits?.firstOrNull()?.commit?.committer?.date ?: return null
            // GitHub dates are "...Z"-suffixed ISO-8601; reformat into the app's stored
            // date format so sorting/display is consistent with RSS/Malpedia articles.
            val date = githubDateFormat.parse(dateStr) ?: return null
            DateUtils.formatIso(date)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            appLogger.w(TAG, "Failed to resolve publish date for $ghPath: ${e.message}")
            null
        }
    }

    /**
     * Deletes existing articles confirmed, right now, to be container pages. Earlier
     * versions of this fetcher treated index/container pages as leaf articles. Those
     * pages are never produced by the current resolution logic, so any existing article
     * not in this refresh's leaf set is re-checked directly — only deleted if its
     * README.md still demonstrably contains further list links, so a transient fetch
     * failure elsewhere can never cause a real article to be pruned.
     */
    private suspend fun pruneStaleContainers(sourceId: Long, leaves: List<LeafPost>) {
        val leafUrls = leaves.map { it.liveUrl }.toSet()
        for ((id, url) in articleRepository.getIdsAndUrlsBySource(sourceId)) {
            if (url in leafUrls || !url.startsWith(BLOG_SOURCE_URL)) continue

            val rel = url.removePrefix(BLOG_SOURCE_URL).trim('/')
            val ghPath = if (rel.isNotEmpty()) "$BLOG_PATH/$rel" else BLOG_PATH
            val readme = fetchReadme(ghPath)
            if (readme != null && parseReadme(readme).isNotEmpty()) {
                appLogger.i(TAG, "Kaido's Blog: pruning stale container article $url")
                articleRepository.delete(id)
            }
        }
    }

    suspend operator fun invoke(): Int {
        val sourceId = sourceRepository.upsertSource(BLOG_SOURCE_NAME, BLOG_SOURCE_URL)

        if (!sourceRepository.isSourceEnabled(BLOG_SOURCE_URL)) {
            appLogger.i(TAG, "Kaido's Blog disabled, skipping")
            return 0
        }

        val readmeText = fetchReadme(BLOG_PATH) ?: return 0
        val topLinks = parseReadme(readmeText)
        val leaves = resolveLeafPosts(BLOG_PATH, BLOG_SOURCE_URL, topLinks)

        pruneStaleContainers(sourceId, leaves)

        var newCount = 0
        for (leaf in leaves) {
            if (articleRepository.existsByUrl(leaf.liveUrl)) continue

            val publishedDate = lookupPublishedDate(leaf.ghPath)
            val id = articleRepository.insert(
                sourceId = sourceId, title = leaf.title, url = leaf.liveUrl,
                author = "Kaido", publishedDate = publishedDate, imageUrl = null
            )
            if (id > 0) newCount++
        }

        sourceRepository.updateLastFetched(sourceId)
        appLogger.i(TAG, "Kaido's Blog: $newCount new posts")
        return newCount
    }
}
