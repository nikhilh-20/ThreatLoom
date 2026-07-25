package com.threatloom.app.data.repository

import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import com.threatloom.app.data.local.dao.SavedChatDao
import com.threatloom.app.data.local.entity.SavedChatEntity
import com.threatloom.app.domain.model.ChatMessage
import com.threatloom.app.util.DateUtils
import javax.inject.Inject
import javax.inject.Singleton

/** Lightweight row for listing saved chats on the article page, without parsing message JSON. */
data class SavedChatSummary(
    val id: Long,
    val title: String?,
    val updatedDate: String?,
    val totalCost: Double,
    val modelUsed: String?
)

/** Lightweight row for the app-wide "Saved Chats" list; carries articleId so the chat can be reopened. */
data class GlobalArticleChatSummary(
    val id: Long,
    val articleId: Long,
    val title: String?,
    val updatedDate: String?,
    val modelUsed: String?
)

/** Rehydrated chat conversation restored from persistence. */
data class SavedChatConversation(
    val id: Long,
    val title: String?,
    val messages: List<ChatMessage>,
    val totalCost: Double,
    val modelUsed: String?
)

@JsonClass(generateAdapter = true)
data class SavedChatMessageDto(
    val role: String,
    val content: String,
    val modelUsed: String? = null
)

@Singleton
class SavedChatRepository @Inject constructor(
    private val savedChatDao: SavedChatDao
) {
    private val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
    private val listType = Types.newParameterizedType(List::class.java, SavedChatMessageDto::class.java)
    private val adapter = moshi.adapter<List<SavedChatMessageDto>>(listType)

    suspend fun getAllByArticleId(articleId: Long): List<SavedChatSummary> {
        return savedChatDao.getAllByArticleId(articleId).map {
            SavedChatSummary(
                id = it.id,
                title = it.title,
                updatedDate = it.updatedDate,
                totalCost = it.totalCost,
                modelUsed = it.modelUsed
            )
        }
    }

    /** Every saved article chat across all articles, newest first, for the app-wide Saved Chats list. */
    suspend fun getAllGlobal(): List<GlobalArticleChatSummary> {
        return savedChatDao.getAll().map {
            GlobalArticleChatSummary(
                id = it.id,
                articleId = it.articleId,
                title = it.title,
                updatedDate = it.updatedDate,
                modelUsed = it.modelUsed
            )
        }
    }

    suspend fun getById(id: Long): SavedChatConversation? {
        val entity = savedChatDao.getById(id) ?: return null
        val dtos = entity.messages?.let { runCatching { adapter.fromJson(it) }.getOrNull() } ?: emptyList()
        val messages = dtos.map { ChatMessage(role = it.role, content = it.content, modelUsed = it.modelUsed) }
        return SavedChatConversation(
            id = entity.id,
            title = entity.title,
            messages = messages,
            totalCost = entity.totalCost,
            modelUsed = entity.modelUsed
        )
    }

    suspend fun save(
        id: Long?,
        articleId: Long,
        messages: List<ChatMessage>,
        totalCost: Double,
        modelUsed: String?
    ): Long {
        val dtos = messages.map { SavedChatMessageDto(role = it.role, content = it.content, modelUsed = it.modelUsed) }
        val messagesJson = adapter.toJson(dtos)

        if (id == null) {
            return savedChatDao.upsert(
                SavedChatEntity(
                    articleId = articleId,
                    title = generateTitle(messages),
                    messages = messagesJson,
                    totalCost = totalCost,
                    modelUsed = modelUsed,
                    createdDate = DateUtils.nowIso(),
                    updatedDate = DateUtils.nowIso()
                )
            )
        }

        val existing = savedChatDao.getById(id)
        return savedChatDao.upsert(
            SavedChatEntity(
                id = id,
                articleId = articleId,
                title = existing?.title ?: generateTitle(messages),
                messages = messagesJson,
                totalCost = totalCost,
                modelUsed = modelUsed,
                createdDate = existing?.createdDate,
                updatedDate = DateUtils.nowIso()
            )
        )
    }

    suspend fun delete(id: Long) = savedChatDao.deleteById(id)

    private fun generateTitle(messages: List<ChatMessage>): String {
        val firstUserMessage = messages.firstOrNull { it.role == "user" }?.content?.trim()
        if (firstUserMessage.isNullOrBlank()) return "Chat"
        return if (firstUserMessage.length > 60) firstUserMessage.take(60) + "…" else firstUserMessage
    }
}
