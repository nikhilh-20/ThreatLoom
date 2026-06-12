package com.threatloom.app.di

import android.content.Context
import com.threatloom.app.data.preferences.SettingsDataStore
import com.threatloom.app.util.*
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideSettingsDataStore(@ApplicationContext context: Context): SettingsDataStore {
        return SettingsDataStore(context)
    }

    @Provides
    @Singleton
    fun provideHtmlTextExtractor(): HtmlTextExtractor = HtmlTextExtractor()

    @Provides
    @Singleton
    fun provideBibtexParser(): BibtexParser = BibtexParser()

    @Provides
    @Singleton
    fun provideEmbeddingMath(): EmbeddingMath = EmbeddingMath()

    @Provides
    @Singleton
    fun provideMarkdownComposer(): MarkdownComposer = MarkdownComposer()

    @Provides
    @Singleton
    fun provideCategoryMapper(): CategoryMapper = CategoryMapper()
}
