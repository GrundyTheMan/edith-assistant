package com.edith.app.network

import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

object NetworkClient {
    private const val GOOGLE_API_BASE_URL = "https://www.googleapis.com/"

    private val ollamaApis = mutableMapOf<String, OllamaApi>()
    private var googleApi: GoogleApiService? = null

    // Local models can take well over OkHttp's 10s default to load and answer
    private val ollamaHttpClient = OkHttpClient.Builder()
        .readTimeout(3, TimeUnit.MINUTES)
        .build()

    @Synchronized
    fun getOllamaApi(baseUrl: String): OllamaApi {
        return ollamaApis.getOrPut(baseUrl) {
            Retrofit.Builder()
                .baseUrl(baseUrl)
                .client(ollamaHttpClient)
                .addConverterFactory(GsonConverterFactory.create())
                .build()
                .create(OllamaApi::class.java)
        }
    }

    fun getGoogleApi(): GoogleApiService {
        if (googleApi == null) {
            googleApi = Retrofit.Builder()
                .baseUrl(GOOGLE_API_BASE_URL)
                .addConverterFactory(GsonConverterFactory.create())
                .build()
                .create(GoogleApiService::class.java)
        }
        return googleApi!!
    }

    fun clear() {
        ollamaApis.clear()
    }
}
