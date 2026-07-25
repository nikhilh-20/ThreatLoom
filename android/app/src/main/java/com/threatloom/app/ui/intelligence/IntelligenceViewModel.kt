package com.threatloom.app.ui.intelligence

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.threatloom.app.data.preferences.SettingsDataStore
import com.threatloom.app.data.repository.EmbeddingRepository
import com.threatloom.app.data.repository.SavedIntelligenceChatRepository
import com.threatloom.app.data.repository.SavedIntelligenceChatSummary
import com.threatloom.app.data.repository.SummaryRepository
import com.threatloom.app.domain.model.ChatMessage
import com.threatloom.app.domain.model.ContextArticle
import com.threatloom.app.domain.service.ReportService
import com.threatloom.app.domain.usecase.IntelligenceChatUseCase
import com.threatloom.app.util.AppEvent
import com.threatloom.app.util.DateUtils
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class IntelligenceViewModel @Inject constructor(
    private val intelligenceChatUseCase: IntelligenceChatUseCase,
    private val embeddingRepository: EmbeddingRepository,
    private val summaryRepository: SummaryRepository,
    private val reportService: ReportService,
    private val settingsDataStore: SettingsDataStore,
    private val savedIntelligenceChatRepository: SavedIntelligenceChatRepository,
    private val appEvent: AppEvent
) : ViewModel() {

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    // Rolling retrieval context for the current conversation.
    private var contextArticles: List<ContextArticle> = emptyList()

    // Persistence state for the current conversation.
    private var conversationId: Long? = null
    private var latestModel: String? = null

    private val _isSaved = MutableStateFlow(false)
    val isSaved: StateFlow<Boolean> = _isSaved.asStateFlow()

    private val _hasUnsavedChanges = MutableStateFlow(false)
    val hasUnsavedChanges: StateFlow<Boolean> = _hasUnsavedChanges.asStateFlow()

    private val _savedChats = MutableStateFlow<List<SavedIntelligenceChatSummary>>(emptyList())
    val savedChats: StateFlow<List<SavedIntelligenceChatSummary>> = _savedChats.asStateFlow()

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _embeddingStatus = MutableStateFlow<String?>(null)
    val embeddingStatus: StateFlow<String?> = _embeddingStatus.asStateFlow()

    private val _reportStatus = MutableStateFlow<String?>(null)
    val reportStatus: StateFlow<String?> = _reportStatus.asStateFlow()

    private val _webSearchEnabled = MutableStateFlow(false)
    val webSearchEnabled: StateFlow<Boolean> = _webSearchEnabled.asStateFlow()

    fun setWebSearchEnabled(enabled: Boolean) {
        _webSearchEnabled.value = enabled
    }

    private val _loadingStage = MutableStateFlow("Thinking…")
    val loadingStage: StateFlow<String> = _loadingStage.asStateFlow()

    init {
        viewModelScope.launch { loadEmbeddingStatus() }
        // Honor requests from the app-wide Saved Chats tab to open a specific Intelligence chat.
        viewModelScope.launch {
            appEvent.resumeIntelligenceChatId.collect { id ->
                if (id != null) {
                    resume(id)
                    appEvent.consumeResumeIntelligenceChat()
                }
            }
        }
    }

    fun refreshEmbeddingStatus() {
        viewModelScope.launch { loadEmbeddingStatus() }
    }

    private suspend fun loadEmbeddingStatus() {
        val totalEmbedded = embeddingRepository.countAll()
        val totalSummarized = summaryRepository.countAll()
        _embeddingStatus.value = if (totalEmbedded == 0) {
            "No articles indexed yet. Run a refresh to generate embeddings for your articles."
        } else {
            "$totalEmbedded of $totalSummarized articles indexed for semantic search"
        }
    }

    fun updateQuery(text: String) {
        _query.value = text
    }

    fun sendMessage() {
        val text = _query.value.trim()
        if (text.isBlank() || _isLoading.value) return
        _query.value = ""
        val userMsg = ChatMessage("user", text)
        val history = _messages.value + userMsg
        _messages.value = history
        viewModelScope.launch {
            _isLoading.value = true
            val result = intelligenceChatUseCase(
                messages = history,
                priorContext = contextArticles,
                webSearchEnabled = _webSearchEnabled.value,
                onProgress = { stage -> _loadingStage.value = stage }
            )
            contextArticles = result.context
            _messages.value = _messages.value + result.message
            result.message.modelUsed?.let { latestModel = it }
            _hasUnsavedChanges.value = true
            _isLoading.value = false
        }
    }

    /** Persist the current conversation, updating the existing saved row in place after the first save. */
    fun save() {
        if (_messages.value.none { it.role == "user" }) return
        viewModelScope.launch {
            conversationId = savedIntelligenceChatRepository.save(
                id = conversationId,
                messages = _messages.value,
                context = contextArticles,
                totalCost = 0.0,
                modelUsed = latestModel
            )
            _isSaved.value = true
            _hasUnsavedChanges.value = false
        }
    }

    /** Refresh the saved-chats list; called when the saved-chats sheet opens. */
    fun refreshSavedChats() {
        viewModelScope.launch { _savedChats.value = savedIntelligenceChatRepository.getAll() }
    }

    /** Restore a saved conversation into the active view. */
    fun resume(id: Long) {
        viewModelScope.launch {
            val conversation = savedIntelligenceChatRepository.getById(id) ?: return@launch
            _messages.value = conversation.messages
            contextArticles = conversation.context
            latestModel = conversation.modelUsed
            conversationId = conversation.id
            _isSaved.value = true
            _hasUnsavedChanges.value = false
            _query.value = ""
        }
    }

    fun deleteSavedChat(id: Long) {
        viewModelScope.launch {
            savedIntelligenceChatRepository.delete(id)
            _savedChats.value = savedIntelligenceChatRepository.getAll()
            // If the open conversation was the one deleted, it is no longer persisted.
            if (conversationId == id) {
                conversationId = null
                _isSaved.value = false
                _hasUnsavedChanges.value = _messages.value.any { it.role == "user" }
            }
        }
    }

    fun useSuggestion(text: String) {
        _query.value = text
        sendMessage()
    }

    fun clearConversation() {
        _messages.value = emptyList()
        contextArticles = emptyList()
        _query.value = ""
        conversationId = null
        latestModel = null
        _isSaved.value = false
        _hasUnsavedChanges.value = false
    }

    fun sendMessageReport(messageIndex: Int, userNote: String) {
        viewModelScope.launch {
            val url = settingsDataStore.backendUrl.first()
            if (url.isBlank()) {
                _reportStatus.value = "Backend URL not configured in Settings"
                autoDismiss()
                return@launch
            }
            val msgs = _messages.value
            val assistant = msgs.getOrNull(messageIndex) ?: return@launch
            val query = msgs.getOrNull(messageIndex - 1)?.content ?: ""
            val citations = assistant.articles?.joinToString(", ") { it.title } ?: ""
            _reportStatus.value = "Sending…"
            val result = reportService.send(url, ReportService.ReportPayload(
                type = "Intelligence Response",
                identifier = query.take(80),
                llm_content = assistant.content,
                metadata = buildMap {
                    put("Query", query.take(200))
                    put("Model", assistant.modelUsed ?: "")
                    if (citations.isNotEmpty()) put("Citations", citations)
                    put("Reported", DateUtils.nowIso())
                },
                user_note = userNote,
                token = settingsDataStore.reportToken.first()
            ))
            _reportStatus.value = if (result.isSuccess) "Report sent" else "Failed: ${result.exceptionOrNull()?.message}"
            autoDismiss()
        }
    }

    private fun autoDismiss() {
        viewModelScope.launch { delay(3000); _reportStatus.value = null }
    }
}
