package com.threatloom.app.data.remote.dto

import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class GitHubCommitDto(
    val commit: GitHubCommitDetailDto
)

@JsonClass(generateAdapter = true)
data class GitHubCommitDetailDto(
    val committer: GitHubCommitterDto
)

@JsonClass(generateAdapter = true)
data class GitHubCommitterDto(
    val date: String
)
