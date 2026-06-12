package com.threatloom.app.ui.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.threatloom.app.data.preferences.SettingsDataStore
import com.threatloom.app.domain.model.ArticleWithSummary
import com.threatloom.app.domain.model.SubcategoryGroup
import com.threatloom.app.domain.model.TrendAnalysis
import com.threatloom.app.domain.service.CostTracker
import com.threatloom.app.domain.service.ReportService
import com.threatloom.app.domain.usecase.CategorizeArticlesUseCase
import com.threatloom.app.domain.usecase.GenerateCategoryInsightUseCase
import com.threatloom.app.domain.usecase.GenerateTrendAnalysisUseCase
import com.threatloom.app.util.DateUtils
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

data class InsightCostEstimate(
    val articleCount: Int,
    val estimatedCost: Double,
    val modelName: String,
    val nQuarters: Int? = null,
    val nYears: Int? = null
)

data class InsightActualCost(
    val articleCount: Int,
    val actualCost: Double,
    val modelName: String,
    val type: String  // "trend" or "forecast"
)

@HiltViewModel
class DrilldownViewModel @Inject constructor(
    private val categorizeArticlesUseCase: CategorizeArticlesUseCase,
    private val generateCategoryInsightUseCase: GenerateCategoryInsightUseCase,
    private val generateTrendAnalysisUseCase: GenerateTrendAnalysisUseCase,
    private val costTracker: CostTracker,
    private val settingsDataStore: SettingsDataStore,
    private val reportService: ReportService
) : ViewModel() {

    private val _articles = MutableStateFlow<List<ArticleWithSummary>>(emptyList())
    val articles: StateFlow<List<ArticleWithSummary>> = _articles.asStateFlow()

    private val _subcategories = MutableStateFlow<List<SubcategoryGroup>>(emptyList())
    val subcategories: StateFlow<List<SubcategoryGroup>> = _subcategories.asStateFlow()

    private val _isLoading = MutableStateFlow(true)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _quarterlyTrends = MutableStateFlow<List<TrendAnalysis>>(emptyList())
    val quarterlyTrends: StateFlow<List<TrendAnalysis>> = _quarterlyTrends.asStateFlow()

    private val _yearlyTrends = MutableStateFlow<List<TrendAnalysis>>(emptyList())
    val yearlyTrends: StateFlow<List<TrendAnalysis>> = _yearlyTrends.asStateFlow()

    private val _isTrendLoading = MutableStateFlow(false)
    val isTrendLoading: StateFlow<Boolean> = _isTrendLoading.asStateFlow()

    private val _trendProgress = MutableStateFlow("")
    val trendProgress: StateFlow<String> = _trendProgress.asStateFlow()

    private val _forecastText = MutableStateFlow<String?>(null)
    val forecastText: StateFlow<String?> = _forecastText.asStateFlow()

    private val _isForecastLoading = MutableStateFlow(false)
    val isForecastLoading: StateFlow<Boolean> = _isForecastLoading.asStateFlow()

    private val _selectedDays = MutableStateFlow(0)
    val selectedDays: StateFlow<Int> = _selectedDays.asStateFlow()

    private val _insightCostEstimate = MutableStateFlow<InsightCostEstimate?>(null)
    val insightCostEstimate: StateFlow<InsightCostEstimate?> = _insightCostEstimate.asStateFlow()

    private val _insightActualCost = MutableStateFlow<InsightActualCost?>(null)
    val insightActualCost: StateFlow<InsightActualCost?> = _insightActualCost.asStateFlow()

    private var insightCostDeferred: CompletableDeferred<Boolean>? = null

    private val _reportStatus = MutableStateFlow<String?>(null)
    val reportStatus: StateFlow<String?> = _reportStatus.asStateFlow()

    // Subcategory context — null when viewing a top-level category
    private var subcategoryTag: String? = null
    private var currentCategoryName: String = ""

    fun setTimeFilter(days: Int) {
        _selectedDays.value = days
        if (subcategoryTag != null) {
            loadSubcategory(currentCategoryName, subcategoryTag!!)
        } else {
            loadCategory(currentCategoryName)
        }
    }

    fun confirmInsightCost() {
        insightCostDeferred?.complete(true)
        insightCostDeferred = null
        _insightCostEstimate.value = null
    }

    fun declineInsightCost() {
        insightCostDeferred?.complete(false)
        insightCostDeferred = null
        _insightCostEstimate.value = null
    }

    fun dismissInsightActualCost() { _insightActualCost.value = null }

    private suspend fun activeModel(): String {
        val provider = settingsDataStore.llmProvider.first()
        return if (provider == "anthropic") settingsDataStore.anthropicModel.first()
               else settingsDataStore.openaiModel.first()
    }

    fun loadCategory(categoryName: String) {
        currentCategoryName = categoryName
        subcategoryTag = null
        val cutoff = if (_selectedDays.value > 0) DateUtils.cutoffIso(_selectedDays.value) else null
        viewModelScope.launch {
            _isLoading.value = true
            try {
                _articles.value = categorizeArticlesUseCase.getArticlesForCategory(categoryName, sinceDate = cutoff)
                _subcategories.value = categorizeArticlesUseCase.getSubcategories(categoryName, sinceDate = cutoff)
            } catch (_: Exception) {}
            _isLoading.value = false
        }
    }

    fun loadSubcategory(categoryName: String, tag: String) {
        currentCategoryName = categoryName
        subcategoryTag = tag
        val cutoff = if (_selectedDays.value > 0) DateUtils.cutoffIso(_selectedDays.value) else null
        viewModelScope.launch {
            _isLoading.value = true
            try {
                _articles.value = categorizeArticlesUseCase.getArticlesForSubcategory(categoryName, tag, sinceDate = cutoff)
                _subcategories.value = emptyList()
            } catch (_: Exception) {}
            _isLoading.value = false
        }
    }

    fun generateTrends(categoryName: String) {
        val cacheKey = buildCacheKey(categoryName)
        viewModelScope.launch {
            val model = activeModel()
            val articles = _articles.value
            val (estCost, nQ, nY) = costTracker.estimateTrendCost(articles, model)
            if (estCost > 0.0) {
                val deferred = CompletableDeferred<Boolean>()
                insightCostDeferred = deferred
                _insightCostEstimate.value = InsightCostEstimate(articles.size, estCost, model, nQ, nY)
                if (!deferred.await()) return@launch
            }
            _isTrendLoading.value = true
            _trendProgress.value = "Starting trend analysis..."
            val before = costTracker.getSnapshot()
            try {
                val (quarterly, yearly) = generateTrendAnalysisUseCase(
                    categoryName = cacheKey,
                    preFilteredArticles = articles,
                    onProgress = { progress -> _trendProgress.value = progress }
                )
                _quarterlyTrends.value = quarterly
                _yearlyTrends.value = yearly
                val actual = costTracker.deltaCost(before, costTracker.getSnapshot(), model)
                _insightActualCost.value = InsightActualCost(articles.size, actual, model, "trend")
            } catch (_: Exception) {
                _trendProgress.value = "Trend analysis failed"
            }
            _isTrendLoading.value = false
        }
    }

    fun generateForecast(categoryName: String) {
        viewModelScope.launch {
            val model = activeModel()
            val articles = _articles.value
            val estCost = costTracker.estimateInsightCost(articles.size, model)
            if (estCost > 0.0) {
                val deferred = CompletableDeferred<Boolean>()
                insightCostDeferred = deferred
                _insightCostEstimate.value = InsightCostEstimate(articles.size, estCost, model)
                if (!deferred.await()) return@launch
            }
            _isForecastLoading.value = true
            val before = costTracker.getSnapshot()
            try {
                val insight = generateCategoryInsightUseCase(
                    categoryName = categoryName,
                    subcategoryTag = subcategoryTag,
                    preFilteredArticles = articles
                )
                _forecastText.value = insight?.forecastText
                val actual = costTracker.deltaCost(before, costTracker.getSnapshot(), model)
                _insightActualCost.value = InsightActualCost(articles.size, actual, model, "forecast")
            } catch (_: Exception) {}
            _isForecastLoading.value = false
        }
    }

    fun sendTrendReport(userNote: String) {
        viewModelScope.launch {
            val url = settingsDataStore.backendUrl.first()
            if (url.isBlank()) { showReportError("Backend URL not configured"); return@launch }
            _reportStatus.value = "Sending…"
            val allTrends = (_quarterlyTrends.value + _yearlyTrends.value)
                .joinToString("\n\n---\n\n") { "[${it.periodLabel}]\n${it.trendText}" }
            val result = reportService.send(url, ReportService.ReportPayload(
                type = "Trend Analysis",
                identifier = currentCategoryName,
                llm_content = allTrends,
                metadata = mapOf(
                    "Category" to currentCategoryName,
                    "Articles" to _articles.value.size.toString(),
                    "Reported" to DateUtils.nowIso()
                ),
                user_note = userNote,
                token = settingsDataStore.reportToken.first()
            ))
            _reportStatus.value = if (result.isSuccess) "Report sent" else "Failed: ${result.exceptionOrNull()?.message}"
            autoDismissReport()
        }
    }

    fun sendForecastReport(userNote: String) {
        viewModelScope.launch {
            val url = settingsDataStore.backendUrl.first()
            if (url.isBlank()) { showReportError("Backend URL not configured"); return@launch }
            _reportStatus.value = "Sending…"
            val result = reportService.send(url, ReportService.ReportPayload(
                type = "Forecast",
                identifier = currentCategoryName,
                llm_content = _forecastText.value ?: "",
                metadata = mapOf(
                    "Category" to currentCategoryName,
                    "Articles" to _articles.value.size.toString(),
                    "Reported" to DateUtils.nowIso()
                ),
                user_note = userNote,
                token = settingsDataStore.reportToken.first()
            ))
            _reportStatus.value = if (result.isSuccess) "Report sent" else "Failed: ${result.exceptionOrNull()?.message}"
            autoDismissReport()
        }
    }

    private fun showReportError(msg: String) {
        _reportStatus.value = msg
        autoDismissReport()
    }

    private fun autoDismissReport() {
        viewModelScope.launch { delay(3000); _reportStatus.value = null }
    }

    private fun buildCacheKey(categoryName: String): String {
        return if (subcategoryTag != null) "$categoryName::$subcategoryTag" else categoryName
    }
}
