package com.threatloom.app.ui.discuss

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.threatloom.app.data.repository.DebateRepository
import com.threatloom.app.data.repository.QuizRepository
import com.threatloom.app.domain.model.ChatMessage
import com.threatloom.app.domain.service.CostTracker
import com.threatloom.app.domain.usecase.DiscussUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class DiscussViewModel @Inject constructor(
    private val discussUseCase: DiscussUseCase,
    private val quizRepository: QuizRepository,
    private val debateRepository: DebateRepository,
    private val costTracker: CostTracker
) : ViewModel() {

    private var articleId: Long = 0L
    private var initialized = false
    private var totalCost = 0.0
    private var latestModel: String? = null

    private val _debateTopic = MutableStateFlow<String?>(null)
    val debateTopic: StateFlow<String?> = _debateTopic.asStateFlow()

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    private val _input = MutableStateFlow("")
    val input: StateFlow<String> = _input.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _sessionCost = MutableStateFlow<Double?>(null)
    val sessionCost: StateFlow<Double?> = _sessionCost.asStateFlow()

    private val _concluded = MutableStateFlow(false)
    val concluded: StateFlow<Boolean> = _concluded.asStateFlow()

    fun init(id: Long) {
        if (initialized) return
        initialized = true
        articleId = id
        viewModelScope.launch {
            val saved = debateRepository.getByArticleId(id)
            if (saved != null && saved.messages.isNotEmpty()) {
                // Resume a previously saved debate.
                _debateTopic.value = saved.debateTopic
                _messages.value = saved.messages
                totalCost = saved.totalCost
                latestModel = saved.modelUsed
                _sessionCost.value = saved.totalCost
                _concluded.value = saved.concluded
                return@launch
            }
            val topic = quizRepository.getByArticleId(id)?.debateTopic
            _debateTopic.value = topic
            if (!topic.isNullOrBlank()) {
                openWithLlm(topic)
            }
        }
    }

    private suspend fun openWithLlm(topic: String) {
        _isLoading.value = true
        // Seed message is not shown in the UI — the LLM's opening reply is the first visible message
        val seedMessages = listOf(ChatMessage("user", "Let's discuss this topic: $topic\n\nPlease open the debate with your initial perspective in 2-3 sentences, then ask me what I think."))
        val before = costTracker.getSnapshot()
        val opening = discussUseCase(seedMessages, articleId, topic)
        accrueCost(before, opening.modelUsed)
        _messages.value = listOf(opening)
        if (opening.concluded) _concluded.value = true
        _isLoading.value = false
        persist()
    }

    fun onInputChanged(text: String) {
        _input.value = text
    }

    fun send() {
        val text = _input.value.trim()
        if (text.isBlank() || _isLoading.value || _concluded.value) return
        val topic = _debateTopic.value ?: return
        _input.value = ""
        val userMsg = ChatMessage("user", text)
        val history = _messages.value + userMsg
        _messages.value = history
        viewModelScope.launch {
            _isLoading.value = true
            val before = costTracker.getSnapshot()
            val response = discussUseCase(history, articleId, topic)
            accrueCost(before, response.modelUsed)
            _messages.value = _messages.value + response
            if (response.concluded) _concluded.value = true
            _isLoading.value = false
            persist()
        }
    }

    /** Manually ends the discussion without another LLM call. */
    fun endDebate() {
        if (_concluded.value) return
        _concluded.value = true
        viewModelScope.launch { persist() }
    }

    private fun accrueCost(before: com.threatloom.app.domain.service.CostSnapshot, model: String?) {
        val after = costTracker.getSnapshot()
        val resolvedModel = model ?: latestModel ?: ""
        totalCost += costTracker.deltaCost(before, after, resolvedModel)
        latestModel = resolvedModel.ifBlank { latestModel }
        _sessionCost.value = totalCost
    }

    private suspend fun persist() {
        if (_messages.value.isEmpty()) return
        debateRepository.save(
            articleId = articleId,
            debateTopic = _debateTopic.value,
            messages = _messages.value,
            totalCost = totalCost,
            modelUsed = latestModel,
            concluded = _concluded.value
        )
    }
}
