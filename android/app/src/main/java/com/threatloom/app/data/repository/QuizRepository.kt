package com.threatloom.app.data.repository

import com.threatloom.app.data.local.dao.QuizDao
import com.threatloom.app.data.local.entity.QuizEntity
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class QuizRepository @Inject constructor(
    private val quizDao: QuizDao
) {
    suspend fun getByArticleId(articleId: Long): QuizEntity? = quizDao.getByArticleId(articleId)

    suspend fun getAllWithQuestions(): List<QuizEntity> = quizDao.getAllWithQuestions()

    suspend fun upsertQuiz(articleId: Long) {
        if (quizDao.getByArticleId(articleId) == null) {
            quizDao.upsert(QuizEntity(articleId = articleId))
        }
    }

    suspend fun updateQuestions(articleId: Long, questions: String, debateTopic: String?) {
        upsertQuiz(articleId)
        quizDao.updateQuestions(articleId, questions, debateTopic)
    }

    suspend fun deleteByArticleId(articleId: Long) = quizDao.deleteByArticleId(articleId)

    suspend fun updateBestScore(articleId: Long, score: Int, total: Int) {
        val existing = quizDao.getByArticleId(articleId)
        val currentBest = existing?.scoreBest ?: 0
        if (score > currentBest) {
            quizDao.updateBestScore(articleId, score, total)
        }
    }
}
