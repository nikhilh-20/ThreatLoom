package com.threatloom.app.data.remote.dto

import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class TrendForecastResult(
    val trend: String = "",
    val forecast: String = ""
)
