package com.threatloom.app.domain.usecase

import com.threatloom.app.data.preferences.SettingsDataStore
import com.threatloom.app.data.remote.api.OpenAiApi
import com.threatloom.app.data.remote.dto.EmbeddingRequest
import com.threatloom.app.data.repository.ArticleRepository
import com.threatloom.app.data.repository.EmbeddingRepository
import com.threatloom.app.util.AppLogger
import com.threatloom.app.util.EmbeddingMath
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import javax.inject.Inject

class EmbedArticlesUseCase @Inject constructor(
    private val articleRepository: ArticleRepository,
    private val embeddingRepository: EmbeddingRepository,
    private val openAiApi: OpenAiApi,
    private val embeddingMath: EmbeddingMath,
    private val settingsDataStore: SettingsDataStore,
    private val appLogger: AppLogger
) {
    companion object {
        private const val TAG = "EmbedArticles"
        private const val EMBEDDING_MODEL = "text-embedding-3-small"
    }

    suspend operator fun invoke(limit: Int = 50, onEach: (suspend () -> Unit)? = null): Int {
        val apiKey = settingsDataStore.openaiApiKey.first()
        if (apiKey.isBlank()) return 0

        val articles = articleRepository.getUnembedded(limit)
        if (articles.isEmpty()) return 0

        val texts = articles.map { "${it.title}\n${it.summary_text}" }

        return try {
            val response = openAiApi.embeddings(EmbeddingRequest(model = EMBEDDING_MODEL, input = texts))
            var stored = 0
            for ((article, embData) in articles.zip(response.data)) {
                try {
                    val blob = embeddingMath.floatsToBlob(embData.embedding)
                    embeddingRepository.upsert(article.id, blob, EMBEDDING_MODEL)
                    stored++
                    onEach?.invoke()
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    appLogger.e(TAG, "Failed to save embedding for article ${article.id}: ${e.message}")
                }
            }
            appLogger.i(TAG, "Generated embeddings for $stored/${articles.size} articles")
            articles.size
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            appLogger.e(TAG, "Embedding generation failed: ${e.message}")
            0
        }
    }
}
