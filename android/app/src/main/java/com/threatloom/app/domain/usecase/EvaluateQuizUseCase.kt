package com.threatloom.app.domain.usecase

import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import com.threatloom.app.data.remote.dto.ChatMessageDto
import com.threatloom.app.data.remote.dto.QuizEvaluationItem
import com.threatloom.app.data.remote.dto.QuizEvaluationResult
import com.threatloom.app.data.remote.dto.QuizQuestionDto
import com.threatloom.app.domain.service.CostTracker
import com.threatloom.app.domain.service.LlmService
import javax.inject.Inject

data class EvaluateResult(
    val evaluations: List<QuizEvaluationItem>,
    val cost: Double,
    val modelName: String
)

class EvaluateQuizUseCase @Inject constructor(
    private val llmService: LlmService,
    private val costTracker: CostTracker
) {
    companion object {
        private const val EVAL_PROMPT = """You are evaluating a learner's free-text quiz answers against model answers.
For each question, return a verdict and one-sentence feedback.
Verdict must be exactly one of: "correct", "partial", "incorrect".
- "correct": the learner captured the key concept, even if wording differs
- "partial": some correct elements but missing important details
- "incorrect": wrong or blank
Return JSON: { "evaluations": [ { "verdict": "...", "feedback": "..." }, ... ] }
The array must have the same length and order as the input questions.
Respond ONLY with valid JSON."""
    }

    private val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
    private val evalAdapter = moshi.adapter(QuizEvaluationResult::class.java)

    suspend operator fun invoke(
        questions: List<QuizQuestionDto>,
        userAnswers: List<String>
    ): EvaluateResult {
        if (questions.isEmpty()) return EvaluateResult(emptyList(), 0.0, "")

        val userMessage = questions.mapIndexed { i, q ->
            val answer = userAnswers.getOrElse(i) { "" }
            "Q${i + 1}: ${q.question}\nModel answer: ${q.modelAnswer}\nUser answer: $answer"
        }.joinToString("\n\n")

        return try {
            val model = llmService.getModelName()
            val before = costTracker.getSnapshot()

            val resultJson = llmService.chatCompletion(
                systemPrompt = EVAL_PROMPT,
                messages = listOf(ChatMessageDto("user", userMessage)),
                temperature = 0.1f,
                maxTokens = 1500,
                jsonMode = true
            ).content

            val cost = costTracker.deltaCost(before, costTracker.getSnapshot(), model)
            val evals = evalAdapter.fromJson(resultJson)?.evaluations
                ?: questions.map { QuizEvaluationItem("incorrect", "Could not evaluate.") }

            EvaluateResult(evals, cost, model)
        } catch (e: Exception) {
            EvaluateResult(
                questions.map { QuizEvaluationItem("incorrect", "Evaluation failed: ${e.message}") },
                0.0,
                ""
            )
        }
    }
}
