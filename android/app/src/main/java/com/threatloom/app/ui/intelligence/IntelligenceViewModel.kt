package com.threatloom.app.ui.intelligence

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.threatloom.app.data.preferences.SettingsDataStore
import com.threatloom.app.data.repository.EmbeddingRepository
import com.threatloom.app.data.repository.SummaryRepository
import com.threatloom.app.domain.model.ChatMessage
import com.threatloom.app.domain.service.ReportService
import com.threatloom.app.domain.usecase.IntelligenceChatUseCase
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
    private val settingsDataStore: SettingsDataStore
) : ViewModel() {

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _embeddingStatus = MutableStateFlow<String?>(null)
    val embeddingStatus: StateFlow<String?> = _embeddingStatus.asStateFlow()

    private val _reportStatus = MutableStateFlow<String?>(null)
    val reportStatus: StateFlow<String?> = _reportStatus.asStateFlow()

    init {
        viewModelScope.launch { loadEmbeddingStatus() }
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
            val response = intelligenceChatUseCase(history)
            _messages.value = _messages.value + response
            _isLoading.value = false
        }
    }

    fun useSuggestion(text: String) {
        _query.value = text
        sendMessage()
    }

    fun clearConversation() {
        _messages.value = emptyList()
        _query.value = ""
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
