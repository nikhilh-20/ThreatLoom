package com.threatloom.app.ui.settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.threatloom.app.data.local.ThreatLoomDatabase
import com.threatloom.app.data.preferences.SettingsDataStore
import com.threatloom.app.data.repository.ArticleRepository
import com.threatloom.app.data.repository.SourceRepository
import com.threatloom.app.domain.model.Source
import com.threatloom.app.util.AppEvent
import com.threatloom.app.util.DateUtils
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val application: Application,
    private val settingsDataStore: SettingsDataStore,
    private val sourceRepository: SourceRepository,
    private val articleRepository: ArticleRepository,
    private val database: ThreatLoomDatabase,
    private val appEvent: AppEvent
) : AndroidViewModel(application) {

    val openaiApiKey = settingsDataStore.openaiApiKey.stateIn(viewModelScope, SharingStarted.Lazily, "")
    val malpediaApiKey = settingsDataStore.malpediaApiKey.stateIn(viewModelScope, SharingStarted.Lazily, "")
    val openaiModel = settingsDataStore.openaiModel.stateIn(viewModelScope, SharingStarted.Lazily, "gpt-5.4-nano")
    val lookbackDays = settingsDataStore.lookbackDays.stateIn(viewModelScope, SharingStarted.Lazily, 1)
    val parallelRequests = settingsDataStore.parallelRequests.stateIn(viewModelScope, SharingStarted.Lazily, 5)
    val llmProvider = settingsDataStore.llmProvider.stateIn(viewModelScope, SharingStarted.Lazily, "openai")
    val anthropicApiKey = settingsDataStore.anthropicApiKey.stateIn(viewModelScope, SharingStarted.Lazily, "")
    val anthropicModel = settingsDataStore.anthropicModel.stateIn(viewModelScope, SharingStarted.Lazily, "claude-3-5-haiku-20241022")
    val backendUrl = settingsDataStore.backendUrl.stateIn(viewModelScope, SharingStarted.Lazily, "")
    val reportToken = settingsDataStore.reportToken.stateIn(viewModelScope, SharingStarted.Lazily, "")
    val dedupEnabled = settingsDataStore.dedupEnabled.stateIn(viewModelScope, SharingStarted.Lazily, true)
    val dedupThreshold = settingsDataStore.dedupThreshold.stateIn(viewModelScope, SharingStarted.Lazily, 0.85f)

    val sources: StateFlow<List<Source>> = sourceRepository.getAllSources()
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    private val _testResult = MutableStateFlow<String?>(null)
    val testResult: StateFlow<String?> = _testResult.asStateFlow()

    fun setOpenaiApiKey(value: String) = viewModelScope.launch { settingsDataStore.setOpenaiApiKey(value) }
    fun setMalpediaApiKey(value: String) = viewModelScope.launch { settingsDataStore.setMalpediaApiKey(value) }
    fun setOpenaiModel(value: String) = viewModelScope.launch { settingsDataStore.setOpenaiModel(value) }
    fun setLlmProvider(value: String) = viewModelScope.launch { settingsDataStore.setLlmProvider(value) }
    fun setAnthropicApiKey(value: String) = viewModelScope.launch { settingsDataStore.setAnthropicApiKey(value) }
    fun setAnthropicModel(value: String) = viewModelScope.launch { settingsDataStore.setAnthropicModel(value) }
    fun setBackendUrl(value: String) = viewModelScope.launch { settingsDataStore.setBackendUrl(value) }
    fun setReportToken(value: String) = viewModelScope.launch { settingsDataStore.setReportToken(value) }

    fun setLookbackDays(value: Int) = viewModelScope.launch { settingsDataStore.setLookbackDays(value) }
    fun setParallelRequests(value: Int) = viewModelScope.launch { settingsDataStore.setParallelRequests(value) }
    fun setDedupEnabled(value: Boolean) = viewModelScope.launch { settingsDataStore.setDedupEnabled(value) }
    fun setDedupThreshold(value: Float) = viewModelScope.launch { settingsDataStore.setDedupThreshold(value) }

    fun toggleSource(sourceId: Long, enabled: Boolean) = viewModelScope.launch {
        sourceRepository.setEnabled(sourceId, enabled)
    }

    fun deleteSource(sourceId: Long) = viewModelScope.launch {
        sourceRepository.delete(sourceId)
    }

    fun addFeed(name: String, url: String) = viewModelScope.launch {
        sourceRepository.upsertSource(name, url, true)
    }

    fun clearDatabase() = viewModelScope.launch {
        _testResult.value = "Clearing database…"
        try {
            withContext(Dispatchers.IO) {
                database.clearAllTables()
            }
            sourceRepository.initializeDefaultFeeds()
            appEvent.notifyDatabaseCleared()
            _testResult.value = "Database cleared successfully"
            autoDismissResult()
        } catch (e: Exception) {
            _testResult.value = "Error clearing: ${e.message}"
            autoDismissResult()
        }
    }

    fun clearDatabaseSince(days: Int) = viewModelScope.launch {
        _testResult.value = "Clearing articles from last ${days}d…"
        try {
            withContext(Dispatchers.IO) {
                val cutoff = DateUtils.cutoffIso(days)
                articleRepository.deleteArticlesFetchedSince(cutoff)
            }
            appEvent.notifyDatabaseCleared()
            _testResult.value = "Articles from last ${days}d cleared"
            autoDismissResult()
        } catch (e: Exception) {
            _testResult.value = "Error clearing: ${e.message}"
            autoDismissResult()
        }
    }

    fun testApiKey() = viewModelScope.launch {
        _testResult.value = "Testing…"
        try {
            val provider = llmProvider.value
            if (provider == "anthropic") {
                val results = mutableListOf<String>()

                val aKey = anthropicApiKey.value
                if (aKey.isBlank()) {
                    results.add("Anthropic: No API key configured")
                } else if (aKey.startsWith("sk-ant-")) {
                    results.add("Anthropic: API key format looks valid")
                } else {
                    results.add("Anthropic: API key should start with sk-ant-")
                }

                val oKey = openaiApiKey.value
                if (oKey.isBlank()) {
                    results.add("OpenAI (embeddings): No API key configured")
                } else if (oKey.startsWith("sk-")) {
                    results.add("OpenAI (embeddings): API key format looks valid")
                } else {
                    results.add("OpenAI (embeddings): API key should start with sk-")
                }

                _testResult.value = results.joinToString("\n")
            } else {
                val key = openaiApiKey.value
                if (key.isBlank()) {
                    _testResult.value = "No OpenAI API key configured"
                } else if (key.startsWith("sk-")) {
                    _testResult.value = "OpenAI API key format looks valid"
                } else {
                    _testResult.value = "OpenAI API key should start with sk-"
                }
            }
        } catch (e: Exception) {
            _testResult.value = "Error: ${e.message}"
        }
        autoDismissResult()
    }

    fun testMalpediaApiKey() = viewModelScope.launch {
        _testResult.value = "Testing Malpedia key…"
        try {
            val key = malpediaApiKey.value
            if (key.isBlank()) {
                _testResult.value = "No Malpedia API key configured"
            } else {
                _testResult.value = "Malpedia API key is set (format not validated)"
            }
        } catch (e: Exception) {
            _testResult.value = "Error: ${e.message}"
        }
        autoDismissResult()
    }

    private fun autoDismissResult() {
        viewModelScope.launch {
            kotlinx.coroutines.delay(3000)
            _testResult.value = null
        }
    }
}
