package com.threatloom.app.domain.usecase

import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import com.threatloom.app.data.remote.dto.QuizQuestionDto
import com.threatloom.app.data.remote.dto.QuizResult
import com.threatloom.app.data.repository.ArticleRepository
import com.threatloom.app.data.repository.QuizRepository
import javax.inject.Inject

class GetGlobalQuizQuestionsUseCase @Inject constructor(
    private val quizRepository: QuizRepository,
    private val articleRepository: ArticleRepository
) {
    companion object {
        const val TARGET_QUESTION_COUNT = 50
        const val MAX_QUESTIONS_PER_ARTICLE = 5
    }

    private val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
    private val quizResultAdapter = moshi.adapter(QuizResult::class.java)

    data class QuizArticleSource(val articleId: Long, val title: String, val url: String)

    data class GlobalQuizData(
        val questions: List<QuizQuestionDto>,
        val debateTopic: String?,
        val sources: List<QuizArticleSource?>
    )

    suspend operator fun invoke(): GlobalQuizData {
        val allRows = quizRepository.getAllWithQuestions()
        if (allRows.isEmpty()) return GlobalQuizData(emptyList(), null, emptyList())

        data class TaggedQuestion(
            val question: QuizQuestionDto,
            val articleId: Long,
            val sourceDebateTopic: String?
        )

        val pool = mutableListOf<TaggedQuestion>()
        for (row in allRows) {
            val questionsJson = row.questions ?: continue
            try {
                val result = quizResultAdapter.fromJson(questionsJson) ?: continue
                result.questions.shuffled().take(MAX_QUESTIONS_PER_ARTICLE).forEach { q ->
                    pool.add(TaggedQuestion(q, row.articleId, row.debateTopic))
                }
            } catch (_: Exception) {}
        }

        pool.shuffle()
        val selected = pool.take(TARGET_QUESTION_COUNT)
        val debateTopic = selected.mapNotNull { it.sourceDebateTopic }.randomOrNull()

        val distinctIds = selected.map { it.articleId }.distinct()
        val articlesById = articleRepository.getArticlesByIds(distinctIds)
            .associateBy { it.id }

        return GlobalQuizData(
            questions = selected.map { it.question },
            debateTopic = debateTopic,
            sources = selected.map { tagged ->
                articlesById[tagged.articleId]?.let { aws ->
                    QuizArticleSource(aws.id, aws.title, aws.url)
                }
            }
        )
    }
}
