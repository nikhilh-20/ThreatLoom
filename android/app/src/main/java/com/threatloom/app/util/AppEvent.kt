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

    fun notifyDatabaseCleared() {
        _databaseCleared.tryEmit(Unit)
    }

    fun setPipelineRunning(running: Boolean) {
        _pipelineRunning.value = running
    }
}
