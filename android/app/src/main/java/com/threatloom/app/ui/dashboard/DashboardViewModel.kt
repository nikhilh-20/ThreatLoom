package com.threatloom.app.ui.dashboard

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.threatloom.app.data.preferences.SettingsDataStore
import com.threatloom.app.data.repository.ArticleRepository
import com.threatloom.app.data.repository.EmbeddingRepository
import com.threatloom.app.data.repository.SourceRepository
import com.threatloom.app.data.repository.SummaryRepository
import com.threatloom.app.domain.model.ArticleWithSummary
import com.threatloom.app.domain.model.CategoryGroup
import com.threatloom.app.domain.model.DashboardStats
import com.threatloom.app.data.repository.QuizRepository
import com.threatloom.app.domain.service.CostConfirmationGate
import com.threatloom.app.domain.usecase.ActualCostInfo
import com.threatloom.app.domain.usecase.BackfillTlcTagsUseCase
import com.threatloom.app.domain.usecase.CategorizeArticlesUseCase
import com.threatloom.app.domain.usecase.CostEstimate
import com.threatloom.app.domain.usecase.EmbedArticlesUseCase
import com.threatloom.app.domain.usecase.FetchKaidoBlogUseCase
import com.threatloom.app.domain.usecase.ReprocessFailuresUseCase
import com.threatloom.app.util.AppEvent
import com.threatloom.app.util.DateUtils
import com.threatloom.app.worker.RefreshPipelineWorker
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
import java.util.UUID
import javax.inject.Inject

@HiltViewModel
class DashboardViewModel @Inject constructor(
    private val application: Application,
    private val categorizeArticlesUseCase: CategorizeArticlesUseCase,
    private val articleRepository: ArticleRepository,
    private val sourceRepository: SourceRepository,
    private val summaryRepository: SummaryRepository,
    private val embeddingRepository: EmbeddingRepository,
    private val embedArticlesUseCase: EmbedArticlesUseCase,
    private val reprocessFailuresUseCase: ReprocessFailuresUseCase,
    private val backfillTlcTagsUseCase: BackfillTlcTagsUseCase,
    private val settingsDataStore: SettingsDataStore,
    private val appEvent: AppEvent,
    private val quizRepository: QuizRepository,
    private val costConfirmationGate: CostConfirmationGate
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

    // Local cost-confirmation gate for runReprocess()/backfillTlcTags(), which stay on
    // viewModelScope. The worker-driven refresh/custom-URL path uses [costConfirmationGate]
    // instead, since that work outlives this ViewModel.
    private var reprocessCostDeferred: CompletableDeferred<Boolean>? = null

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

    private val _isBackfillingTlcTags = MutableStateFlow(false)
    val isBackfillingTlcTags: StateFlow<Boolean> = _isBackfillingTlcTags.asStateFlow()

    private var searchJob: Job? = null
    private var liveReloadJob: Job? = null
    private var embedJob: Job? = null
    private var activePipelineWorkId: UUID? = null

    init {
        viewModelScope.launch {
            sourceRepository.initializeDefaultFeeds()
            sourceRepository.upsertSource(FetchKaidoBlogUseCase.BLOG_SOURCE_NAME, FetchKaidoBlogUseCase.BLOG_SOURCE_URL)
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
        viewModelScope.launch {
            appEvent.rateLimited.collect {
                _snackbarMessage.tryEmit("Rate limit reached — refresh will be slower and some summarizations may fail")
            }
        }
        viewModelScope.launch {
            costConfirmationGate.pending.collect { _costConfirmation.value = it }
        }
        observePipelineWork()
    }

    /**
     * Observes [RefreshPipelineWorker]'s WorkInfo to drive refresh UI state. This is what lets the
     * pipeline survive backgrounding/screen-lock/task-swipe: the work itself runs independently of
     * this ViewModel, which just reflects whatever state the worker (or a prior instance of it, if
     * this ViewModel was recreated mid-refresh) is currently in.
     */
    private fun observePipelineWork() {
        viewModelScope.launch {
            var wasRunning = false
            WorkManager.getInstance(application)
                .getWorkInfosForUniqueWorkFlow(RefreshPipelineWorker.WORK_NAME)
                .collect { infos ->
                    val nonTerminal = infos.filter { !it.state.isFinished }
                    val running = nonTerminal.isNotEmpty()
                    _isRefreshing.value = running

                    if (running && !wasRunning) {
                        _refreshProgress.value = 0f
                        _refreshStatus.value = "Starting…"
                        liveReloadJob = viewModelScope.launch {
                            delay(10_000)
                            while (true) {
                                loadDataQuietly()
                                delay(15_000)
                            }
                        }
                    } else if (!running && wasRunning) {
                        liveReloadJob?.cancel()
                        liveReloadJob = null
                    }
                    wasRunning = running

                    // Prefer the work we ourselves enqueued; fall back to whichever entry is still
                    // running (process-death recovery — ExistingWorkPolicy.KEEP guarantees at most one).
                    val tracked = infos.firstOrNull { it.id == activePipelineWorkId }
                        ?: nonTerminal.firstOrNull()?.also { activePipelineWorkId = it.id }
                        ?: return@collect

                    if (!tracked.state.isFinished) {
                        tracked.progress.getString(RefreshPipelineWorker.KEY_DETAIL)?.let { _refreshStatus.value = it }
                        _refreshProgress.value = tracked.progress.getDouble(RefreshPipelineWorker.KEY_FRACTION, 0.0).toFloat()
                        if (tracked.progress.getString(RefreshPipelineWorker.KEY_STAGE) == RefreshPipelineWorker.STAGE_ACTUAL_COST) {
                            val count = tracked.progress.getInt(RefreshPipelineWorker.KEY_ACTUAL_COST_ARTICLE_COUNT, 0)
                            val amount = tracked.progress.getDouble(RefreshPipelineWorker.KEY_ACTUAL_COST_AMOUNT, 0.0)
                            val model = tracked.progress.getString(RefreshPipelineWorker.KEY_ACTUAL_COST_MODEL) ?: ""
                            if (_actualCost.value?.articleCount != count || _actualCost.value?.actualCost != amount) {
                                _actualCost.value = ActualCostInfo(count, amount, model)
                            }
                        }
                    } else if (tracked.id == activePipelineWorkId) {
                        activePipelineWorkId = null
                        val aborted = tracked.state == WorkInfo.State.CANCELLED
                        when (tracked.state) {
                            WorkInfo.State.CANCELLED -> _refreshStatus.value = "Refresh aborted"
                            WorkInfo.State.FAILED -> _refreshStatus.value =
                                "Error: ${tracked.outputData.getString(RefreshPipelineWorker.KEY_ERROR) ?: "Unknown error"}"
                            else -> {}
                        }
                        loadData()
                        WorkManager.getInstance(application).pruneWork()
                        val statusAtCompletion = _refreshStatus.value
                        viewModelScope.launch {
                            delay(if (aborted) 3000L else 8000L)
                            if (_refreshStatus.value == statusAtCompletion) {
                                _refreshStatus.value = null
                                _refreshProgress.value = 0f
                            }
                        }
                    }
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
                    summaryFailed = summaryRepository.countFailed(),
                    pendingScrape = articleRepository.countUnscraped(),
                    duplicates = articleRepository.countDuplicates(),
                    missingTlcTags = backfillTlcTagsUseCase.countPending()
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
        viewModelScope.launch {
            val days = settingsDataStore.lookbackDays.first()
            enqueuePipeline(
                workDataOf(
                    RefreshPipelineWorker.KEY_MODE to RefreshPipelineWorker.MODE_REFRESH,
                    RefreshPipelineWorker.KEY_LOOKBACK_DAYS to days
                )
            )
        }
    }

    /** Quick refresh — only fetch articles since the last successful fetch (lookback=0). */
    fun refreshSinceLast() {
        if (_isRefreshing.value) return
        enqueuePipeline(workDataOf(RefreshPipelineWorker.KEY_MODE to RefreshPipelineWorker.MODE_SINCE_LAST))
    }

    fun processCustomUrls(urls: List<String>) {
        if (_isRefreshing.value || _isEmbedding.value) return
        enqueuePipeline(
            workDataOf(
                RefreshPipelineWorker.KEY_MODE to RefreshPipelineWorker.MODE_CUSTOM_URLS,
                RefreshPipelineWorker.KEY_URLS to urls.toTypedArray()
            )
        )
    }

    /**
     * Enqueues [RefreshPipelineWorker] as unique work — [androidx.work.ExistingWorkPolicy.KEEP]
     * means a second enqueue while one is already running/pending is dropped, matching the
     * `_isRefreshing` guards above.
     */
    private fun enqueuePipeline(data: Data) {
        val request = OneTimeWorkRequestBuilder<RefreshPipelineWorker>().setInputData(data).build()
        activePipelineWorkId = request.id
        WorkManager.getInstance(application)
            .enqueueUniqueWork(RefreshPipelineWorker.WORK_NAME, ExistingWorkPolicy.KEEP, request)
    }

    fun abortRefresh() {
        WorkManager.getInstance(application).cancelUniqueWork(RefreshPipelineWorker.WORK_NAME)
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
        val deferred = reprocessCostDeferred
        if (deferred != null) {
            deferred.complete(true)
            reprocessCostDeferred = null
            _costConfirmation.value = null
        } else {
            costConfirmationGate.approve()
        }
    }

    fun declineCost() {
        val deferred = reprocessCostDeferred
        if (deferred != null) {
            deferred.complete(false)
            reprocessCostDeferred = null
            _costConfirmation.value = null
        } else {
            costConfirmationGate.decline()
        }
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
     * wiring as the refresh pipeline (estimate confirmation + actual-cost readout). [busy] is the
     * busy flag to toggle (re-scrape vs re-summarize) for the progress UI.
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
                        reprocessCostDeferred = deferred
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

    /**
     * Backfills tlc- catalogue tags onto articles summarized before the catalogue existed, using
     * the same cost-dialog wiring as [runReprocess] (estimate confirmation + actual-cost readout).
     */
    fun backfillTlcTags() {
        if (_isRefreshing.value || _isReScraping.value || _isReSummarizing.value || _isEmbedding.value || _isBackfillingTlcTags.value) return
        viewModelScope.launch {
            _isBackfillingTlcTags.value = true
            _refreshProgress.value = 0f
            try {
                backfillTlcTagsUseCase(
                    onProgress = { progress ->
                        _refreshStatus.value = progress.detail
                        _refreshProgress.value = if (progress.total > 0) progress.current.toFloat() / progress.total else 0f
                    },
                    onConfirmCost = { estimate ->
                        val deferred = CompletableDeferred<Boolean>()
                        reprocessCostDeferred = deferred
                        _costConfirmation.value = estimate
                        deferred
                    },
                    onActualCost = { info -> _actualCost.value = info }
                )
            } catch (_: CancellationException) {
            } catch (e: Exception) {
                _refreshStatus.value = "Error: ${e.message}"
            }
            withContext(NonCancellable) {
                loadData()
                _refreshStatus.value = null
                _refreshProgress.value = 0f
                _isBackfillingTlcTags.value = false
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
