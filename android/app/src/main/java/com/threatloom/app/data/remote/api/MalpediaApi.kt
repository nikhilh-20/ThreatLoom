package com.threatloom.app.data.remote.api

import okhttp3.ResponseBody
import retrofit2.http.GET
import retrofit2.http.Header

interface MalpediaApi {
    @GET("api/get/bib")
    suspend fun getBibtex(
        @Header("Authorization") authHeader: String
    ): ResponseBody
}
