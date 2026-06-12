package com.threatloom.app.data.remote.dto

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class QuarterlyTrendResult(
    val trend: String = "",
    @Json(name = "key_developments") val keyDevelopments: List<String> = emptyList(),
    val outlook: String = ""
)
