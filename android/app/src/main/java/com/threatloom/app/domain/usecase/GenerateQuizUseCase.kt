package com.threatloom.app.domain.usecase

import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import com.threatloom.app.data.remote.dto.ChatMessageDto
import com.threatloom.app.data.remote.dto.QuizResult
import com.threatloom.app.data.repository.QuizRepository
import com.threatloom.app.data.repository.SummaryRepository
import com.threatloom.app.domain.service.CostTracker
import com.threatloom.app.domain.service.LlmService
import javax.inject.Inject

class GenerateQuizUseCase @Inject constructor(
    private val summaryRepository: SummaryRepository,
    private val quizRepository: QuizRepository,
    private val llmService: LlmService,
    private val costTracker: CostTracker
) {
    companion object {
        private const val QUIZ_PROMPT = """You are creating a technical quiz from cybersecurity study notes.

The goal is to help the reader durably understand the *important and sticky*
technical aspects of the article — the mechanisms, reasoning, attack/defense
logic, and conceptual takeaways that stay useful long after the news cycle passes.

Generate a JSON object with:
- "questions": array of question objects. Cover every important and sticky
  technical point — and nothing more. The NUMBER of questions does not matter:
  generate as few or as many as the article genuinely warrants. Do NOT pad with
  filler questions to reach a count, and do NOT drop a genuinely important concept
  just to stay short.

  Favor questions that test REASONING and UNDERSTANDING:
    * How or why a vulnerability, exploit, or technique actually works
    * The root cause, the attacker's objective, or the logic of an attack chain
    * Why a mitigation or detection works, and its trade-offs or limitations
    * Conceptual relationships (e.g. "why does X enable Y?")

  AVOID questions about ephemeral, look-up-able facts that don't build understanding:
    * Specific version or patch numbers (e.g. "which patch version remediates the CVE?")
    * Exact counts or statistics (e.g. "how many instances did Shodan find?")
    * Dates and deadlines (e.g. "what deadline did CISA set?")
    * Rote recall of CVE IDs, vendor names, or product names
  Such details may appear as supporting context inside a model_answer, but must
  never be the thing a question asks the reader to recall.

  Each object:
  { "question": string, "model_answer": string (the ideal answer, 1-3 sentences), "hint": string (a short clue pointing toward the answer without revealing it, 1 sentence) }
- "debate_topic": one broader cybersecurity landscape question inspired by the article,
  suitable for critical thinking and forming opinions about the threat landscape.
  Must NOT be a factual recall question.

Respond ONLY with valid JSON."""
    }

    private val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
    private val quizResultAdapter = moshi.adapter(QuizResult::class.java)

    sealed class Result {
        data class Success(val questionCount: Int, val cost: Double, val modelName: String) : Result()
        data class Error(val message: String) : Result()
    }

    suspend operator fun invoke(articleId: Long): Result {
        if (!llmService.hasApiKey()) return Result.Error("No API key configured")

        val summaryText = summaryRepository.getSummaryText(articleId)
        if (summaryText.isNullOrBlank()) return Result.Error("No summary found. Run the pipeline first.")

        return try {
            val model = llmService.getModelName()
            val before = costTracker.getSnapshot()

            val resultJson = llmService.chatCompletion(
                systemPrompt = QUIZ_PROMPT,
                messages = listOf(
                    ChatMessageDto("user", "Study Notes:\n$summaryText")
                ),
                temperature = 0.4f,
                maxTokens = 3000,
                jsonMode = true
            ).content

            val cost = costTracker.deltaCost(before, costTracker.getSnapshot(), model)

            val result = quizResultAdapter.fromJson(resultJson)
                ?: return Result.Error("Failed to parse quiz response")

            if (result.questions.isEmpty()) return Result.Error("No questions were generated")

            val questionsJson = moshi.adapter(QuizResult::class.java).toJson(result)
            quizRepository.updateQuestions(articleId, questionsJson, result.debateTopic.ifBlank { null })
            Result.Success(result.questions.size, cost, model)
        } catch (e: Exception) {
            Result.Error(e.message ?: "Failed to generate quiz")
        }
    }
}
