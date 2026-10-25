package com.edith.app.network

import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

object NetworkClient {
    private var ollamaApi: OllamaApi? = null

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

    fun clear() {
        ollamaApi = null
    }
}
