package com.edith.app.testing

import com.edith.app.core.error.AppError
import com.edith.app.core.error.Outcome
import com.edith.app.ollama.ChatCall
import com.edith.app.ollama.ChatEvent
import com.edith.app.ollama.OllamaClient
import com.edith.app.ollama.OllamaMessage
import com.edith.app.ollama.OllamaModelTag
import com.edith.app.ollama.OllamaShow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/**
 * [OllamaClient] for JVM tests. [models] maps model name → capabilities; set [failure] to make every request
 * fail with that error. Counts calls so tests can check debouncing and retries.
 */
class FakeOllamaClient(
    var models: Map<String, List<String>> = mapOf(
        "gemma4:12b-it-q8_0" to listOf("completion", "vision", "audio", "tools"),
        "qwen3.5:9b-q8_0" to listOf("completion", "tools"),
        "nomic-embed-text:latest" to listOf("embedding"),
    ),
    var version: String = "0.40.13",
    var failure: AppError? = null,
) : OllamaClient {
    var listModelsCalls = 0
        private set
    val listModelsUrls = mutableListOf<String>()

    override fun chatStream(baseUrl: String, request: ChatCall): Flow<ChatEvent> = emptyFlow()
    override suspend fun chat(baseUrl: String, request: ChatCall): Outcome<OllamaMessage> = error("not used")

    override suspend fun listModels(baseUrl: String): Outcome<List<OllamaModelTag>> {
        listModelsCalls++
        listModelsUrls += baseUrl
        return failure?.let { Outcome.Failure(it) } ?: Outcome.Success(models.keys.map { OllamaModelTag(it) })
    }

    override suspend fun show(baseUrl: String, model: String): Outcome<OllamaShow> =
        failure?.let { Outcome.Failure(it) }
            ?: models[model]?.let { Outcome.Success(OllamaShow(it)) }
            ?: Outcome.Failure(AppError.ModelNotFound(model))

    override suspend fun version(baseUrl: String): Outcome<String> =
        failure?.let { Outcome.Failure(it) } ?: Outcome.Success(version)
}
