package com.threatloom.app.data.repository

import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import com.threatloom.app.data.local.dao.DebateDao
import com.threatloom.app.data.local.entity.DebateEntity
import com.threatloom.app.domain.model.ChatMessage
import com.threatloom.app.domain.model.ContextArticle
import com.threatloom.app.domain.model.SummarySection
import javax.inject.Inject
import javax.inject.Singleton

/** Lightweight row for the app-wide "Saved Chats" list; keyed by articleId (one debate per article). */
data class GlobalDebateSummary(
    val articleId: Long,
    val debateTopic: String?,
    val createdDate: String?,
    val totalCost: Double,
    val modelUsed: String?
)

/** Rehydrated debate state restored from persistence. */
data class SavedDebate(
    val debateTopic: String?,
    val messages: List<ChatMessage>,
    val context: List<ContextArticle>,
    val totalCost: Double,
    val modelUsed: String?,
    val concluded: Boolean
)

@JsonClass(generateAdapter = true)
data class DebateMessageDto(
    val role: String,
    val content: String,
    val modelUsed: String? = null,
    val concluded: Boolean = false
)

/** Persisted form of one rolling-context entry: article id + which summary sections were injected. */
@JsonClass(generateAdapter = true)
data class DebateContextArticleDto(
    val articleId: Long,
    val sections: List<String>
)

@Singleton
class DebateRepository @Inject constructor(
    private val debateDao: DebateDao,
    private val articleRepository: ArticleRepository
) {
    private val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
    private val listType = Types.newParameterizedType(List::class.java, DebateMessageDto::class.java)
    private val adapter = moshi.adapter<List<DebateMessageDto>>(listType)
    private val contextListType = Types.newParameterizedType(List::class.java, DebateContextArticleDto::class.java)
    private val contextAdapter = moshi.adapter<List<DebateContextArticleDto>>(contextListType)

    suspend fun getByArticleId(articleId: Long): SavedDebate? {
        val entity = debateDao.getByArticleId(articleId) ?: return null
        val dtos = entity.messages?.let { runCatching { adapter.fromJson(it) }.getOrNull() } ?: emptyList()
        val messages = dtos.map {
            ChatMessage(role = it.role, content = it.content, modelUsed = it.modelUsed, concluded = it.concluded)
        }
        return SavedDebate(
            debateTopic = entity.debateTopic,
            messages = messages,
            context = rehydrateContext(entity.contextArticles),
            totalCost = entity.totalCost,
            modelUsed = entity.modelUsed,
            concluded = entity.concluded
        )
    }

    /** Rebuild the rolling context working set from persisted (articleId, sections), preserving order. */
    private suspend fun rehydrateContext(json: String?): List<ContextArticle> {
        val dtos = json?.let { runCatching { contextAdapter.fromJson(it) }.getOrNull() } ?: return emptyList()
        if (dtos.isEmpty()) return emptyList()
        val articlesById = articleRepository.getArticlesByIds(dtos.map { it.articleId }).associateBy { it.id }
        return dtos.mapNotNull { dto ->
            val article = articlesById[dto.articleId] ?: return@mapNotNull null
            ContextArticle(article, dto.sections.mapNotNull { SummarySection.fromToken(it) })
        }
    }

    /** Every saved debate across all articles, newest first, for the app-wide Saved Chats list. */
    suspend fun getAllGlobal(): List<GlobalDebateSummary> {
        return debateDao.getAll().map {
            GlobalDebateSummary(
                articleId = it.articleId,
                debateTopic = it.debateTopic,
                createdDate = it.createdDate,
                totalCost = it.totalCost,
                modelUsed = it.modelUsed
            )
        }
    }

    suspend fun exists(articleId: Long): Boolean = debateDao.getByArticleId(articleId) != null

    suspend fun save(
        articleId: Long,
        debateTopic: String?,
        messages: List<ChatMessage>,
        context: List<ContextArticle>,
        totalCost: Double,
        modelUsed: String?,
        concluded: Boolean
    ) {
        val dtos = messages.map {
            DebateMessageDto(role = it.role, content = it.content, modelUsed = it.modelUsed, concluded = it.concluded)
        }
        val contextJson = contextAdapter.toJson(
            context.map { DebateContextArticleDto(it.article.id, it.sections.map { s -> s.token }) }
        )
        debateDao.upsert(
            DebateEntity(
                articleId = articleId,
                debateTopic = debateTopic,
                messages = adapter.toJson(dtos),
                contextArticles = contextJson,
                totalCost = totalCost,
                modelUsed = modelUsed,
                concluded = concluded
            )
        )
    }

    suspend fun delete(articleId: Long) = debateDao.deleteByArticleId(articleId)
}
