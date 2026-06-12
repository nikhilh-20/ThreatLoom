package com.threatloom.app.data.remote.api

import com.threatloom.app.data.remote.dto.*
import retrofit2.http.Body
import retrofit2.http.POST

interface OpenAiApi {
    @POST("v1/chat/completions")
    suspend fun chatCompletion(@Body request: OpenAiRequest): OpenAiResponse

    @POST("v1/embeddings")
    suspend fun embeddings(@Body request: EmbeddingRequest): EmbeddingResponse
}
