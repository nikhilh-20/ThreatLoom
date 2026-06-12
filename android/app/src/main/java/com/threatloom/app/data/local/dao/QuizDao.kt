package com.threatloom.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.threatloom.app.data.local.entity.QuizEntity

@Dao
interface QuizDao {
    @Query("SELECT * FROM quizzes WHERE article_id = :articleId")
    suspend fun getByArticleId(articleId: Long): QuizEntity?

    @Query("SELECT * FROM quizzes WHERE questions IS NOT NULL")
    suspend fun getAllWithQuestions(): List<QuizEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(quiz: QuizEntity): Long

    @Query("UPDATE quizzes SET questions = :questions, debate_topic = :debateTopic WHERE article_id = :articleId")
    suspend fun updateQuestions(articleId: Long, questions: String, debateTopic: String?)

    @Query("UPDATE quizzes SET score_best = :score, score_total = :total WHERE article_id = :articleId")
    suspend fun updateBestScore(articleId: Long, score: Int, total: Int)

    @Query("DELETE FROM quizzes WHERE article_id = :articleId")
    suspend fun deleteByArticleId(articleId: Long)
}
