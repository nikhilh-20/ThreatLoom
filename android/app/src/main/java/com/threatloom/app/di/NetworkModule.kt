package com.threatloom.app.di

import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import com.threatloom.app.data.preferences.SettingsDataStore
import com.threatloom.app.data.remote.api.AnthropicApi
import com.threatloom.app.data.remote.api.FeedService
import com.threatloom.app.data.remote.api.MalpediaApi
import com.threatloom.app.data.remote.api.OpenAiApi
import com.threatloom.app.data.remote.interceptor.AnthropicInterceptor
import com.threatloom.app.data.remote.interceptor.OpenAiInterceptor
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import retrofit2.converter.scalars.ScalarsConverterFactory
import java.util.concurrent.TimeUnit
import javax.inject.Named
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    @Provides
    @Singleton
    fun provideMoshi(): Moshi = Moshi.Builder()
        .add(KotlinJsonAdapterFactory())
        .build()

    @Provides
    @Singleton
    @Named("openai")
    fun provideOpenAiClient(settingsDataStore: SettingsDataStore): OkHttpClient {
        return OkHttpClient.Builder()
            .addInterceptor(OpenAiInterceptor(settingsDataStore))
            .addInterceptor(HttpLoggingInterceptor().apply { level = HttpLoggingInterceptor.Level.BASIC })
            .connectTimeout(60, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    @Provides
    @Singleton
    @Named("anthropic")
    fun provideAnthropicClient(settingsDataStore: SettingsDataStore): OkHttpClient {
        return OkHttpClient.Builder()
            .addInterceptor(AnthropicInterceptor(settingsDataStore))
            .addInterceptor(HttpLoggingInterceptor().apply { level = HttpLoggingInterceptor.Level.BASIC })
            .connectTimeout(60, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    @Provides
    @Singleton
    @Named("generic")
    fun provideGenericClient(): OkHttpClient {
        return OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(90, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
    }

    @Provides
    @Singleton
    fun provideOpenAiApi(@Named("openai") client: OkHttpClient, moshi: Moshi): OpenAiApi {
        return Retrofit.Builder()
            .baseUrl("https://api.openai.com/")
            .client(client)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()
            .create(OpenAiApi::class.java)
    }

    @Provides
    @Singleton
    fun provideAnthropicApi(@Named("anthropic") client: OkHttpClient, moshi: Moshi): AnthropicApi {
        return Retrofit.Builder()
            .baseUrl("https://api.anthropic.com/")
            .client(client)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()
            .create(AnthropicApi::class.java)
    }

    @Provides
    @Singleton
    fun provideFeedService(@Named("generic") client: OkHttpClient): FeedService {
        return Retrofit.Builder()
            .baseUrl("https://placeholder.example.com/")
            .client(client)
            .addConverterFactory(ScalarsConverterFactory.create())
            .build()
            .create(FeedService::class.java)
    }

    @Provides
    @Singleton
    fun provideMalpediaApi(@Named("generic") client: OkHttpClient): MalpediaApi {
        return Retrofit.Builder()
            .baseUrl("https://malpedia.caad.fkie.fraunhofer.de/")
            .client(client)
            .addConverterFactory(ScalarsConverterFactory.create())
            .build()
            .create(MalpediaApi::class.java)
    }
}
