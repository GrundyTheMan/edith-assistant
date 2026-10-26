package com.edith.app.utils

import android.content.Context
import android.content.SharedPreferences

class ConfigManager(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("edith_prefs", Context.MODE_PRIVATE)

    companion object {
        private const val KEY_OLLAMA_URL = "ollama_url"
        private const val KEY_MODEL_NAME = "model_name"
        private const val DEFAULT_URL = "http://10.0.2.2:11434"
        private const val DEFAULT_MODEL = "gemma4:12b"
    }

    fun getOllamaUrl(): String {
        return prefs.getString(KEY_OLLAMA_URL, DEFAULT_URL) ?: DEFAULT_URL
    }

    fun setOllamaUrl(url: String) {
        prefs.edit().putString(KEY_OLLAMA_URL, url).apply()
    }

    fun getModelName(): String {
        return prefs.getString(KEY_MODEL_NAME, DEFAULT_MODEL) ?: DEFAULT_MODEL
    }

    fun setModelName(modelName: String) {
        prefs.edit().putString(KEY_MODEL_NAME, modelName).apply()
    }
}
