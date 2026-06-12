package com.threatloom.app.data.repository

import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import com.threatloom.app.data.local.dao.DebateDao
import com.threatloom.app.data.local.entity.DebateEntity
import com.threatloom.app.domain.model.ChatMessage
import javax.inject.Inject
import javax.inject.Singleton

/** Rehydrated debate state restored from persistence. */
data class SavedDebate(
    val debateTopic: String?,
    val messages: List<ChatMessage>,
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

@Singleton
class DebateRepository @Inject constructor(
    private val debateDao: DebateDao
) {
    private val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
    private val listType = Types.newParameterizedType(List::class.java, DebateMessageDto::class.java)
    private val adapter = moshi.adapter<List<DebateMessageDto>>(listType)

    suspend fun getByArticleId(articleId: Long): SavedDebate? {
        val entity = debateDao.getByArticleId(articleId) ?: return null
        val dtos = entity.messages?.let { runCatching { adapter.fromJson(it) }.getOrNull() } ?: emptyList()
        val messages = dtos.map {
            ChatMessage(role = it.role, content = it.content, modelUsed = it.modelUsed, concluded = it.concluded)
        }
        return SavedDebate(
            debateTopic = entity.debateTopic,
            messages = messages,
            totalCost = entity.totalCost,
            modelUsed = entity.modelUsed,
            concluded = entity.concluded
        )
    }

    suspend fun exists(articleId: Long): Boolean = debateDao.getByArticleId(articleId) != null

    suspend fun save(
        articleId: Long,
        debateTopic: String?,
        messages: List<ChatMessage>,
        totalCost: Double,
        modelUsed: String?,
        concluded: Boolean
    ) {
        val dtos = messages.map {
            DebateMessageDto(role = it.role, content = it.content, modelUsed = it.modelUsed, concluded = it.concluded)
        }
        debateDao.upsert(
            DebateEntity(
                articleId = articleId,
                debateTopic = debateTopic,
                messages = adapter.toJson(dtos),
                totalCost = totalCost,
                modelUsed = modelUsed,
                concluded = concluded
            )
        )
    }

    suspend fun delete(articleId: Long) = debateDao.deleteByArticleId(articleId)
}
