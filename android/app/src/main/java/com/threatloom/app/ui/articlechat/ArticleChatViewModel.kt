package com.threatloom.app.ui.articlechat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.threatloom.app.data.repository.SavedChatRepository
import com.threatloom.app.domain.model.ChatMessage
import com.threatloom.app.domain.service.CostTracker
import com.threatloom.app.domain.usecase.ArticleChatUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ArticleChatViewModel @Inject constructor(
    private val articleChatUseCase: ArticleChatUseCase,
    private val costTracker: CostTracker,
    private val savedChatRepository: SavedChatRepository
) : ViewModel() {

    private var articleId: Long = 0L
    private var initialized = false
    private var conversationId: Long? = null
    private var totalCost = 0.0
    private var latestModel: String? = null

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    private val _input = MutableStateFlow("")
    val input: StateFlow<String> = _input.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _sessionCost = MutableStateFlow<Double?>(null)
    val sessionCost: StateFlow<Double?> = _sessionCost.asStateFlow()

    private val _sessionModel = MutableStateFlow<String?>(null)
    val sessionModel: StateFlow<String?> = _sessionModel.asStateFlow()

    private val _isSaved = MutableStateFlow(false)
    val isSaved: StateFlow<Boolean> = _isSaved.asStateFlow()

    private val _hasUnsavedChanges = MutableStateFlow(false)
    val hasUnsavedChanges: StateFlow<Boolean> = _hasUnsavedChanges.asStateFlow()

    fun init(id: Long, resumeConversationId: Long?) {
        if (initialized) return
        initialized = true
        articleId = id
        if (resumeConversationId != null) {
            viewModelScope.launch {
                val saved = savedChatRepository.getById(resumeConversationId)
                if (saved != null) {
                    conversationId = saved.id
                    _messages.value = saved.messages
                    totalCost = saved.totalCost
                    latestModel = saved.modelUsed
                    _sessionCost.value = saved.totalCost
                    _sessionModel.value = saved.modelUsed
                    _isSaved.value = true
                    _hasUnsavedChanges.value = false
                } else {
                    startFresh()
                }
            }
        } else {
            startFresh()
        }
    }

    private fun startFresh() {
        conversationId = null
        _messages.value = listOf(ChatMessage("assistant", "Ask me anything about this article."))
        _isSaved.value = false
        _hasUnsavedChanges.value = false
    }

    fun onInputChanged(text: String) {
        _input.value = text
    }

    fun send() {
        val text = _input.value.trim()
        if (text.isBlank() || _isLoading.value) return
        _input.value = ""
        val userMsg = ChatMessage("user", text)
        val history = _messages.value.filter { it.role != "assistant" || it.content != "Ask me anything about this article." } + userMsg
        _messages.value = _messages.value + userMsg
        viewModelScope.launch {
            _isLoading.value = true
            val before = costTracker.getSnapshot()
            val response = articleChatUseCase(history, articleId)
            val after = costTracker.getSnapshot()
            val model = response.modelUsed ?: latestModel ?: ""
            totalCost += costTracker.deltaCost(before, after, model)
            latestModel = model.ifBlank { latestModel }
            _sessionCost.value = totalCost
            _sessionModel.value = latestModel
            _messages.value = _messages.value + response
            _isLoading.value = false
            _hasUnsavedChanges.value = true
        }
    }

    fun save() {
        if (_messages.value.none { it.role == "user" }) return
        viewModelScope.launch {
            val newId = savedChatRepository.save(
                id = conversationId,
                articleId = articleId,
                messages = _messages.value,
                totalCost = totalCost,
                modelUsed = latestModel
            )
            conversationId = newId
            _isSaved.value = true
            _hasUnsavedChanges.value = false
        }
    }
}
