package com.threatloom.app.domain.service

import com.squareup.moshi.Moshi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

@Singleton
class ReportService @Inject constructor(
    @Named("generic") private val okHttpClient: OkHttpClient,
    private val moshi: Moshi
) {
    data class ReportPayload(
        val type: String,
        val identifier: String,
        val llm_content: String,
        val metadata: Map<String, String>,
        val user_note: String,
        val token: String
    )

    suspend fun send(backendUrl: String, payload: ReportPayload): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                val json = moshi.adapter(ReportPayload::class.java).toJson(payload)
                val body = json.toRequestBody("application/json".toMediaType())
                val request = Request.Builder()
                    .url("${backendUrl.trimEnd('/')}/api/report")
                    .post(body)
                    .build()
                val response = okHttpClient.newCall(request).execute()
                if (!response.isSuccessful) {
                    throw IOException("HTTP ${response.code}")
                }
            }
        }
}
