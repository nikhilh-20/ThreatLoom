package com.threatloom.app.data.remote.dto

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class SummaryResult(
    @Json(name = "executive_summary") val executiveSummary: String = "",
    val details: List<String> = emptyList(),
    @Json(name = "analyst_notes") val analystNotes: String = "",
    val mitigations: List<String> = emptyList(),
    val iocs: List<String> = emptyList(),
    val tags: List<String> = emptyList(),
    @Json(name = "attack_flow") val attackFlow: List<AttackFlowDto> = emptyList()
)

@JsonClass(generateAdapter = true)
data class AttackFlowDto(
    val phase: String = "",
    val title: String = "",
    val description: String = "",
    val technique: String = ""
)
