package com.edith.app.tools.web

import com.edith.app.core.dispatchers.AppDispatchers
import com.edith.app.core.error.AppError
import com.edith.app.core.error.Outcome
import com.edith.app.core.log.Log
import com.edith.app.core.log.LogTag
import kotlinx.coroutines.withContext
import com.edith.app.core.net.withTimeouts
import io.ktor.client.HttpClient
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.Url
import io.ktor.http.parameters
import com.fleeksoft.ksoup.Ksoup
import com.fleeksoft.ksoup.nodes.Document
import kotlinx.io.IOException

/**
 * DuckDuckGo's results pages, which need no key (D3) — the same approach as Jarvis. Not DuckDuckGo's "instant answer"
 * API, which returns nothing for most real questions (L20). Searches are POSTed like DuckDuckGo's own search form:
 * plain GETs were flagged as a bot after two or three requests when this was built (2026-11-01), POSTs weren't.
 * The main page (html.duckduckgo.com) first, then the lite page. Both are web pages, not an API, so a layout change
 * or a bot check can break them; [BLOCKED] says so plainly.
 */
class DuckDuckGoSearch(
    baseClient: Lazy<HttpClient>,
    private val dispatchers: AppDispatchers,
    private val htmlUrl: String = "https://html.duckduckgo.com/html/",
    private val liteUrl: String = "https://lite.duckduckgo.com/lite/",
) : SearchProvider {
    override val id = "duckduckgo"
    override val displayName = "DuckDuckGo"
    override val needsApiKey = false

    private val http by lazy { baseClient.value.withTimeouts(TIMEOUT_S, TIMEOUT_S, TIMEOUT_S) }

    override suspend fun search(query: String, maxResults: Int): Outcome<List<SearchHit>> = withContext(dispatchers.io) {
        var blocked = false
        var answered = false
        for ((page, url) in listOf("html" to htmlUrl, "lite" to liteUrl)) {
            val (code, html) = try {
                post(url, query)
            } catch (e: IOException) {
                Log.w(LogTag.Tools, "DuckDuckGo $page unreachable (${e::class.simpleName})")
                continue
            }
            val doc = Ksoup.parse(html, url)
            val hits = if (page == "html") parseHtml(doc) else parseLite(doc)
            if (hits.isNotEmpty()) return@withContext Outcome.Success(hits.take(maxResults))
            val challenged = code == 202 || isChallenge(doc)
            Log.w(LogTag.Tools, "DuckDuckGo $page: HTTP $code, no results${if (challenged) " (bot check)" else ""}")
            blocked = blocked || challenged
            answered = answered || (code == 200 && !challenged)
        }
        when {
            answered -> Outcome.Success(emptyList())   // a real "no results" page
            blocked -> Outcome.Failure(AppError.ToolFailed("web search", BLOCKED))
            else -> Outcome.Failure(AppError.ToolFailed("web search", WebSearch.UNAVAILABLE))
        }
    }

    private suspend fun post(url: String, query: String): Pair<Int, String> {
        val response = http.submitForm(url, formParameters = parameters { append("q", query) }) { header(HttpHeaders.UserAgent, USER_AGENT) }
        return response.status.value to response.bodyAsText()
    }

    companion object {
        const val BLOCKED = "DuckDuckGo is asking for a human check, so web search isn't available right now — try again later, " +
            "or add an Ollama API key in Settings → Web search."
        const val USER_AGENT = "EdithAssistant/2 (Android)"
        private const val TIMEOUT_S = 10L

        /** html.duckduckgo.com: one `div.result` per hit; ads have `result--ad`. */
        fun parseHtml(doc: Document): List<SearchHit> = doc.select("div.result").mapNotNull { r ->
            if (r.hasClass("result--ad")) return@mapNotNull null
            val link = r.selectFirst("a.result__a") ?: return@mapNotNull null
            val url = resultUrl(link.attr("href")) ?: return@mapNotNull null
            SearchHit(link.text().trim(), url, r.selectFirst(".result__snippet")?.text()?.trim().orEmpty())
        }

        /** lite.duckduckgo.com: a table; each hit's link row is followed by rows holding its snippet. */
        fun parseLite(doc: Document): List<SearchHit> = doc.select("a.result-link").mapNotNull { link ->
            val url = resultUrl(link.attr("href")) ?: return@mapNotNull null
            val snippet = generateSequence(link.closest("tr")?.nextElementSibling()) { it.nextElementSibling() }
                .takeWhile { it.selectFirst("a.result-link") == null }
                .firstNotNullOfOrNull { it.selectFirst("td.result-snippet") }
            SearchHit(link.text().trim(), url, snippet?.text()?.trim().orEmpty())
        }

        /** The real address: DuckDuckGo wraps results in `//duckduckgo.com/l/?uddg=<url>`; its own links (ads) are dropped. */
        fun resultUrl(href: String): String? {
            val absolute = if (href.startsWith("//")) "https:$href" else href
            fun web(text: String): Url? = runCatching { Url(text) }.getOrNull()?.takeIf { (it.protocol.name == "http" || it.protocol.name == "https") && it.host.isNotEmpty() }
            val parsed = web(absolute) ?: return null
            val real = parsed.parameters["uddg"]?.let(::web) ?: parsed
            return real.toString().takeUnless { real.host == "duckduckgo.com" || real.host.endsWith(".duckduckgo.com") }
        }

        fun isChallenge(doc: Document): Boolean =
            doc.selectFirst("form[action*=anomaly]") != null || doc.getElementById("challenge-form") != null
    }
}
