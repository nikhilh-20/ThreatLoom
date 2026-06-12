package com.threatloom.app.data.remote.api

import com.threatloom.app.data.remote.dto.AnthropicRequest
import com.threatloom.app.data.remote.dto.AnthropicResponse
import retrofit2.http.Body
import retrofit2.http.POST

interface AnthropicApi {
    @POST("v1/messages")
    suspend fun createMessage(@Body request: AnthropicRequest): AnthropicResponse
}
