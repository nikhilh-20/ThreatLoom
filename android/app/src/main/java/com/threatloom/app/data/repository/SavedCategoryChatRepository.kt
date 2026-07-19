package com.threatloom.app.data.repository

import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import com.threatloom.app.data.local.dao.SavedCategoryChatDao
import com.threatloom.app.data.local.entity.SavedCategoryChatEntity
import com.threatloom.app.domain.model.ChatMessage
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

/** Rehydrated chat conversation restored from persistence. */
data class SavedCategoryChatConversation(
    val id: Long,
    val title: String?,
    val messages: List<ChatMessage>,
    val totalCost: Double,
    val modelUsed: String?
)

@JsonClass(generateAdapter = true)
data class SavedCategoryChatMessageDto(
    val role: String,
    val content: String,
    val modelUsed: String? = null
)

@Singleton
class SavedCategoryChatRepository @Inject constructor(
    private val savedCategoryChatDao: SavedCategoryChatDao
) {
    private val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
    private val listType = Types.newParameterizedType(List::class.java, SavedCategoryChatMessageDto::class.java)
    private val adapter = moshi.adapter<List<SavedCategoryChatMessageDto>>(listType)

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

    suspend fun getById(id: Long): SavedCategoryChatConversation? {
        val entity = savedCategoryChatDao.getById(id) ?: return null
        val dtos = entity.messages?.let { runCatching { adapter.fromJson(it) }.getOrNull() } ?: emptyList()
        val messages = dtos.map { ChatMessage(role = it.role, content = it.content, modelUsed = it.modelUsed) }
        return SavedCategoryChatConversation(
            id = entity.id,
            title = entity.title,
            messages = messages,
            totalCost = entity.totalCost,
            modelUsed = entity.modelUsed
        )
    }

    suspend fun save(
        id: Long?,
        categoryName: String,
        messages: List<ChatMessage>,
        totalCost: Double,
        modelUsed: String?
    ): Long {
        val dtos = messages.map { SavedCategoryChatMessageDto(role = it.role, content = it.content, modelUsed = it.modelUsed) }
        val messagesJson = adapter.toJson(dtos)

        if (id == null) {
            return savedCategoryChatDao.upsert(
                SavedCategoryChatEntity(
                    categoryName = categoryName,
                    title = generateTitle(messages),
                    messages = messagesJson,
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
