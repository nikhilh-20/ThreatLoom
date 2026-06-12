package com.threatloom.app.ui.logs

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.threatloom.app.util.AppLogger
import com.threatloom.app.util.LogEntry
import com.threatloom.app.util.LogLevel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@HiltViewModel
class LogViewerViewModel @Inject constructor(
    private val appLogger: AppLogger
) : ViewModel() {

    private val _levelFilter = MutableStateFlow<LogLevel?>(null)
    val levelFilter: StateFlow<LogLevel?> = _levelFilter.asStateFlow()

    val filteredEntries: StateFlow<List<LogEntry>> = combine(
        appLogger.entries, _levelFilter
    ) { entries, level ->
        if (level == null) entries else entries.filter { it.level == level }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    fun setLevelFilter(level: LogLevel?) { _levelFilter.value = level }

    fun clearLogs() { appLogger.clear() }
}
