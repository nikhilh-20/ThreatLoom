package com.threatloom.app.domain.model

data class AttackFlowStep(
    val phase: String,
    val title: String,
    val description: String,
    val technique: String = ""
)
