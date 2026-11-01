package com.edith.app.tools.web

import com.edith.app.core.error.Outcome
import com.edith.app.core.log.Log
import com.edith.app.core.log.LogTag

/** A web search service (C10 §1). */
interface SearchProvider {
    val id: String
    val displayName: String
    val needsApiKey: Boolean
    suspend fun search(query: String, maxResults: Int = 5): Outcome<List<SearchHit>>
}

data class SearchHit(val title: String, val url: String, val snippet: String, val published: String? = null)

data class WebPage(val title: String, val content: String)

/** Somewhere a page's readable text comes from. */
fun interface WebPageSource {
    suspend fun read(url: String): Outcome<WebPage>
}

/**
 * The search Edith's tools use (D3): DuckDuckGo, which needs no key; with an Ollama API key saved, Ollama's web search
 * first (an official API, so it doesn't break when a page layout changes or get blocked as a bot) and DuckDuckGo as
 * its backup. When both fail, Ollama's message is the one reported, since it's the one that can name a bad key.
 */
class WebSearch(
    private val duckDuckGo: SearchProvider,
    private val ollama: OllamaWebSearch,
    private val setup: WebSearchSetup,
) : SearchProvider {
    override val id = "web"
    override val displayName = "Web search"
    override val needsApiKey = false

    override suspend fun search(query: String, maxResults: Int): Outcome<List<SearchHit>> {
        if (setup.key() == null) return duckDuckGo.search(query, maxResults)
        val first = ollama.search(query, maxResults)
        if (first is Outcome.Success) return first
        Log.w(LogTag.Tools, "Ollama web search failed; trying DuckDuckGo")
        return duckDuckGo.search(query, maxResults).takeIf { it is Outcome.Success } ?: first
    }

    /** Pages are read on the phone; with a key saved, Ollama's web fetch is the backup (e.g. for script-only pages). */
    fun pages(reader: PageReader): WebPageSource = WebPageSource { url ->
        val own = reader.read(url)
        if (own is Outcome.Success || setup.key() == null || !PageReader.worthRetrying(own)) return@WebPageSource own
        Log.w(LogTag.Tools, "Reading the page failed; trying Ollama's web fetch")
        ollama.fetch(url).takeIf { it is Outcome.Success } ?: own
    }

    companion object {
        const val UNAVAILABLE = "The web search service didn't respond — try again shortly."
    }
}
