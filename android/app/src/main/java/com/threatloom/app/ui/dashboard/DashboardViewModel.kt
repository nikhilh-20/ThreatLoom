package com.threatloom.app.ui.dashboard

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.threatloom.app.data.preferences.SettingsDataStore
import com.threatloom.app.data.repository.ArticleRepository
import com.threatloom.app.data.repository.EmbeddingRepository
import com.threatloom.app.data.repository.SourceRepository
import com.threatloom.app.data.repository.SummaryRepository
import com.threatloom.app.domain.model.ArticleWithSummary
import com.threatloom.app.domain.model.CategoryGroup
import com.threatloom.app.domain.model.DashboardStats
import com.threatloom.app.data.repository.QuizRepository
import com.threatloom.app.domain.usecase.ActualCostInfo
import com.threatloom.app.domain.usecase.CategorizeArticlesUseCase
import com.threatloom.app.domain.usecase.CostEstimate
import com.threatloom.app.domain.usecase.EmbedArticlesUseCase
import com.threatloom.app.domain.usecase.ProcessCustomUrlsUseCase
import com.threatloom.app.domain.usecase.ReprocessFailuresUseCase
import com.threatloom.app.domain.usecase.RunPipelineUseCase
import com.threatloom.app.util.AppEvent
import com.threatloom.app.util.DateUtils
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@HiltViewModel
class DashboardViewModel @Inject constructor(
    private val application: Application,
    private val categorizeArticlesUseCase: CategorizeArticlesUseCase,
    private val articleRepository: ArticleRepository,
    private val sourceRepository: SourceRepository,
    private val summaryRepository: SummaryRepository,
    private val embeddingRepository: EmbeddingRepository,
    private val runPipelineUseCase: RunPipelineUseCase,
    private val processCustomUrlsUseCase: ProcessCustomUrlsUseCase,
    private val embedArticlesUseCase: EmbedArticlesUseCase,
    private val reprocessFailuresUseCase: ReprocessFailuresUseCase,
    private val settingsDataStore: SettingsDataStore,
    private val appEvent: AppEvent,
    private val quizRepository: QuizRepository
) : AndroidViewModel(application) {

    private val _categories = MutableStateFlow<List<CategoryGroup>>(emptyList())
    val categories: StateFlow<List<CategoryGroup>> = _categories.asStateFlow()

    private val _stats = MutableStateFlow(DashboardStats())
    val stats: StateFlow<DashboardStats> = _stats.asStateFlow()

    private val _searchResults = MutableStateFlow<List<ArticleWithSummary>>(emptyList())
    val searchResults: StateFlow<List<ArticleWithSummary>> = _searchResults.asStateFlow()

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    private val _refreshStatus = MutableStateFlow<String?>(null)
    val refreshStatus: StateFlow<String?> = _refreshStatus.asStateFlow()

    private val _refreshProgress = MutableStateFlow(0f)
    val refreshProgress: StateFlow<Float> = _refreshProgress.asStateFlow()

    private val _isLoading = MutableStateFlow(true)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _costConfirmation = MutableStateFlow<CostEstimate?>(null)
    val costConfirmation: StateFlow<CostEstimate?> = _costConfirmation.asStateFlow()

    private val _actualCost = MutableStateFlow<ActualCostInfo?>(null)
    val actualCost: StateFlow<ActualCostInfo?> = _actualCost.asStateFlow()

    private var costDeferred: CompletableDeferred<Boolean>? = null

    private val _selectedDays = MutableStateFlow(0)
    val selectedDays: StateFlow<Int> = _selectedDays.asStateFlow()

    private val _isEmbedding = MutableStateFlow(false)
    val isEmbedding: StateFlow<Boolean> = _isEmbedding.asStateFlow()

    private val _snackbarMessage = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val snackbarMessage: SharedFlow<String> = _snackbarMessage.asSharedFlow()

    private val _globalQuizAvailable = MutableStateFlow(false)
    val globalQuizAvailable: StateFlow<Boolean> = _globalQuizAvailable.asStateFlow()

    private val _globalQuizBestScore = MutableStateFlow(0)
    val globalQuizBestScore: StateFlow<Int> = _globalQuizBestScore.asStateFlow()

    private val _isReScraping = MutableStateFlow(false)
    val isReScraping: StateFlow<Boolean> = _isReScraping.asStateFlow()

    private val _isReSummarizing = MutableStateFlow(false)
    val isReSummarizing: StateFlow<Boolean> = _isReSummarizing.asStateFlow()

    private var searchJob: Job? = null
    private var liveReloadJob: Job? = null
    private var pipelineJob: Job? = null
    private var embedJob: Job? = null

    init {
        viewModelScope.launch {
            sourceRepository.initializeDefaultFeeds()
            loadData()
        }
        viewModelScope.launch {
            appEvent.databaseCleared.collect {
                _categories.value = emptyList()
                _searchResults.value = emptyList()
                _stats.value = DashboardStats()
                loadData()
            }
        }
    }

    fun setTimeFilter(days: Int) {
        _selectedDays.value = days
        loadData()
    }

    fun loadData() {
        viewModelScope.launch {
            _isLoading.value = true
            val cutoff = if (_selectedDays.value > 0) DateUtils.cutoffIso(_selectedDays.value) else null
            try {
                _categories.value = categorizeArticlesUseCase.getCategories(sinceDate = cutoff)
                _stats.value = DashboardStats(
                    totalArticles = articleRepository.countAll(),
                    totalSources = sourceRepository.countEnabled(),
                    totalSummaries = summaryRepository.countAll(),
                    articlesLast24h = articleRepository.countLast24h(),
                    totalEmbedded = embeddingRepository.countAll(),
                    scrapeFailed = articleRepository.countScrapeFailed(),
                    unsummarized = articleRepository.countUnsummarized(),
                    summaryFailed = summaryRepository.countFailed()
                )
            } catch (_: Exception) {}

            try {
                _globalQuizAvailable.value = quizRepository.getAllWithQuestions().isNotEmpty()
                _globalQuizBestScore.value = settingsDataStore.globalQuizBestScore.first()
            } catch (_: Exception) {}

            _isLoading.value = false
        }
    }

    fun onSearchQueryChange(query: String) {
        _searchQuery.value = query
        searchJob?.cancel()
        if (query.isBlank()) {
            _searchResults.value = emptyList()
            return
        }
        searchJob = viewModelScope.launch {
            delay(400)
            try {
                _searchResults.value = articleRepository.getArticles(search = query, limit = 50)
            } catch (_: Exception) {}
        }
    }

    /** Full refresh using the configured lookback days from settings. */
    fun refresh() {
        if (_isRefreshing.value) return
        pipelineJob = viewModelScope.launch {
            val days = settingsDataStore.lookbackDays.first()
            runPipeline(days)
        }
    }

    /** Quick refresh — only fetch articles since the last successful fetch (lookback=0). */
    fun refreshSinceLast() {
        if (_isRefreshing.value) return
        pipelineJob = viewModelScope.launch { runPipeline(0) }
    }

    fun processCustomUrls(urls: List<String>) {
        if (_isRefreshing.value || _isEmbedding.value) return
        pipelineJob = viewModelScope.launch {
            _isRefreshing.value = true
            appEvent.setPipelineRunning(true)
            _refreshProgress.value = 0f
            _refreshStatus.value = "Processing ${urls.size} URL(s)…"

            var aborted = false
            try {
                processCustomUrlsUseCase(
                    urls = urls,
                    onProgress = { progress ->
                        _refreshStatus.value = progress.detail
                        _refreshProgress.value = progress.overallFraction
                    },
                    onConfirmCost = { estimate ->
                        val deferred = CompletableDeferred<Boolean>()
                        costDeferred = deferred
                        _costConfirmation.value = estimate
                        deferred
                    },
                    onActualCost = { info -> _actualCost.value = info },
                    onRateLimited = {
                        _snackbarMessage.tryEmit("Rate limit reached — processing will be slower and some summarizations may fail")
                    }
                )
            } catch (e: CancellationException) {
                aborted = true
            } catch (e: Exception) {
                _refreshStatus.value = "Error: ${e.message}"
            } finally {
                withContext(NonCancellable) {
                    loadData()
                    appEvent.setPipelineRunning(false)
                    _isRefreshing.value = false
                    if (aborted) _refreshStatus.value = "Processing aborted"
                    delay(if (aborted) 3000L else 8000L)
                    _refreshStatus.value = null
                    _refreshProgress.value = 0f
                }
            }
        }
    }

    fun abortRefresh() {
        pipelineJob?.cancel()
        pipelineJob = null
    }

    /** Generate embeddings for all summarized-but-not-yet-indexed articles. */
    fun embedArticles() {
        if (_isRefreshing.value || _isEmbedding.value) return
        embedJob = viewModelScope.launch {
            _isEmbedding.value = true
            _refreshStatus.value = "Generating embeddings…"
            var totalEmbedded = 0
            var aborted = false
            try {
                var batch: Int
                do {
                    batch = embedArticlesUseCase(limit = 50)
                    totalEmbedded += batch
                } while (batch > 0)
            } catch (e: CancellationException) {
                aborted = true
            } catch (_: Exception) {}
            withContext(NonCancellable) {
                loadData()
                _refreshStatus.value = when {
                    aborted -> "Embedding aborted"
                    totalEmbedded > 0 -> "Indexed $totalEmbedded articles"
                    else -> "Nothing new to index"
                }
                _isEmbedding.value = false
                delay(if (aborted) 3000L else 5000L)
                _refreshStatus.value = null
            }
        }
    }

    fun abortEmbed() {
        embedJob?.cancel()
        embedJob = null
    }

    fun confirmCost() {
        costDeferred?.complete(true)
        costDeferred = null
        _costConfirmation.value = null
    }

    fun declineCost() {
        costDeferred?.complete(false)
        costDeferred = null
        _costConfirmation.value = null
    }

    fun dismissActualCost() {
        _actualCost.value = null
    }

    fun summarizeUnsummarized() =
        runReprocess(ReprocessFailuresUseCase.Mode.SUMMARIZE_UNSUMMARIZED, _isReSummarizing)

    fun reScrapeFailures() =
        runReprocess(ReprocessFailuresUseCase.Mode.RESCRAPE, _isReScraping)

    fun reSummarizeFailures() =
        runReprocess(ReprocessFailuresUseCase.Mode.RESUMMARIZE, _isReSummarizing)

    /**
     * Drives a failure-reprocess action through [ReprocessFailuresUseCase] with the same cost-dialog
     * wiring as [runPipeline] (estimate confirmation + actual-cost readout). [busy] is the busy flag
     * to toggle (re-scrape vs re-summarize) for the progress UI.
     */
    private fun runReprocess(mode: ReprocessFailuresUseCase.Mode, busy: MutableStateFlow<Boolean>) {
        if (_isRefreshing.value || _isReScraping.value || _isReSummarizing.value || _isEmbedding.value) return
        viewModelScope.launch {
            busy.value = true
            _refreshProgress.value = 0f
            try {
                reprocessFailuresUseCase(
                    mode = mode,
                    onProgress = { progress ->
                        _refreshStatus.value = progress.detail
                        _refreshProgress.value = progress.overallFraction
                    },
                    onConfirmCost = { estimate ->
                        val deferred = CompletableDeferred<Boolean>()
                        costDeferred = deferred
                        _costConfirmation.value = estimate
                        deferred
                    },
                    onActualCost = { info -> _actualCost.value = info },
                    onRateLimited = {
                        _snackbarMessage.tryEmit("Rate limit reached — processing will be slower and some summarizations may fail")
                    }
                )
            } catch (_: CancellationException) {
            } catch (e: Exception) {
                _refreshStatus.value = "Error: ${e.message}"
            }
            withContext(NonCancellable) {
                loadData()
                _refreshStatus.value = null
                _refreshProgress.value = 0f
                busy.value = false
            }
        }
    }

    private suspend fun runPipeline(lookbackDays: Int) {
        _isRefreshing.value = true
        appEvent.setPipelineRunning(true)
        _refreshProgress.value = 0f
        _refreshStatus.value = "Starting…"

        // Periodically reload dashboard data while the pipeline runs
        liveReloadJob = viewModelScope.launch {
            // Wait a bit before the first reload so the pipeline has time to produce data
            delay(10_000)
            while (true) {
                loadDataQuietly()
                delay(15_000)
            }
        }

        var aborted = false
        try {
            runPipelineUseCase(
                lookbackDays = lookbackDays,
                onProgress = { progress ->
                    _refreshStatus.value = progress.detail
                    _refreshProgress.value = progress.overallFraction
                },
                onConfirmCost = { estimate ->
                    val deferred = CompletableDeferred<Boolean>()
                    costDeferred = deferred
                    _costConfirmation.value = estimate
                    deferred
                },
                onActualCost = { info ->
                    _actualCost.value = info
                },
                onRateLimited = {
                    _snackbarMessage.tryEmit("Rate limit reached — refresh will be slower and some summarizations may fail")
                }
            )
        } catch (e: CancellationException) {
            aborted = true
        } catch (e: Exception) {
            _refreshStatus.value = "Error: ${e.message}"
        } finally {
            withContext(NonCancellable) {
                liveReloadJob?.cancel()
                liveReloadJob = null
                loadData()
                appEvent.setPipelineRunning(false)
                _isRefreshing.value = false
                if (aborted) _refreshStatus.value = "Refresh aborted"
                delay(if (aborted) 3000L else 8000L)
                _refreshStatus.value = null
                _refreshProgress.value = 0f
            }
        }
    }

    /** Reload categories and stats without changing isLoading (avoids flicker during refresh). */
    private suspend fun loadDataQuietly() {
        val cutoff = if (_selectedDays.value > 0) DateUtils.cutoffIso(_selectedDays.value) else null
        try {
            _categories.value = categorizeArticlesUseCase.getCategories(sinceDate = cutoff)
            _stats.value = DashboardStats(
                totalArticles = articleRepository.countAll(),
                totalSources = sourceRepository.countEnabled(),
                totalSummaries = summaryRepository.countAll(),
                articlesLast24h = articleRepository.countLast24h(),
                totalEmbedded = embeddingRepository.countAll(),
                scrapeFailed = articleRepository.countScrapeFailed(),
                unsummarized = articleRepository.countUnsummarized(),
                summaryFailed = summaryRepository.countFailed()
            )
        } catch (_: Exception) {}
    }
}
