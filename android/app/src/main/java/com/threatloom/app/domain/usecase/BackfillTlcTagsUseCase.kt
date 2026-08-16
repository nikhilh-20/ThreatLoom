package com.threatloom.app.domain.usecase

import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import com.threatloom.app.data.remote.dto.ChatMessageDto
import com.threatloom.app.data.remote.dto.TlcTaggingResult
import com.threatloom.app.data.repository.ArticleRepository
import com.threatloom.app.data.repository.SummaryRepository
import com.threatloom.app.domain.model.LlmFeature
import com.threatloom.app.domain.model.TlcTaxonomy
import com.threatloom.app.domain.service.CostTracker
import com.threatloom.app.domain.service.LlmService
import com.threatloom.app.util.AppLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject

/**
 * Backfills tlc- catalogue tags onto articles that were already summarized before the catalogue
 * existed. Classifies against the existing composed summary_text instead of re-extracting from
 * the raw article, so it's much cheaper than [SummarizeArticlesUseCase] and never touches the
 * existing summary/details/mitigations/iocs/attack_flow — only appends to the tags column.
 */
class BackfillTlcTagsUseCase @Inject constructor(
    private val articleRepository: ArticleRepository,
    private val summaryRepository: SummaryRepository,
    private val llmService: LlmService,
    private val costTracker: CostTracker,
    private val appLogger: AppLogger
) {
    companion object {
        private const val TAG = "BackfillTlcTags"

        private val TLC_TAGGING_PROMPT = """You are classifying an already-written cybersecurity threat-intel summary against a fixed technique catalogue.

Given the summary provided in the <summary> element, return a JSON object with a single key:

- "tlc_tags": A JSON array of one or more tags from this FIXED catalogue — a closed vocabulary.
  Select every entry below whose description clearly matches a technique described in the summary.
  Do not invent new tags and do not paraphrase — copy the tag string exactly as written. If
  nothing in the catalogue clearly applies (including summaries that describe no specific
  attack/defense technique at all), return ["tlc-unknown"]. Never return an empty array.

${TlcTaxonomy.promptBlock}

Respond ONLY with valid JSON: {"tlc_tags": [...]}"""
    }

    private val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
    private val tagsListAdapter = moshi.adapter<List<String>>(
        Types.newParameterizedType(List::class.java, String::class.java)
    )
    private val taggingAdapter = moshi.adapter(TlcTaggingResult::class.java)

    /** Number of already-summarized articles that don't have a tlc- tag yet. */
    suspend fun countPending(): Int = summaryRepository.countMissingTlcTags()

    suspend operator fun invoke(
        concurrency: Int = 5,
        onProgress: (suspend (PipelineProgress) -> Unit)? = null,
        onConfirmCost: (suspend (CostEstimate) -> CompletableDeferred<Boolean>)? = null,
        onActualCost: ((ActualCostInfo) -> Unit)? = null
    ): Int {
        if (!llmService.hasApiKey(LlmFeature.TLC_TAGGING)) return 0
        val articleIds = summaryRepository.getArticleIdsMissingTlcTags()
        if (articleIds.isEmpty()) return 0

        val model = llmService.getModelName(LlmFeature.TLC_TAGGING)
        if (onConfirmCost != null) {
            val estimate = costTracker.estimateTlcTaggingCost(articleIds.size, model)
            onProgress?.invoke(PipelineProgress("confirm", "Awaiting cost confirmation…", 0, articleIds.size))
            val approved = onConfirmCost(CostEstimate(articleIds.size, estimate, model)).await()
            if (!approved) return 0
        }

        val before = costTracker.getSnapshot()
        val semaphore = Semaphore(concurrency)
        val tagged = AtomicInteger(0)
        val counter = AtomicInteger(0)

        onProgress?.invoke(PipelineProgress("tag", "Tagging 0/${articleIds.size} articles…", 0, articleIds.size))
        coroutineScope {
            for (articleId in articleIds) {
                launch {
                    semaphore.acquire()
                    try {
                        if (tagOne(articleId)) tagged.incrementAndGet()
                        val count = counter.incrementAndGet()
                        onProgress?.invoke(PipelineProgress("tag", "Tagging $count/${articleIds.size} articles…", count, articleIds.size))
                    } finally {
                        semaphore.release()
                    }
                }
            }
        }

        val actualCost = costTracker.deltaCost(before, costTracker.getSnapshot(), model)
        onActualCost?.invoke(ActualCostInfo(tagged.get(), actualCost, model))
        onProgress?.invoke(PipelineProgress("done", "Done", articleIds.size, articleIds.size))
        return tagged.get()
    }

    private suspend fun tagOne(articleId: Long): Boolean {
        return try {
            val summaryText = summaryRepository.getSummaryText(articleId)
            if (summaryText.isNullOrBlank()) return false

            val resultJson = llmService.chatCompletion(
                feature = LlmFeature.TLC_TAGGING,
                systemPrompt = TLC_TAGGING_PROMPT,
                messages = listOf(ChatMessageDto("user", "<summary>\n$summaryText\n</summary>")),
                temperature = 0f,
                maxTokens = 500,
                jsonMode = true,
                cacheSystemPrompt = true
            ).content

            val result = taggingAdapter.fromJson(resultJson) ?: return false
            val validTlcTags = result.tlcTags.filter { it in TlcTaxonomy.allTags }

            val existingTagsJson = articleRepository.getArticleById(articleId)?.tags
            val existingTags = existingTagsJson?.let {
                try { tagsListAdapter.fromJson(it) } catch (_: Exception) { null }
            } ?: emptyList()

            val merged = (existingTags + validTlcTags.ifEmpty { listOf("tlc-unknown") }).distinct()
            summaryRepository.updateTags(articleId, merged)
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            appLogger.e(TAG, "Failed to backfill tlc tags for article $articleId: ${e.message}")
            false
        }
    }
}
