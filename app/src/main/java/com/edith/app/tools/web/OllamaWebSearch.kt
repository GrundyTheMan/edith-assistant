package com.edith.app.tools.web

import com.edith.app.core.dispatchers.AppDispatchers
import com.edith.app.core.error.AppError
import com.edith.app.core.error.Outcome
import com.edith.app.core.log.Log
import com.edith.app.core.log.LogTag
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Ollama's web search and web fetch APIs (D3; docs.ollama.com/capabilities/web-search, checked 2026-11-01):
 * `POST https://ollama.com/api/web_search {"query","max_results" (≤ 10)}` → `{"results":[{"title","url","content"}]}`,
 * `POST https://ollama.com/api/web_fetch {"url"}` → `{"title","content","links":[…]}`, both with
 * `Authorization: Bearer <key>`. The key comes from [apiKey] (SecretStore) on every call and is never logged.
 * Optional (D3): used only while the owner has a key saved, see [WebSearch].
 */
class OllamaWebSearch(
    baseClient: Lazy<OkHttpClient>,
    private val dispatchers: AppDispatchers,
    private val apiKey: suspend () -> String?,
    private val baseUrl: String = "https://ollama.com/",
) : SearchProvider {
    override val id = "ollama"
    override val displayName = "Ollama web search"
    override val needsApiKey = true

    private val http by lazy {
        baseClient.value.newBuilder()
            .connectTimeout(TIMEOUT_S, TimeUnit.SECONDS).readTimeout(TIMEOUT_S, TimeUnit.SECONDS).callTimeout(TIMEOUT_S, TimeUnit.SECONDS)
            .build()
    }

    override suspend fun search(query: String, maxResults: Int): Outcome<List<SearchHit>> = search(query, maxResults, apiKey())

    /** [key] given explicitly by Settings' Test button, before it's saved. */
    suspend fun search(query: String, maxResults: Int, key: String?): Outcome<List<SearchHit>> =
        post("api/web_search", json.encodeToString(SearchRequest.serializer(), SearchRequest(query, maxResults.coerceIn(1, 10))), key) { body ->
            json.decodeFromString(SearchResponse.serializer(), body).results.map { SearchHit(it.title, it.url, it.content) }
        }

    suspend fun fetch(url: String): Outcome<WebPage> =
        post("api/web_fetch", json.encodeToString(FetchRequest.serializer(), FetchRequest(url)), apiKey()) { body ->
            json.decodeFromString(FetchResponse.serializer(), body).let { WebPage(it.title, it.content) }
        }

    private suspend fun <T> post(path: String, body: String, key: String?, parse: (String) -> T): Outcome<T> = withContext(dispatchers.io) {
        if (key.isNullOrBlank()) return@withContext Outcome.Failure(AppError.ToolFailed("web search", NO_KEY))
        val request = Request.Builder()
            .url(baseUrl.toHttpUrl().newBuilder().addPathSegments(path).build())
            .header("Authorization", "Bearer $key")
            .post(body.toRequestBody(JSON))
            .build()
        try {
            http.newCall(request).execute().use { response ->
                when (response.code) {
                    in 200..299 -> Outcome.Success(parse(response.body.string()))
                    401, 403 -> failure(path, response.code, REJECTED)
                    429 -> failure(path, response.code, LIMITED)
                    else -> failure(path, response.code, UNAVAILABLE)
                }
            }
        } catch (e: IOException) {
            Log.w(LogTag.Tools, "Ollama $path unreachable (${e::class.simpleName})")   // no URL, headers or key
            Outcome.Failure(AppError.ToolFailed("web search", UNAVAILABLE))
        } catch (e: IllegalArgumentException) {   // kotlinx.serialization: an unreadable reply
            Log.w(LogTag.Tools, "Ollama $path reply couldn't be read")
            Outcome.Failure(AppError.ToolFailed("web search", UNAVAILABLE))
        }
    }

    private fun failure(path: String, code: Int, message: String): Outcome<Nothing> {
        Log.w(LogTag.Tools, "Ollama $path HTTP $code")
        return Outcome.Failure(AppError.ToolFailed("web search", message))
    }

    companion object {
        const val NO_KEY = "Web search isn't set up — add an Ollama API key in Settings."
        const val REJECTED = "The search API key was rejected — check it in Settings."
        const val LIMITED = "Search limit reached — try later."
        const val UNAVAILABLE = WebSearch.UNAVAILABLE
        private const val TIMEOUT_S = 15L
        private val JSON = "application/json".toMediaType()
        private val json = Json { ignoreUnknownKeys = true; explicitNulls = false; encodeDefaults = true }
    }
}

@Serializable private data class SearchRequest(val query: String, @kotlinx.serialization.SerialName("max_results") val maxResults: Int)
@Serializable private data class SearchResponse(val results: List<SearchResult> = emptyList())
@Serializable private data class SearchResult(val title: String = "", val url: String = "", val content: String = "")
@Serializable private data class FetchRequest(val url: String)
@Serializable private data class FetchResponse(val title: String = "", val content: String = "")
