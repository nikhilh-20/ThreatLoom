package com.threatloom.app.data.repository

import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import com.threatloom.app.data.local.dao.SavedIntelligenceChatDao
import com.threatloom.app.data.local.entity.SavedIntelligenceChatEntity
import com.threatloom.app.domain.model.ChatMessage
import com.threatloom.app.domain.model.ContextArticle
import com.threatloom.app.domain.model.SummarySection
import com.threatloom.app.util.DateUtils
import javax.inject.Inject
import javax.inject.Singleton

/** Lightweight row for listing saved Intelligence chats, without parsing message JSON. */
data class SavedIntelligenceChatSummary(
    val id: Long,
    val title: String?,
    val updatedDate: String?,
    val totalCost: Double,
    val modelUsed: String?
)

/** Rehydrated Intelligence chat conversation restored from persistence. */
data class SavedIntelligenceChatConversation(
    val id: Long,
    val title: String?,
    val messages: List<ChatMessage>,
    val context: List<ContextArticle>,
    val totalCost: Double,
    val modelUsed: String?
)

@JsonClass(generateAdapter = true)
data class SavedIntelligenceChatMessageDto(
    val role: String,
    val content: String,
    val modelUsed: String? = null,
    /** Ids of the articles cited by this (assistant) message, so citation cards survive a resume. */
    val citedArticleIds: List<Long> = emptyList(),
    val isTruncated: Boolean = false
)

/** Persisted form of one rolling-context entry: article id + which summary sections were injected. */
@JsonClass(generateAdapter = true)
data class SavedIntelligenceContextArticleDto(
    val articleId: Long,
    val sections: List<String>
)

@Singleton
class SavedIntelligenceChatRepository @Inject constructor(
    private val savedIntelligenceChatDao: SavedIntelligenceChatDao,
    private val articleRepository: ArticleRepository
) {
    private val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
    private val listType = Types.newParameterizedType(List::class.java, SavedIntelligenceChatMessageDto::class.java)
    private val adapter = moshi.adapter<List<SavedIntelligenceChatMessageDto>>(listType)
    private val contextListType = Types.newParameterizedType(List::class.java, SavedIntelligenceContextArticleDto::class.java)
    private val contextAdapter = moshi.adapter<List<SavedIntelligenceContextArticleDto>>(contextListType)

    suspend fun getAll(): List<SavedIntelligenceChatSummary> {
        return savedIntelligenceChatDao.getAll().map {
            SavedIntelligenceChatSummary(
                id = it.id,
                title = it.title,
                updatedDate = it.updatedDate,
                totalCost = it.totalCost,
                modelUsed = it.modelUsed
            )
        }
    }

    suspend fun getById(id: Long): SavedIntelligenceChatConversation? {
        val entity = savedIntelligenceChatDao.getById(id) ?: return null
        val dtos = entity.messages?.let { runCatching { adapter.fromJson(it) }.getOrNull() } ?: emptyList()

        // Batch-fetch every cited article across all messages in one query, then attach per message.
        val citedIds = dtos.flatMap { it.citedArticleIds }.distinct()
        val articlesById = if (citedIds.isEmpty()) {
            emptyMap()
        } else {
            articleRepository.getArticlesByIds(citedIds).associateBy { it.id }
        }
        val messages = dtos.map { dto ->
            val articles = dto.citedArticleIds.mapNotNull { articlesById[it] }
            ChatMessage(
                role = dto.role,
                content = dto.content,
                articles = articles.ifEmpty { null },
                modelUsed = dto.modelUsed,
                isTruncated = dto.isTruncated
            )
        }

        return SavedIntelligenceChatConversation(
            id = entity.id,
            title = entity.title,
            messages = messages,
            context = rehydrateContext(entity.contextArticles),
            totalCost = entity.totalCost,
            modelUsed = entity.modelUsed
        )
    }

    /** Rebuild the rolling context working set from persisted (articleId, sections), preserving order. */
    private suspend fun rehydrateContext(json: String?): List<ContextArticle> {
        val dtos = json?.let { runCatching { contextAdapter.fromJson(it) }.getOrNull() } ?: return emptyList()
        if (dtos.isEmpty()) return emptyList()
        val articlesById = articleRepository.getArticlesByIds(dtos.map { it.articleId }).associateBy { it.id }
        return dtos.mapNotNull { dto ->
            val article = articlesById[dto.articleId] ?: return@mapNotNull null
            val sections = dto.sections.mapNotNull { SummarySection.fromToken(it) }
            ContextArticle(article, sections)
        }
    }

    suspend fun save(
        id: Long?,
        messages: List<ChatMessage>,
        context: List<ContextArticle>,
        totalCost: Double,
        modelUsed: String?
    ): Long {
        val dtos = messages.map {
            SavedIntelligenceChatMessageDto(
                role = it.role,
                content = it.content,
                modelUsed = it.modelUsed,
                citedArticleIds = it.articles?.map { a -> a.id } ?: emptyList(),
                isTruncated = it.isTruncated
            )
        }
        val messagesJson = adapter.toJson(dtos)
        val contextJson = contextAdapter.toJson(
            context.map { SavedIntelligenceContextArticleDto(it.article.id, it.sections.map { s -> s.token }) }
        )

        if (id == null) {
            return savedIntelligenceChatDao.upsert(
                SavedIntelligenceChatEntity(
                    title = generateTitle(messages),
                    messages = messagesJson,
                    contextArticles = contextJson,
                    totalCost = totalCost,
                    modelUsed = modelUsed,
                    createdDate = DateUtils.nowIso(),
                    updatedDate = DateUtils.nowIso()
                )
            )
        }

        val existing = savedIntelligenceChatDao.getById(id)
        return savedIntelligenceChatDao.upsert(
            SavedIntelligenceChatEntity(
                id = id,
                title = existing?.title ?: generateTitle(messages),
                messages = messagesJson,
                contextArticles = contextJson,
                totalCost = totalCost,
                modelUsed = modelUsed,
                createdDate = existing?.createdDate,
                updatedDate = DateUtils.nowIso()
            )
        )
    }

    suspend fun delete(id: Long) = savedIntelligenceChatDao.deleteById(id)

    private fun generateTitle(messages: List<ChatMessage>): String {
        val firstUserMessage = messages.firstOrNull { it.role == "user" }?.content?.trim()
        if (firstUserMessage.isNullOrBlank()) return "Chat"
        return if (firstUserMessage.length > 60) firstUserMessage.take(60) + "…" else firstUserMessage
    }
}
