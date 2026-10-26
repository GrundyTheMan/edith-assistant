package com.edith.app.network

import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

object NetworkClient {
    private const val GOOGLE_API_BASE_URL = "https://www.googleapis.com/"

    private var ollamaApi: OllamaApi? = null
    private var googleApi: GoogleApiService? = null

    fun getOllamaApi(baseUrl: String): OllamaApi {
        if (ollamaApi == null) {
            ollamaApi = Retrofit.Builder()
                .baseUrl(baseUrl)
                .addConverterFactory(GsonConverterFactory.create())
                .build()
                .create(OllamaApi::class.java)
        }
        return ollamaApi!!
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
        ollamaApi = null
    }
}
