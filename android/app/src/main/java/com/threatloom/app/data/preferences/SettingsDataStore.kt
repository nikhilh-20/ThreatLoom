package com.threatloom.app.data.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "threat_loom_settings")

@Singleton
class SettingsDataStore @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        val OPENAI_API_KEY = stringPreferencesKey("openai_api_key")
        val MALPEDIA_API_KEY = stringPreferencesKey("malpedia_api_key")
        val OPENAI_MODEL = stringPreferencesKey("openai_model")
        val LOOKBACK_DAYS = intPreferencesKey("lookback_days")
        val PARALLEL_REQUESTS = intPreferencesKey("parallel_requests")
        val LLM_PROVIDER = stringPreferencesKey("llm_provider")
        val ANTHROPIC_API_KEY = stringPreferencesKey("anthropic_api_key")
        val ANTHROPIC_MODEL = stringPreferencesKey("anthropic_model")
        val BACKEND_URL = stringPreferencesKey("backend_url")
        val REPORT_TOKEN = stringPreferencesKey("report_token")
        val GLOBAL_QUIZ_BEST_SCORE = intPreferencesKey("global_quiz_best_score")
        val DEDUP_ENABLED = booleanPreferencesKey("dedup_enabled")
        val DEDUP_THRESHOLD = floatPreferencesKey("dedup_threshold")
    }

    val openaiApiKey: Flow<String> = context.dataStore.data.map { it[OPENAI_API_KEY] ?: "" }
    val malpediaApiKey: Flow<String> = context.dataStore.data.map { it[MALPEDIA_API_KEY] ?: "" }
    val openaiModel: Flow<String> = context.dataStore.data.map { it[OPENAI_MODEL] ?: "gpt-5.4-nano" }
    val lookbackDays: Flow<Int> = context.dataStore.data.map { it[LOOKBACK_DAYS] ?: 1 }
    val parallelRequests: Flow<Int> = context.dataStore.data.map { it[PARALLEL_REQUESTS] ?: 5 }
    val llmProvider: Flow<String> = context.dataStore.data.map { it[LLM_PROVIDER] ?: "openai" }
    val anthropicApiKey: Flow<String> = context.dataStore.data.map { it[ANTHROPIC_API_KEY] ?: "" }
    val anthropicModel: Flow<String> = context.dataStore.data.map { it[ANTHROPIC_MODEL] ?: "claude-haiku-4-5-20251001" }
    val backendUrl: Flow<String> = context.dataStore.data.map { it[BACKEND_URL] ?: "" }
    val reportToken: Flow<String> = context.dataStore.data.map { it[REPORT_TOKEN] ?: "" }
    val globalQuizBestScore: Flow<Int> = context.dataStore.data.map { it[GLOBAL_QUIZ_BEST_SCORE] ?: 0 }
    val dedupEnabled: Flow<Boolean> = context.dataStore.data.map { it[DEDUP_ENABLED] ?: true }
    val dedupThreshold: Flow<Float> = context.dataStore.data.map { it[DEDUP_THRESHOLD] ?: 0.85f }

    suspend fun setOpenaiApiKey(value: String) { context.dataStore.edit { it[OPENAI_API_KEY] = value } }
    suspend fun setMalpediaApiKey(value: String) { context.dataStore.edit { it[MALPEDIA_API_KEY] = value } }
    suspend fun setOpenaiModel(value: String) { context.dataStore.edit { it[OPENAI_MODEL] = value } }
    suspend fun setLookbackDays(value: Int) { context.dataStore.edit { it[LOOKBACK_DAYS] = value } }
    suspend fun setParallelRequests(value: Int) { context.dataStore.edit { it[PARALLEL_REQUESTS] = value } }
    suspend fun setLlmProvider(value: String) { context.dataStore.edit { it[LLM_PROVIDER] = value } }
    suspend fun setAnthropicApiKey(value: String) { context.dataStore.edit { it[ANTHROPIC_API_KEY] = value } }
    suspend fun setAnthropicModel(value: String) { context.dataStore.edit { it[ANTHROPIC_MODEL] = value } }
    suspend fun setBackendUrl(value: String) { context.dataStore.edit { it[BACKEND_URL] = value } }
    suspend fun setReportToken(value: String) { context.dataStore.edit { it[REPORT_TOKEN] = value } }
    suspend fun setGlobalQuizBestScore(value: Int) { context.dataStore.edit { it[GLOBAL_QUIZ_BEST_SCORE] = value } }
    suspend fun setDedupEnabled(value: Boolean) { context.dataStore.edit { it[DEDUP_ENABLED] = value } }
    suspend fun setDedupThreshold(value: Float) { context.dataStore.edit { it[DEDUP_THRESHOLD] = value } }
}
