package com.threatloom.app.ui.quiz

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import com.threatloom.app.data.preferences.SettingsDataStore
import com.threatloom.app.data.remote.dto.QuizEvaluationItem
import com.threatloom.app.data.remote.dto.QuizQuestionDto
import com.threatloom.app.data.remote.dto.QuizResult
import com.threatloom.app.data.repository.QuizRepository
import com.threatloom.app.domain.usecase.EvaluateQuizUseCase
import com.threatloom.app.domain.usecase.EvaluateResult
import com.threatloom.app.domain.usecase.GetGlobalQuizQuestionsUseCase
import com.threatloom.app.domain.usecase.GetGlobalQuizQuestionsUseCase.QuizArticleSource
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class QuizPhase { LOADING, ANSWERING, EVALUATING, COMPLETE }

data class EvalCostInfo(val cost: Double, val modelName: String)

@HiltViewModel
class QuizViewModel @Inject constructor(
    private val quizRepository: QuizRepository,
    private val getGlobalQuizQuestionsUseCase: GetGlobalQuizQuestionsUseCase,
    private val evaluateQuizUseCase: EvaluateQuizUseCase,
    private val settingsDataStore: SettingsDataStore
) : ViewModel() {

    companion object {
        const val TIMER_SECONDS = 300
        const val GLOBAL_ARTICLE_ID = -1L
    }

    private val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
    private val quizResultAdapter = moshi.adapter(QuizResult::class.java)

    private var articleId: Long = 0L

    private val _phase = MutableStateFlow(QuizPhase.LOADING)
    val phase: StateFlow<QuizPhase> = _phase.asStateFlow()

    private val _questions = MutableStateFlow<List<QuizQuestionDto>>(emptyList())
    val questions: StateFlow<List<QuizQuestionDto>> = _questions.asStateFlow()

    private val _debateTopic = MutableStateFlow<String?>(null)
    val debateTopic: StateFlow<String?> = _debateTopic.asStateFlow()

    private val _currentIndex = MutableStateFlow(0)
    val currentIndex: StateFlow<Int> = _currentIndex.asStateFlow()

    private val _timeRemaining = MutableStateFlow(TIMER_SECONDS)
    val timeRemaining: StateFlow<Int> = _timeRemaining.asStateFlow()

    private val _currentInput = MutableStateFlow("")
    val currentInput: StateFlow<String> = _currentInput.asStateFlow()

    private val _userAnswers = MutableStateFlow<List<String>>(emptyList())
    val userAnswers: StateFlow<List<String>> = _userAnswers.asStateFlow()

    private val _evaluations = MutableStateFlow<List<QuizEvaluationItem>?>(null)
    val evaluations: StateFlow<List<QuizEvaluationItem>?> = _evaluations.asStateFlow()

    private val _score = MutableStateFlow(0)
    val score: StateFlow<Int> = _score.asStateFlow()

    private val _globalBestScore = MutableStateFlow(0)
    val globalBestScore: StateFlow<Int> = _globalBestScore.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _evalCost = MutableStateFlow<EvalCostInfo?>(null)
    val evalCost: StateFlow<EvalCostInfo?> = _evalCost.asStateFlow()

    private val _articleSources = MutableStateFlow<List<QuizArticleSource?>>(emptyList())
    val articleSources: StateFlow<List<QuizArticleSource?>> = _articleSources.asStateFlow()

    private var timerJob: Job? = null

    fun init(id: Long) {
        if (_phase.value != QuizPhase.LOADING) return
        articleId = id
        viewModelScope.launch {
            if (id == GLOBAL_ARTICLE_ID) {
                _globalBestScore.value = settingsDataStore.globalQuizBestScore.first()
                val data = getGlobalQuizQuestionsUseCase()
                if (data.questions.isEmpty()) {
                    _error.value = "No quizzes available yet. Create quizzes from articles first."
                    return@launch
                }
                _questions.value = data.questions
                _debateTopic.value = data.debateTopic
                _articleSources.value = data.sources
            } else {
                val entity = quizRepository.getByArticleId(id)
                val questionsJson = entity?.questions
                if (questionsJson == null) {
                    _error.value = "Quiz not found. Generate a quiz from the article first."
                    return@launch
                }
                val result = try { quizResultAdapter.fromJson(questionsJson) } catch (_: Exception) { null }
                if (result == null || result.questions.isEmpty()) {
                    _error.value = "Failed to load quiz questions."
                    return@launch
                }
                _questions.value = result.questions
                _debateTopic.value = entity.debateTopic
            }
            startQuiz()
        }
    }

    private fun startQuiz() {
        _userAnswers.value = MutableList(_questions.value.size) { "" }
        _currentIndex.value = 0
        _currentInput.value = ""
        _evaluations.value = null
        _score.value = 0
        _phase.value = QuizPhase.ANSWERING
        startTimer()
    }

    private fun startTimer() {
        timerJob?.cancel()
        _timeRemaining.value = TIMER_SECONDS
        timerJob = viewModelScope.launch {
            while (_timeRemaining.value > 0) {
                delay(1000)
                _timeRemaining.value -= 1
            }
            submitOrTimeout()
        }
    }

    fun onInputChanged(text: String) {
        _currentInput.value = text
    }

    fun submitOrTimeout() {
        timerJob?.cancel()
        val answers = _userAnswers.value.toMutableList()
        val idx = _currentIndex.value
        if (idx < answers.size) {
            answers[idx] = _currentInput.value
            _userAnswers.value = answers
        }

        val nextIndex = idx + 1
        if (nextIndex < _questions.value.size) {
            _currentIndex.value = nextIndex
            _currentInput.value = ""
            startTimer()
        } else {
            _phase.value = QuizPhase.EVALUATING
            evaluate()
        }
    }

    fun dismissEvalCost() { _evalCost.value = null }

    private fun evaluate() {
        viewModelScope.launch {
            val result = evaluateQuizUseCase(_questions.value, _userAnswers.value)
            _evaluations.value = result.evaluations
            val score = result.evaluations.count { it.verdict == "correct" || it.verdict == "partial" }
            _score.value = score
            _phase.value = QuizPhase.COMPLETE
            _evalCost.value = EvalCostInfo(result.cost, result.modelName)

            saveBestScore(score, _questions.value.size)
        }
    }

    private suspend fun saveBestScore(score: Int, total: Int) {
        if (articleId == GLOBAL_ARTICLE_ID) {
            val current = settingsDataStore.globalQuizBestScore.first()
            if (score > current) {
                settingsDataStore.setGlobalQuizBestScore(score)
                _globalBestScore.value = score
            }
        } else {
            quizRepository.updateBestScore(articleId, score, total)
        }
    }

    fun retry() {
        _evalCost.value = null
        startQuiz()
    }

    override fun onCleared() {
        super.onCleared()
        timerJob?.cancel()
    }
}
