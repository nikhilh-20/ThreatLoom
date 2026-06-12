package com.threatloom.app.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.threatloom.app.data.local.converter.Converters
import com.threatloom.app.data.local.dao.*
import com.threatloom.app.data.local.entity.*

@Database(
    entities = [
        SourceEntity::class,
        ArticleEntity::class,
        SummaryEntity::class,
        EmbeddingEntity::class,
        ArticleDedupEmbeddingEntity::class,
        CorrelationEntity::class,
        CategoryInsightEntity::class,
        TrendAnalysisEntity::class,
        QuizEntity::class,
        DebateEntity::class
    ],
    version = 7,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class ThreatLoomDatabase : RoomDatabase() {
    abstract fun sourceDao(): SourceDao
    abstract fun articleDao(): ArticleDao
    abstract fun summaryDao(): SummaryDao
    abstract fun embeddingDao(): EmbeddingDao
    abstract fun articleDedupEmbeddingDao(): ArticleDedupEmbeddingDao
    abstract fun correlationDao(): CorrelationDao
    abstract fun categoryInsightDao(): CategoryInsightDao
    abstract fun trendAnalysisDao(): TrendAnalysisDao
    abstract fun quizDao(): QuizDao
    abstract fun debateDao(): DebateDao
}
