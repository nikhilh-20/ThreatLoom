package com.threatloom.app.util

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Simple app-wide event bus for cross-screen communication.
 */
@Singleton
class AppEvent @Inject constructor() {

    private val _databaseCleared = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val databaseCleared = _databaseCleared.asSharedFlow()

    private val _pipelineRunning = MutableStateFlow(false)
    val pipelineRunning: StateFlow<Boolean> = _pipelineRunning.asStateFlow()

    // Id of a saved Intelligence chat the app-wide Saved Chats tab asked the Intelligence tab to open.
    // Held (not one-shot) so a freshly-created IntelligenceViewModel still catches it after the tab switch.
    private val _resumeIntelligenceChatId = MutableStateFlow<Long?>(null)
    val resumeIntelligenceChatId: StateFlow<Long?> = _resumeIntelligenceChatId.asStateFlow()

    fun notifyDatabaseCleared() {
        _databaseCleared.tryEmit(Unit)
    }

    fun setPipelineRunning(running: Boolean) {
        _pipelineRunning.value = running
    }

    fun requestResumeIntelligenceChat(id: Long) {
        _resumeIntelligenceChatId.value = id
    }

    fun consumeResumeIntelligenceChat() {
        _resumeIntelligenceChatId.value = null
    }
}
