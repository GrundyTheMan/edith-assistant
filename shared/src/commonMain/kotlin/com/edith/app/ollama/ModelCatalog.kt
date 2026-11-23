package com.edith.app.ollama

import com.edith.app.core.error.Outcome
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/** C03: Model catalog - cache of installed models with their capabilities. */
class ModelCatalog(private val client: OllamaClient) {
    private val cache = MutableStateFlow<Map<Pair<String, String>, Set<String>>>(emptyMap())

    /**
     * Chat-capable models with their capabilities; hides models lacking "completion" (e.g. embedding models).
     * A model whose details couldn't be read is kept with empty capabilities rather than hidden.
     */
    suspend fun chatModels(baseUrl: String): Outcome<List<ModelInfo>> {
        val tags = when (val result = client.listModels(baseUrl)) {
            is Outcome.Failure -> return result
            is Outcome.Success -> result.value
        }
        val models = coroutineScope {
            tags.map { tag ->
                async {
                    when (val caps = capabilities(baseUrl, tag.name)) {
                        is Outcome.Success -> ModelInfo(tag.name, caps.value).takeIf { "completion" in caps.value }
                        is Outcome.Failure -> ModelInfo(tag.name, emptySet())
                    }
                }
            }.awaitAll()
        }
        return Outcome.Success(models.filterNotNull())
    }

    /** The model's capabilities, cached per (server, model). Failures aren't cached. */
    suspend fun capabilities(baseUrl: String, model: String): Outcome<Set<String>> {
        cache.value[baseUrl to model]?.let { return Outcome.Success(it) }
        return when (val show = client.show(baseUrl, model)) {
            is Outcome.Failure -> show
            is Outcome.Success -> show.value.capabilities.toSet().also { found -> cache.update { current -> current + ((baseUrl to model) to found) } }.let { Outcome.Success(it) }
        }
    }

    fun invalidate() { cache.value = emptyMap() }
}

/** An installed model and what it can do (its Ollama capabilities). */
data class ModelInfo(val name: String, val capabilities: Set<String>) {
    val canUseTools get() = "tools" in capabilities
    val canSee get() = "vision" in capabilities
    val canHear get() = "audio" in capabilities
}
