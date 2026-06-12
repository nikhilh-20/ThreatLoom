package com.threatloom.app.data.remote.dto

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class QuizQuestionDto(
    val question: String = "",
    @Json(name = "model_answer") val modelAnswer: String = "",
    val hint: String = ""
)

@JsonClass(generateAdapter = true)
data class QuizResult(
    val questions: List<QuizQuestionDto> = emptyList(),
    @Json(name = "debate_topic") val debateTopic: String = ""
)

@JsonClass(generateAdapter = true)
data class QuizEvaluationItem(
    val verdict: String = "incorrect",
    val feedback: String = ""
)

@JsonClass(generateAdapter = true)
data class QuizEvaluationResult(
    val evaluations: List<QuizEvaluationItem> = emptyList()
)
