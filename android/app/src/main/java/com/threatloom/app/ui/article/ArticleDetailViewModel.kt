package com.threatloom.app.ui.article

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import com.threatloom.app.data.local.entity.QuizEntity
import com.threatloom.app.data.preferences.SettingsDataStore
import com.threatloom.app.data.remote.dto.AttackFlowDto
import com.threatloom.app.data.repository.ArticleRepository
import com.threatloom.app.data.repository.DebateRepository
import com.threatloom.app.data.repository.QuizRepository
import com.threatloom.app.domain.model.ArticleWithSummary
import com.threatloom.app.domain.model.AttackFlowStep
import com.threatloom.app.domain.service.ReportService
import com.threatloom.app.domain.usecase.CostEstimate
import com.threatloom.app.domain.usecase.GenerateQuizUseCase
import com.threatloom.app.domain.usecase.SummarizeArticlesUseCase
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

data class QuizCostInfo(val questionCount: Int, val cost: Double, val modelName: String)
data class ResummarizeCostInfo(val cost: Double, val modelName: String)

@HiltViewModel
class ArticleDetailViewModel @Inject constructor(
    private val articleRepository: ArticleRepository,
    private val reportService: ReportService,
    private val settingsDataStore: SettingsDataStore,
    private val quizRepository: QuizRepository,
    private val debateRepository: DebateRepository,
    private val generateQuizUseCase: GenerateQuizUseCase,
    private val summarizeArticlesUseCase: SummarizeArticlesUseCase
) : ViewModel() {

    private val _article = MutableStateFlow<ArticleWithSummary?>(null)
    val article: StateFlow<ArticleWithSummary?> = _article.asStateFlow()

    private val _attackFlow = MutableStateFlow<List<AttackFlowStep>>(emptyList())
    val attackFlow: StateFlow<List<AttackFlowStep>> = _attackFlow.asStateFlow()

    private val _tags = MutableStateFlow<List<String>>(emptyList())
    val tags: StateFlow<List<String>> = _tags.asStateFlow()

    private val _duplicates = MutableStateFlow<List<ArticleWithSummary>>(emptyList())
    val duplicates: StateFlow<List<ArticleWithSummary>> = _duplicates.asStateFlow()

    private val _isLoading = MutableStateFlow(true)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()

    private val _summaryDeleted = MutableStateFlow(false)
    val summaryDeleted: StateFlow<Boolean> = _summaryDeleted.asStateFlow()

    private val _reportStatus = MutableStateFlow<String?>(null)
    val reportStatus: StateFlow<String?> = _reportStatus.asStateFlow()

    private val _quizData = MutableStateFlow<QuizEntity?>(null)
    val quizData: StateFlow<QuizEntity?> = _quizData.asStateFlow()

    private val _isGeneratingQuiz = MutableStateFlow(false)
    val isGeneratingQuiz: StateFlow<Boolean> = _isGeneratingQuiz.asStateFlow()

    private val _quizError = MutableStateFlow<String?>(null)
    val quizError: StateFlow<String?> = _quizError.asStateFlow()

    private val _quizCost = MutableStateFlow<QuizCostInfo?>(null)
    val quizCost: StateFlow<QuizCostInfo?> = _quizCost.asStateFlow()

    private val _debateExists = MutableStateFlow(false)
    val debateExists: StateFlow<Boolean> = _debateExists.asStateFlow()

    private val _isResummarizing = MutableStateFlow(false)
    val isResummarizing: StateFlow<Boolean> = _isResummarizing.asStateFlow()

    private val _resummarizeError = MutableStateFlow<String?>(null)
    val resummarizeError: StateFlow<String?> = _resummarizeError.asStateFlow()

    private val _resummarizeCost = MutableStateFlow<ResummarizeCostInfo?>(null)
    val resummarizeCost: StateFlow<ResummarizeCostInfo?> = _resummarizeCost.asStateFlow()

    private val _resummarizeEstimate = MutableStateFlow<CostEstimate?>(null)
    val resummarizeEstimate: StateFlow<CostEstimate?> = _resummarizeEstimate.asStateFlow()
    private var resummarizeDeferred: CompletableDeferred<Boolean>? = null

    fun sendReport(userNote: String) {
        val art = _article.value ?: return
        viewModelScope.launch {
            val backendUrl = settingsDataStore.backendUrl.first()
            if (backendUrl.isBlank()) {
                _reportStatus.value = "Backend URL not configured in Settings"
                autoDismiss()
                return@launch
            }
            _reportStatus.value = "Sending report…"
            val result = reportService.send(backendUrl, ReportService.ReportPayload(
                type = "Article Summary",
                identifier = art.title,
                llm_content = art.summaryText ?: "",
                metadata = buildMap {
                    put("Article URL", art.url)
                    put("Source", art.sourceName ?: "")
                    put("Published", art.publishedDate ?: "")
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

    fun deleteSummaryAndEmbedding(articleId: Long) {
        viewModelScope.launch {
            try {
                articleRepository.deleteSummaryAndEmbedding(articleId)
                _summaryDeleted.value = true
                loadArticle(articleId)
            } catch (_: Exception) {}
        }
    }

    fun loadArticle(articleId: Long) {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                val art = articleRepository.getArticleById(articleId)
                _article.value = art

                art?.tags?.let { tagsJson ->
                    try {
                        val listType = Types.newParameterizedType(List::class.java, String::class.java)
                        _tags.value = moshi.adapter<List<String>>(listType).fromJson(tagsJson) ?: emptyList()
                    } catch (_: Exception) {}
                }

                art?.keyPoints?.let { kpJson ->
                    try {
                        val listType = Types.newParameterizedType(List::class.java, AttackFlowDto::class.java)
                        val dtos = moshi.adapter<List<AttackFlowDto>>(listType).fromJson(kpJson) ?: emptyList()
                        _attackFlow.value = dtos.map {
                            AttackFlowStep(phase = it.phase, title = it.title, description = it.description, technique = it.technique)
                        }
                    } catch (_: Exception) {}
                }

                _duplicates.value = articleRepository.getDuplicatesOf(articleId)
                _quizData.value = quizRepository.getByArticleId(articleId)
                _debateExists.value = debateRepository.exists(articleId)
            } catch (_: Exception) {}
            _isLoading.value = false
        }
    }

    fun resummarize(articleId: Long) {
        if (_isResummarizing.value) return
        viewModelScope.launch {
            _isResummarizing.value = true
            // Show a cost estimate and wait for confirmation before spending on the LLM.
            val estimate = summarizeArticlesUseCase.estimateForArticle()
            if (estimate != null) {
                val deferred = CompletableDeferred<Boolean>()
                resummarizeDeferred = deferred
                _resummarizeEstimate.value = estimate
                if (!deferred.await()) {
                    _isResummarizing.value = false
                    return@launch
                }
            }
            when (val result = summarizeArticlesUseCase.invokeForArticle(articleId)) {
                is SummarizeArticlesUseCase.InvokeResult.Success -> {
                    loadArticle(articleId)
                    _resummarizeCost.value = ResummarizeCostInfo(result.cost, result.modelName)
                }
                is SummarizeArticlesUseCase.InvokeResult.Error -> {
                    _resummarizeError.value = result.message
                    launch { delay(4000); _resummarizeError.value = null }
                }
            }
            _isResummarizing.value = false
        }
    }

    fun confirmResummarize() {
        resummarizeDeferred?.complete(true)
        resummarizeDeferred = null
        _resummarizeEstimate.value = null
    }

    fun declineResummarize() {
        resummarizeDeferred?.complete(false)
        resummarizeDeferred = null
        _resummarizeEstimate.value = null
    }

    fun deleteDebate(articleId: Long) {
        viewModelScope.launch {
            try {
                debateRepository.delete(articleId)
                _debateExists.value = false
            } catch (_: Exception) {}
        }
    }

    fun dismissResummarizeCost() { _resummarizeCost.value = null }

    fun dismissQuizCost() { _quizCost.value = null }

    fun generateQuiz(articleId: Long) {
        if (_isGeneratingQuiz.value) return
        viewModelScope.launch {
            _isGeneratingQuiz.value = true
            _quizError.value = null
            when (val result = generateQuizUseCase(articleId)) {
                is GenerateQuizUseCase.Result.Success -> {
                    _quizData.value = quizRepository.getByArticleId(articleId)
                    _quizCost.value = QuizCostInfo(result.questionCount, result.cost, result.modelName)
                }
                is GenerateQuizUseCase.Result.Error -> {
                    _quizError.value = result.message
                    viewModelScope.launch { delay(4000); _quizError.value = null }
                }
            }
            _isGeneratingQuiz.value = false
        }
    }
}
