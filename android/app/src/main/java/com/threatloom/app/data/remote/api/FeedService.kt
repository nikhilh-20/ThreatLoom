package com.threatloom.app.data.remote.api

import okhttp3.ResponseBody
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Url

interface FeedService {
    @GET
    suspend fun fetchUrl(
        @Url url: String,
        @Header("User-Agent") userAgent: String = "ThreatLoom/1.0 (+https://github.com/nikhilh-20/ThreatLoom; feed reader)",
        @Header("Accept") accept: String = "application/rss+xml, application/xml, text/xml, application/atom+xml, */*;q=0.8"
    ): ResponseBody

    @GET
    suspend fun fetchHtml(
        @Url url: String,
        @Header("User-Agent") userAgent: String = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36",
        @Header("Accept") accept: String = "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        @Header("Accept-Language") acceptLang: String = "en-US,en;q=0.9"
        // NOTE: Do NOT set Accept-Encoding here. OkHttp only performs transparent gzip
        // decompression when it adds the Accept-Encoding header itself; setting it manually
        // disables that, so ResponseBody.string() would return raw compressed bytes (binary
        // gibberish), making the LLM report scraped articles as "corrupted".
    ): ResponseBody
}
