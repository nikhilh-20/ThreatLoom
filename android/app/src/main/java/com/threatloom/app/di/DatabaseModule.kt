package com.threatloom.app.di

import android.content.Context
import androidx.room.Room
import com.threatloom.app.data.local.ThreatLoomDatabase
import com.threatloom.app.data.local.dao.*
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): ThreatLoomDatabase {
        return Room.databaseBuilder(
            context,
            ThreatLoomDatabase::class.java,
            "threatlandscape.db"
        ).fallbackToDestructiveMigration().build()
    }

    @Provides fun provideSourceDao(db: ThreatLoomDatabase): SourceDao = db.sourceDao()
    @Provides fun provideArticleDao(db: ThreatLoomDatabase): ArticleDao = db.articleDao()
    @Provides fun provideSummaryDao(db: ThreatLoomDatabase): SummaryDao = db.summaryDao()
    @Provides fun provideEmbeddingDao(db: ThreatLoomDatabase): EmbeddingDao = db.embeddingDao()
    @Provides fun provideArticleDedupEmbeddingDao(db: ThreatLoomDatabase): ArticleDedupEmbeddingDao = db.articleDedupEmbeddingDao()
    @Provides fun provideCorrelationDao(db: ThreatLoomDatabase): CorrelationDao = db.correlationDao()
    @Provides fun provideCategoryInsightDao(db: ThreatLoomDatabase): CategoryInsightDao = db.categoryInsightDao()
    @Provides fun provideTrendAnalysisDao(db: ThreatLoomDatabase): TrendAnalysisDao = db.trendAnalysisDao()
    @Provides fun provideQuizDao(db: ThreatLoomDatabase): QuizDao = db.quizDao()
    @Provides fun provideDebateDao(db: ThreatLoomDatabase): DebateDao = db.debateDao()
}
