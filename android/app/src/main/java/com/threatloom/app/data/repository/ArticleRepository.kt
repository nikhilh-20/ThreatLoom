package com.threatloom.app.data.repository

import com.threatloom.app.data.local.dao.ArticleDao
import com.threatloom.app.data.local.dao.ArticleDedupEmbeddingDao
import com.threatloom.app.data.local.dao.ArticleWithSummaryTuple
import com.threatloom.app.data.local.dao.CorrelationDao
import com.threatloom.app.data.local.dao.SummaryDao
import com.threatloom.app.data.local.dao.EmbeddingDao
import com.threatloom.app.data.local.entity.ArticleEntity
import com.threatloom.app.domain.model.ArticleWithSummary
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ArticleRepository @Inject constructor(
    private val articleDao: ArticleDao,
    private val summaryDao: SummaryDao,
    private val embeddingDao: EmbeddingDao,
    private val dedupEmbeddingDao: ArticleDedupEmbeddingDao,
    private val correlationDao: CorrelationDao
) {
    suspend fun getArticles(search: String? = null, limit: Int = 20, offset: Int = 0): List<ArticleWithSummary> {
        return articleDao.getArticles(search, limit, offset).map { it.toDomain() }
    }

    suspend fun getArticleById(id: Long): ArticleWithSummary? {
        return articleDao.getArticleById(id)?.toDomain()
    }

    suspend fun getArticlesByIds(ids: List<Long>): List<ArticleWithSummary> {
        if (ids.isEmpty()) return emptyList()
        val results = articleDao.getArticlesByIds(ids)
        val byId = results.associateBy { it.id }
        return ids.mapNotNull { byId[it]?.toDomain() }
    }

    /**
     * Near-duplicate articles that were folded into [articleId] during deduplication
     * (i.e. this is the kept article). Used to show an "also reported by" cross-reference.
     */
    suspend fun getDuplicatesOf(articleId: Long): List<ArticleWithSummary> {
        val dupIds = correlationDao.getForArticle(articleId)
            .filter { it.correlationType == "duplicate" && it.articleId1 == articleId }
            .map { it.articleId2 }
            .distinct()
        return getArticlesByIds(dupIds)
    }

    suspend fun getTaggedArticles(cutoffDate: String? = null): List<ArticleWithSummary> {
        return articleDao.getTaggedArticles(cutoffDate).map { it.toDomain() }
    }

    suspend fun existsByUrl(url: String): Boolean {
        return articleDao.existsByUrl(url) != null
    }

    suspend fun insert(sourceId: Long, title: String, url: String, author: String?, publishedDate: String?, imageUrl: String?): Long {
        return articleDao.insert(ArticleEntity(
            sourceId = sourceId, title = title, url = url,
            author = author, publishedDate = publishedDate, imageUrl = imageUrl
        ))
    }

    suspend fun updateContent(articleId: Long, content: String) {
        articleDao.updateContent(articleId, content)
    }

    suspend fun getUnscraped(limit: Int = 20) = articleDao.getUnscraped(limit)
    suspend fun getUnsummarized(limit: Int = 10) = articleDao.getUnsummarized(limit)
    suspend fun getUnembedded(limit: Int = 50) = articleDao.getUnembedded(limit)

    suspend fun countAll() = articleDao.countAll()
    suspend fun countUnscraped() = articleDao.countUnscraped()
    suspend fun countUnsummarized() = articleDao.countUnsummarized()
    suspend fun countScrapeFailed() = articleDao.countScrapeFailed()
    suspend fun countLast24h() = articleDao.countLast24h()
    suspend fun resetScrapeFailed() = articleDao.resetScrapeFailed()

    suspend fun delete(articleId: Long) {
        embeddingDao.deleteByArticleId(articleId)
        dedupEmbeddingDao.deleteByArticleId(articleId)
        summaryDao.deleteByArticleId(articleId)
        correlationDao.deleteForArticle(articleId)
        articleDao.clearDuplicateRefsTo(articleId)
        articleDao.delete(articleId)
    }

    suspend fun deleteArticlesFetchedSince(cutoffDate: String) {
        correlationDao.deleteFetchedSince(cutoffDate)
        embeddingDao.deleteFetchedSince(cutoffDate)
        dedupEmbeddingDao.deleteFetchedSince(cutoffDate)
        summaryDao.deleteFetchedSince(cutoffDate)
        articleDao.clearDuplicateRefsFetchedSince(cutoffDate)
        articleDao.deleteFetchedSince(cutoffDate)
    }

    suspend fun deleteSummaryAndEmbedding(articleId: Long) {
        embeddingDao.deleteByArticleId(articleId)
        summaryDao.deleteByArticleId(articleId)
    }

    suspend fun getContentRaw(articleId: Long): String? = articleDao.getContentRaw(articleId)
    suspend fun getTitle(articleId: Long): String? = articleDao.getTitle(articleId)

    private fun ArticleWithSummaryTuple.toDomain() = ArticleWithSummary(
        id = id, title = title, url = url, author = author,
        publishedDate = published_date, fetchedDate = fetched_date,
        imageUrl = image_url, sourceName = source_name,
        summaryText = summary_text, keyPoints = key_points,
        tags = tags
    )
}
