package com.threatloom.app.data.repository

import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import com.threatloom.app.data.local.dao.SavedCategoryChatDao
import com.threatloom.app.data.local.entity.SavedCategoryChatEntity
import com.threatloom.app.domain.model.ChatMessage
import com.threatloom.app.domain.model.ContextArticle
import com.threatloom.app.domain.model.SummarySection
import com.threatloom.app.util.DateUtils
import javax.inject.Inject
import javax.inject.Singleton

/** Lightweight row for listing saved chats on the category page, without parsing message JSON. */
data class SavedCategoryChatSummary(
    val id: Long,
    val title: String?,
    val updatedDate: String?,
    val totalCost: Double,
    val modelUsed: String?
)

/** Lightweight row for the app-wide "Saved Chats" list; carries categoryName so the chat can be reopened. */
data class GlobalCategoryChatSummary(
    val id: Long,
    val categoryName: String,
    val title: String?,
    val updatedDate: String?,
    val totalCost: Double,
    val modelUsed: String?
)

/** Rehydrated chat conversation restored from persistence. */
data class SavedCategoryChatConversation(
    val id: Long,
    val title: String?,
    val messages: List<ChatMessage>,
    val context: List<ContextArticle>,
    val totalCost: Double,
    val modelUsed: String?
)

@JsonClass(generateAdapter = true)
data class SavedCategoryChatMessageDto(
    val role: String,
    val content: String,
    val modelUsed: String? = null,
    /** Ids of the articles cited by this (assistant) message, so citation cards survive a resume. */
    val citedArticleIds: List<Long> = emptyList(),
    val isTruncated: Boolean = false
)

/** Persisted form of one rolling-context entry: article id + which summary sections were injected. */
@JsonClass(generateAdapter = true)
data class SavedContextArticleDto(
    val articleId: Long,
    val sections: List<String>
)

@Singleton
class SavedCategoryChatRepository @Inject constructor(
    private val savedCategoryChatDao: SavedCategoryChatDao,
    private val articleRepository: ArticleRepository
) {
    private val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
    private val listType = Types.newParameterizedType(List::class.java, SavedCategoryChatMessageDto::class.java)
    private val adapter = moshi.adapter<List<SavedCategoryChatMessageDto>>(listType)
    private val contextListType = Types.newParameterizedType(List::class.java, SavedContextArticleDto::class.java)
    private val contextAdapter = moshi.adapter<List<SavedContextArticleDto>>(contextListType)

    suspend fun getAllByCategory(categoryName: String): List<SavedCategoryChatSummary> {
        return savedCategoryChatDao.getAllByCategory(categoryName).map {
            SavedCategoryChatSummary(
                id = it.id,
                title = it.title,
                updatedDate = it.updatedDate,
                totalCost = it.totalCost,
                modelUsed = it.modelUsed
            )
        }
    }

    /** Every saved category chat across all categories, newest first, for the app-wide Saved Chats list. */
    suspend fun getAllGlobal(): List<GlobalCategoryChatSummary> {
        return savedCategoryChatDao.getAll().map {
            GlobalCategoryChatSummary(
                id = it.id,
                categoryName = it.categoryName,
                title = it.title,
                updatedDate = it.updatedDate,
                totalCost = it.totalCost,
                modelUsed = it.modelUsed
            )
        }
    }

    suspend fun getById(id: Long): SavedCategoryChatConversation? {
        val entity = savedCategoryChatDao.getById(id) ?: return null
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
        return SavedCategoryChatConversation(
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
        categoryName: String,
        messages: List<ChatMessage>,
        context: List<ContextArticle>,
        totalCost: Double,
        modelUsed: String?
    ): Long {
        val dtos = messages.map {
            SavedCategoryChatMessageDto(
                role = it.role,
                content = it.content,
                modelUsed = it.modelUsed,
                citedArticleIds = it.articles?.map { a -> a.id } ?: emptyList(),
                isTruncated = it.isTruncated
            )
        }
        val messagesJson = adapter.toJson(dtos)
        val contextJson = contextAdapter.toJson(
            context.map { SavedContextArticleDto(it.article.id, it.sections.map { s -> s.token }) }
        )

        if (id == null) {
            return savedCategoryChatDao.upsert(
                SavedCategoryChatEntity(
                    categoryName = categoryName,
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

        val existing = savedCategoryChatDao.getById(id)
        return savedCategoryChatDao.upsert(
            SavedCategoryChatEntity(
                id = id,
                categoryName = categoryName,
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

    suspend fun delete(id: Long) = savedCategoryChatDao.deleteById(id)

    private fun generateTitle(messages: List<ChatMessage>): String {
        val firstUserMessage = messages.firstOrNull { it.role == "user" }?.content?.trim()
        if (firstUserMessage.isNullOrBlank()) return "Chat"
        return if (firstUserMessage.length > 60) firstUserMessage.take(60) + "…" else firstUserMessage
    }
}
