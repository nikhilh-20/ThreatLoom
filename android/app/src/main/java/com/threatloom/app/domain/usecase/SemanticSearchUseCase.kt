package com.threatloom.app.domain.usecase

import com.threatloom.app.data.preferences.SettingsDataStore
import com.threatloom.app.data.remote.api.OpenAiApi
import com.threatloom.app.data.remote.dto.EmbeddingRequest
import com.threatloom.app.data.repository.ArticleRepository
import com.threatloom.app.data.repository.EmbeddingRepository
import com.threatloom.app.domain.model.ArticleWithSummary
import com.threatloom.app.util.EmbeddingMath
import kotlinx.coroutines.flow.first
import javax.inject.Inject

class SemanticSearchUseCase @Inject constructor(
    private val embeddingRepository: EmbeddingRepository,
    private val articleRepository: ArticleRepository,
    private val openAiApi: OpenAiApi,
    private val embeddingMath: EmbeddingMath,
    private val settingsDataStore: SettingsDataStore
) {
    companion object {
        private const val EMBEDDING_MODEL = "text-embedding-3-small"
    }

    suspend operator fun invoke(query: String, topK: Int = 15, sinceDate: String? = null): List<ArticleWithSummary> {
        val apiKey = settingsDataStore.openaiApiKey.first()
        if (apiKey.isBlank()) return emptyList()

        val queryEmbedding = try {
            val response = openAiApi.embeddings(EmbeddingRequest(model = EMBEDDING_MODEL, input = listOf(query)))
            response.data.firstOrNull()?.embedding?.toFloatArray() ?: return emptyList()
        } catch (e: Exception) {
            return emptyList()
        }

        val allEmbeddings = if (sinceDate != null) {
            embeddingRepository.getByModelSinceDate(EMBEDDING_MODEL, sinceDate)
        } else {
            embeddingRepository.getByModel(EMBEDDING_MODEL)
        }
        if (allEmbeddings.isEmpty()) return emptyList()

        val ranked = embeddingMath.rankBySimilarity(queryEmbedding, allEmbeddings, topK)
        val rankedIds = ranked.map { it.first }
        val rankedScores = ranked.associate { it.first to it.second }

        val articles = articleRepository.getArticlesByIds(rankedIds)
        return articles.map { it.copy(relevanceScore = rankedScores[it.id]) }
    }
}
