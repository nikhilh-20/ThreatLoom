package com.threatloom.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.threatloom.app.data.local.entity.ArticleEntity
import kotlinx.coroutines.flow.Flow

data class ArticleWithSummaryTuple(
    val id: Long,
    val title: String,
    val url: String,
    val author: String?,
    val published_date: String?,
    val fetched_date: String?,
    val image_url: String?,
    val source_name: String?,
    val summary_text: String?,
    val key_points: String?,
    val tags: String?,
)

@Dao
interface ArticleDao {
    @Query("""
        SELECT a.id, a.title, a.url, a.author, a.published_date, a.fetched_date,
               a.image_url, s.name as source_name,
               sm.summary_text, sm.key_points, sm.tags
        FROM articles a
        JOIN sources s ON a.source_id = s.id
        LEFT JOIN summaries sm ON sm.article_id = a.id
        WHERE a.duplicate_of_id IS NULL
        AND (:search IS NULL OR a.title LIKE '%' || :search || '%' OR sm.summary_text LIKE '%' || :search || '%' OR sm.tags LIKE '%' || :search || '%')
        ORDER BY a.published_date DESC, a.fetched_date DESC
        LIMIT :limit OFFSET :offset
    """)
    suspend fun getArticles(search: String? = null, limit: Int = 20, offset: Int = 0): List<ArticleWithSummaryTuple>

    @Query("""
        SELECT a.id, a.title, a.url, a.author, a.published_date, a.fetched_date,
               a.image_url, s.name as source_name,
               sm.summary_text, sm.key_points, sm.tags
        FROM articles a
        JOIN sources s ON a.source_id = s.id
        LEFT JOIN summaries sm ON sm.article_id = a.id
        WHERE a.id = :articleId
    """)
    suspend fun getArticleById(articleId: Long): ArticleWithSummaryTuple?

    @Query("""
        SELECT a.id, a.title, a.url, a.author, a.published_date, a.fetched_date,
               a.image_url, s.name as source_name,
               sm.summary_text, sm.key_points, sm.tags
        FROM articles a
        JOIN sources s ON a.source_id = s.id
        LEFT JOIN summaries sm ON sm.article_id = a.id
        WHERE a.id IN (:ids)
    """)
    suspend fun getArticlesByIds(ids: List<Long>): List<ArticleWithSummaryTuple>

    @Query("""
        SELECT a.id, a.title, a.url, a.author, a.published_date, a.fetched_date,
               a.image_url, s.name as source_name,
               sm.summary_text, sm.key_points, sm.tags
        FROM articles a
        JOIN sources s ON a.source_id = s.id
        LEFT JOIN summaries sm ON sm.article_id = a.id
        WHERE sm.tags IS NOT NULL AND sm.tags != '[]'
        AND (:cutoffDate IS NULL OR a.published_date >= :cutoffDate)
        ORDER BY a.published_date DESC, a.fetched_date DESC
        LIMIT 500
    """)
    suspend fun getTaggedArticles(cutoffDate: String? = null): List<ArticleWithSummaryTuple>

    @Query("SELECT 1 FROM articles WHERE url = :url LIMIT 1")
    suspend fun existsByUrl(url: String): Int?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(article: ArticleEntity): Long

    @Query("UPDATE articles SET content_raw = :content WHERE id = :articleId")
    suspend fun updateContent(articleId: Long, content: String)

    @Query("""
        SELECT id, url FROM articles
        WHERE content_raw IS NULL
        ORDER BY fetched_date DESC
        LIMIT :limit
    """)
    suspend fun getUnscraped(limit: Int = 20): List<UnscrapedArticle>

    @Query("""
        SELECT a.id, a.title, a.url, a.content_raw
        FROM articles a
        LEFT JOIN summaries sm ON sm.article_id = a.id
        WHERE sm.id IS NULL AND a.content_raw IS NOT NULL AND a.content_raw != ''
          AND a.duplicate_of_id IS NULL
        ORDER BY a.fetched_date DESC
        LIMIT :limit
    """)
    suspend fun getUnsummarized(limit: Int = 10): List<UnsummarizedArticle>

    /**
     * Articles eligible for semantic deduplication: scraped, not yet summarized, and not
     * already marked as a duplicate. Ordered longest-content first so the longest article
     * naturally becomes the cluster representative that is kept.
     */
    @Query("""
        SELECT a.id, a.title, a.content_raw, a.published_date, a.fetched_date,
               LENGTH(a.content_raw) AS content_length
        FROM articles a
        LEFT JOIN summaries sm ON sm.article_id = a.id
        WHERE sm.id IS NULL AND a.content_raw IS NOT NULL AND a.content_raw != ''
          AND a.duplicate_of_id IS NULL
        ORDER BY content_length DESC
        LIMIT :limit
    """)
    suspend fun getDedupCandidates(limit: Int = 500): List<DedupCandidate>

    @Query("UPDATE articles SET duplicate_of_id = :ofId WHERE id = :articleId")
    suspend fun setDuplicateOf(articleId: Long, ofId: Long)

    /** Clear any article that pointed to [articleId] as its kept duplicate representative. */
    @Query("UPDATE articles SET duplicate_of_id = NULL WHERE duplicate_of_id = :articleId")
    suspend fun clearDuplicateRefsTo(articleId: Long)

    /** Clear duplicate references pointing at articles about to be deleted by [deleteFetchedSince]. */
    @Query("""
        UPDATE articles SET duplicate_of_id = NULL
        WHERE duplicate_of_id IN (SELECT id FROM articles WHERE fetched_date >= :cutoffDate)
    """)
    suspend fun clearDuplicateRefsFetchedSince(cutoffDate: String)

    @Query("SELECT COUNT(*) FROM articles WHERE content_raw IS NULL")
    suspend fun countUnscraped(): Int

    @Query("""
        SELECT COUNT(*) FROM articles a
        LEFT JOIN summaries sm ON sm.article_id = a.id
        WHERE sm.id IS NULL AND a.content_raw IS NOT NULL AND a.content_raw != ''
          AND a.duplicate_of_id IS NULL
    """)
    suspend fun countUnsummarized(): Int

    @Query("SELECT COUNT(*) FROM articles WHERE content_raw IS NOT NULL AND content_raw = ''")
    suspend fun countScrapeFailed(): Int

    @Query("UPDATE articles SET content_raw = NULL WHERE content_raw = ''")
    suspend fun resetScrapeFailed()

    @Query("SELECT COUNT(*) FROM articles")
    suspend fun countAll(): Int

    @Query("SELECT COUNT(*) FROM articles WHERE fetched_date >= datetime('now', '-24 hours')")
    suspend fun countLast24h(): Int

    @Query("SELECT content_raw FROM articles WHERE id = :articleId")
    suspend fun getContentRaw(articleId: Long): String?

    @Query("SELECT title FROM articles WHERE id = :articleId")
    suspend fun getTitle(articleId: Long): String?

    @Query("DELETE FROM articles WHERE id = :articleId")
    suspend fun delete(articleId: Long)

    @Query("DELETE FROM articles WHERE fetched_date >= :cutoffDate")
    suspend fun deleteFetchedSince(cutoffDate: String)

    @Query("""
        SELECT a.id, a.title, sm.summary_text
        FROM articles a
        JOIN summaries sm ON sm.article_id = a.id
        LEFT JOIN article_embeddings ae ON ae.article_id = a.id
        WHERE ae.article_id IS NULL
          AND sm.summary_text IS NOT NULL AND sm.summary_text != ''
          AND sm.model_used != 'failed'
        ORDER BY a.fetched_date DESC
        LIMIT :limit
    """)
    suspend fun getUnembedded(limit: Int = 50): List<UnembeddedArticle>
}

data class UnscrapedArticle(val id: Long, val url: String)
data class UnsummarizedArticle(val id: Long, val title: String, val url: String, val content_raw: String)
data class UnembeddedArticle(val id: Long, val title: String, val summary_text: String)
data class DedupCandidate(
    val id: Long,
    val title: String,
    val content_raw: String,
    val published_date: String?,
    val fetched_date: String?,
    val content_length: Int
)
