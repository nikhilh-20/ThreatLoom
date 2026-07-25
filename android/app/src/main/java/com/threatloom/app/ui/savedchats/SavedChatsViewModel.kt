package com.threatloom.app.ui.savedchats

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.threatloom.app.data.repository.ArticleRepository
import com.threatloom.app.data.repository.DebateRepository
import com.threatloom.app.data.repository.SavedCategoryChatRepository
import com.threatloom.app.data.repository.SavedChatRepository
import com.threatloom.app.data.repository.SavedIntelligenceChatRepository
import com.threatloom.app.util.AppEvent
import com.threatloom.app.util.DateUtils
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Which feature a saved conversation came from. */
enum class SavedChatKind { ARTICLE, CATEGORY, INTELLIGENCE, DEBATE }

/** One row in the app-wide Saved Chats list, normalized across all four conversation stores. */
data class SavedChatListItem(
    val kind: SavedChatKind,
    /** Conversation id for article/category/intelligence chats; the article id for a debate. */
    val conversationId: Long,
    val title: String,
    /** Context line: article title, category name, or the Intelligence feed. */
    val subtitle: String?,
    val date: String?,
    val modelUsed: String?,
    val articleId: Long? = null,
    val categoryName: String? = null
)

@HiltViewModel
class SavedChatsViewModel @Inject constructor(
    private val savedChatRepository: SavedChatRepository,
    private val savedCategoryChatRepository: SavedCategoryChatRepository,
    private val savedIntelligenceChatRepository: SavedIntelligenceChatRepository,
    private val debateRepository: DebateRepository,
    private val articleRepository: ArticleRepository,
    private val appEvent: AppEvent
) : ViewModel() {

    private val _items = MutableStateFlow<List<SavedChatListItem>>(emptyList())
    val items: StateFlow<List<SavedChatListItem>> = _items.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    init {
        refresh()
    }

    /** Reload every saved conversation across all features and merge them into one newest-first list. */
    fun refresh() {
        viewModelScope.launch {
            _isLoading.value = true

            val articleChats = savedChatRepository.getAllGlobal()
            val categoryChats = savedCategoryChatRepository.getAllGlobal()
            val intelligenceChats = savedIntelligenceChatRepository.getAll()
            val debates = debateRepository.getAllGlobal()

            // Batch-resolve titles for every article referenced by an article chat or a debate.
            val articleIds = (articleChats.map { it.articleId } + debates.map { it.articleId }).distinct()
            val titlesById = if (articleIds.isEmpty()) {
                emptyMap()
            } else {
                articleRepository.getArticlesByIds(articleIds).associate { it.id to it.title }
            }

            val merged = buildList {
                articleChats.forEach {
                    add(
                        SavedChatListItem(
                            kind = SavedChatKind.ARTICLE,
                            conversationId = it.id,
                            title = it.title ?: "Chat",
                            subtitle = titlesById[it.articleId],
                            date = it.updatedDate,
                            modelUsed = it.modelUsed,
                            articleId = it.articleId
                        )
                    )
                }
                categoryChats.forEach {
                    add(
                        SavedChatListItem(
                            kind = SavedChatKind.CATEGORY,
                            conversationId = it.id,
                            title = it.title ?: "Chat",
                            subtitle = it.categoryName,
                            date = it.updatedDate,
                            modelUsed = it.modelUsed,
                            categoryName = it.categoryName
                        )
                    )
                }
                intelligenceChats.forEach {
                    add(
                        SavedChatListItem(
                            kind = SavedChatKind.INTELLIGENCE,
                            conversationId = it.id,
                            title = it.title ?: "Chat",
                            subtitle = "Intelligence",
                            date = it.updatedDate,
                            modelUsed = it.modelUsed
                        )
                    )
                }
                debates.forEach {
                    val articleTitle = titlesById[it.articleId]
                    add(
                        SavedChatListItem(
                            kind = SavedChatKind.DEBATE,
                            conversationId = it.articleId,
                            title = it.debateTopic ?: articleTitle ?: "Debate",
                            subtitle = articleTitle,
                            date = it.createdDate,
                            modelUsed = it.modelUsed,
                            articleId = it.articleId
                        )
                    )
                }
            }.sortedByDescending { DateUtils.parseIso(it.date)?.time ?: Long.MIN_VALUE }

            _items.value = merged
            _isLoading.value = false
        }
    }

    /** Queue a saved Intelligence chat for the Intelligence tab to open once it becomes active. */
    fun requestResumeIntelligence(id: Long) {
        appEvent.requestResumeIntelligenceChat(id)
    }
}
