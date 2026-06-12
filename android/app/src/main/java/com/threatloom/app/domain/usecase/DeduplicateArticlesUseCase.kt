package com.threatloom.app.domain.usecase

import com.threatloom.app.data.local.dao.ArticleDao
import com.threatloom.app.data.local.dao.ArticleDedupEmbeddingDao
import com.threatloom.app.data.local.dao.CorrelationDao
import com.threatloom.app.data.local.dao.DedupCandidate
import com.threatloom.app.data.local.entity.ArticleDedupEmbeddingEntity
import com.threatloom.app.data.local.entity.CorrelationEntity
import com.threatloom.app.data.preferences.SettingsDataStore
import com.threatloom.app.data.remote.api.OpenAiApi
import com.threatloom.app.data.remote.dto.EmbeddingRequest
import com.threatloom.app.util.AppLogger
import com.threatloom.app.util.DateUtils
import com.threatloom.app.util.EmbeddingMath
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import kotlin.math.abs

/**
 * Semantically deduplicates scraped-but-unsummarized articles before the expensive
 * summarization step. Two articles are considered duplicates only when their pre-summary
 * embeddings are at least [SettingsDataStore.dedupThreshold] similar AND they were published
 * within [WINDOW_MS] (24h) of each other — articles further apart are treated as legitimate
 * news updates and both kept.
 *
 * The longest article (most content) is retained; shorter near-duplicates are marked
 * `duplicate_of` the kept article (skipping their summarization) and linked via
 * [CorrelationEntity] so the kept article can surface an "also reported by" cross-reference.
 *
 * Embeddings require an OpenAI key; with no key (or when disabled) this is a silent no-op.
 */
class DeduplicateArticlesUseCase @Inject constructor(
    private val articleDao: ArticleDao,
    private val dedupEmbeddingDao: ArticleDedupEmbeddingDao,
    private val correlationDao: CorrelationDao,
    private val openAiApi: OpenAiApi,
    private val embeddingMath: EmbeddingMath,
    private val settingsDataStore: SettingsDataStore,
    private val appLogger: AppLogger
) {
    companion object {
        private const val TAG = "Dedup"
        private const val EMBEDDING_MODEL = "text-embedding-3-small"
        private const val CANDIDATE_LIMIT = 500
        private const val EMBED_BATCH = 50
        private const val CONTENT_SNIPPET = 2000
        private const val WINDOW_MS = 24L * 60 * 60 * 1000
    }

    private data class Embedded(val candidate: DedupCandidate, val vector: FloatArray)

    /** Returns the number of articles marked as duplicates (and thus skipped for summarization). */
    suspend operator fun invoke(): Int {
        if (!settingsDataStore.dedupEnabled.first()) return 0
        val apiKey = settingsDataStore.openaiApiKey.first()
        if (apiKey.isBlank()) return 0

        val candidates = articleDao.getDedupCandidates(CANDIDATE_LIMIT)
        if (candidates.isEmpty()) return 0

        val threshold = settingsDataStore.dedupThreshold.first()

        // 1. Embed every candidate (title + content snippet) and persist for future runs.
        val embedded = mutableListOf<Embedded>()
        try {
            for (chunk in candidates.chunked(EMBED_BATCH)) {
                val texts = chunk.map { "${it.title}\n${it.content_raw.take(CONTENT_SNIPPET)}" }
                val response = openAiApi.embeddings(EmbeddingRequest(model = EMBEDDING_MODEL, input = texts))
                for ((cand, data) in chunk.zip(response.data)) {
                    val blob = embeddingMath.floatsToBlob(data.embedding)
                    embedded.add(Embedded(cand, embeddingMath.blobToFloats(blob)))
                    dedupEmbeddingDao.upsert(
                        ArticleDedupEmbeddingEntity(
                            articleId = cand.id,
                            embedding = blob,
                            modelUsed = EMBEDDING_MODEL
                        )
                    )
                }
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            appLogger.e(TAG, "Dedup embedding failed, skipping deduplication: ${e.message}")
            return 0
        }

        // 2. References: already-summarized articles from the last 24h (comparable dedup embeddings).
        val cutoff = DateUtils.cutoffIso(1)
        val references = try {
            dedupEmbeddingDao.getReferencesSince(cutoff).map {
                Triple(it.article_id, embeddingMath.blobToFloats(it.embedding), it.published_date ?: it.fetched_date)
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            appLogger.e(TAG, "Failed to load dedup references: ${e.message}")
            emptyList()
        }

        val assigned = HashSet<Long>()
        var marked = 0

        // 3. Cross-run pass: a candidate matching an already-processed article is the duplicate
        //    (the reference is already summarized — keep it regardless of length to avoid re-summarizing).
        for (e in embedded) {
            val candDate = e.candidate.published_date ?: e.candidate.fetched_date
            for ((refId, refVector, refDate) in references) {
                if (!withinWindow(candDate, refDate)) continue
                val sim = embeddingMath.cosineSimilarity(e.vector, refVector)
                if (sim >= threshold) {
                    markDuplicate(keptId = refId, dupId = e.candidate.id, similarity = sim)
                    assigned.add(e.candidate.id)
                    marked++
                    break
                }
            }
        }

        // 4. Same-run clustering: candidates are length-desc ordered, so the first unassigned one
        //    is the longest and becomes the kept representative for its cluster.
        for (i in embedded.indices) {
            val head = embedded[i]
            if (head.candidate.id in assigned) continue
            val headDate = head.candidate.published_date ?: head.candidate.fetched_date
            for (j in i + 1 until embedded.size) {
                val other = embedded[j]
                if (other.candidate.id in assigned) continue
                val otherDate = other.candidate.published_date ?: other.candidate.fetched_date
                if (withinWindow(headDate, otherDate)) {
                    val sim = embeddingMath.cosineSimilarity(head.vector, other.vector)
                    if (sim >= threshold) {
                        markDuplicate(keptId = head.candidate.id, dupId = other.candidate.id, similarity = sim)
                        assigned.add(other.candidate.id)
                        marked++
                    }
                }
            }
        }

        if (marked > 0) appLogger.i(TAG, "Marked $marked duplicate articles across ${embedded.size} candidates")
        return marked
    }

    private suspend fun markDuplicate(keptId: Long, dupId: Long, similarity: Float) {
        articleDao.setDuplicateOf(dupId, keptId)
        correlationDao.insert(
            CorrelationEntity(
                articleId1 = keptId,
                articleId2 = dupId,
                correlationType = "duplicate",
                confidence = similarity,
                description = "Duplicate coverage of the same topic within 24h"
            )
        )
    }

    /** True when both dates parse and are within 24h of each other. Unknown timing → not a duplicate. */
    private fun withinWindow(a: String?, b: String?): Boolean {
        val da = DateUtils.parseIso(a) ?: return false
        val db = DateUtils.parseIso(b) ?: return false
        return abs(da.time - db.time) <= WINDOW_MS
    }
}
