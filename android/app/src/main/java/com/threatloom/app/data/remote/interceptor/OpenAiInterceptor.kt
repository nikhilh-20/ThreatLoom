package com.threatloom.app.data.remote.interceptor

import com.threatloom.app.data.preferences.SettingsDataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.Response

class OpenAiInterceptor(
    private val settingsDataStore: SettingsDataStore
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val apiKey = runBlocking { settingsDataStore.openaiApiKey.first() }
        val request = chain.request().newBuilder()
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
            .build()
        return chain.proceed(request)
    }
}
