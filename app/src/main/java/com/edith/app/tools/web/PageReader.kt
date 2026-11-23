package com.edith.app.tools.web

import com.edith.app.core.dispatchers.AppDispatchers
import com.edith.app.core.error.AppError
import com.edith.app.core.error.Outcome
import com.edith.app.core.log.Log
import com.edith.app.core.log.LogTag
import kotlinx.coroutines.withContext
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.http.URLBuilder
import io.ktor.http.URLProtocol
import io.ktor.http.Url
import io.ktor.http.charset
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.http.takeFrom
import io.ktor.utils.io.readRemaining
import kotlinx.io.readByteArray
import okhttp3.Dns
import okhttp3.OkHttpClient
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import org.jsoup.select.NodeTraversor
import org.jsoup.select.NodeVisitor
import java.io.IOException
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

/**
 * Downloads a page on the phone and turns it into readable text (C10 §2): 10 s, at most 2 MB, Jsoup for the
 * text. Only public http(s) addresses: redirects are followed by hand so every hop's host is checked, and
 * [PublicOnlyDns] refuses names that resolve to a private address, so neither the model nor a page can point Edith at
 * the owner's PC, router or anything else on their network.
 *
 * The requests are Ktor ones, but the name lookup is OkHttp's [Dns] on the engine underneath: that one hook is Android-only for
 * now (the iPhone needs its own public-address check, K4.5).
 */
class PageReader(
    baseClient: Lazy<OkHttpClient>,
    private val dispatchers: AppDispatchers,
    private val dns: Dns = PublicOnlyDns(),
    private val hostAllowed: (String) -> Boolean = { !PrivateNetwork.isPrivateHost(it) },
) {
    private val http: HttpClient by lazy {
        val okHttp = baseClient.value.newBuilder()
            .dns(dns)
            .followRedirects(false).followSslRedirects(false)
            .connectTimeout(TIMEOUT_S, TimeUnit.SECONDS).readTimeout(TIMEOUT_S, TimeUnit.SECONDS).callTimeout(TIMEOUT_S, TimeUnit.SECONDS)
            .build()
        HttpClient(OkHttp) {
            engine { preconfigured = okHttp }
            followRedirects = false   // by hand, below
            expectSuccess = false
        }
    }

    /** What one request came to: another address to go to, or the end of the matter. */
    private sealed interface Step {
        class Redirect(val to: Url) : Step
        class Done(val outcome: Outcome<WebPage>) : Step
    }

    suspend fun read(url: String): Outcome<WebPage> = withContext(dispatchers.io) {
        var current = webUrl(url) ?: return@withContext fail(NOT_WEB)
        repeat(MAX_REDIRECTS + 1) {
            if (!hostAllowed(current.host)) return@withContext fail(PRIVATE)
            val address = current
            try {
                when (val step = fetch(address)) {
                    is Step.Redirect -> current = step.to
                    is Step.Done -> return@withContext step.outcome
                }
            } catch (e: IOException) {
                val causes = generateSequence<Throwable>(e) { it.cause }.toList()
                return@withContext when {
                    causes.any { it is PrivateAddressException } -> fail(PRIVATE)
                    causes.any { it is UnknownHostException } -> fail("Couldn't find ${address.host}.")
                    else -> {
                        Log.w(LogTag.Tools, "Page ${address.host} unreachable (${e::class.simpleName})")
                        fail(UNREACHABLE)
                    }
                }
            }
        }
        fail("That page redirected too many times.")
    }

    private suspend fun fetch(address: Url): Step =
        http.prepareGet(address) {
            header(HttpHeaders.UserAgent, DuckDuckGoSearch.USER_AGENT)
            header(HttpHeaders.Accept, "text/html,application/xhtml+xml,text/plain;q=0.9,*/*;q=0.5")
        }.execute { response ->
            val code = response.status.value
            if (code in REDIRECTS) {
                val next = response.headers[HttpHeaders.Location]?.let { runCatching { URLBuilder(address).takeFrom(it).build() }.getOrNull() }
                return@execute next?.let { Step.Redirect(it) } ?: Step.Done(fail(httpError(code)))
            }
            if (!response.status.isSuccess()) {
                Log.w(LogTag.Tools, "Page ${address.host} HTTP $code")
                return@execute Step.Done(fail(httpError(code)))
            }
            val type = response.contentType()
            val kind = type?.let { "${it.contentType}/${it.contentSubtype}".lowercase() }
            if (kind != null && kind !in HTML_TYPES && kind != "text/plain") {
                return@execute Step.Done(fail("That's a $kind file, not a web page, so it can't be read."))
            }
            val bytes = response.bodyAsChannel().readRemaining(MAX_BYTES).readByteArray()
            val charset = type?.charset()
            val page = if (kind == "text/plain") {
                WebPage("", String(bytes, charset ?: Charsets.UTF_8))
            } else {
                readable(Jsoup.parse(bytes.inputStream(), charset?.name(), address.toString()))
            }
            Step.Done(Outcome.Success(page))
        }

    /** [text] as an http(s) address, or null. */
    private fun webUrl(text: String): Url? =
        runCatching { Url(text) }.getOrNull()?.takeIf { (it.protocol == URLProtocol.HTTP || it.protocol == URLProtocol.HTTPS) && it.host.isNotEmpty() }

    private fun fail(message: String) = Outcome.Failure(AppError.ToolFailed("read_web_page", message))

    companion object {
        const val NOT_WEB = "Only public http(s) web pages can be read."
        const val PRIVATE = "That address is on a private network; only public web pages can be read."
        const val UNREACHABLE = "That site didn't respond — try again shortly."
        const val MAX_BYTES = 2L * 1024 * 1024
        private const val MAX_REDIRECTS = 5
        private val REDIRECTS = setOf(300, 301, 302, 303, 307, 308)
        private const val TIMEOUT_S = 10L
        private val HTML_TYPES = setOf("text/html", "application/xhtml+xml")
        private val SPACES = Regex("\\s+")

        private fun httpError(code: Int) = when (code) {
            404, 410 -> "That page wasn't found (HTTP $code)."
            401, 403 -> "That site refused to show the page (HTTP $code)."
            else -> "That site answered with an error (HTTP $code)."
        }

        /** Whether Ollama's web fetch is worth a try after [failure]: never for addresses that were refused. */
        fun worthRetrying(failure: Outcome<WebPage>): Boolean =
            ((failure as? Outcome.Failure)?.error as? AppError.ToolFailed)?.detail.let { it != PRIVATE && it != NOT_WEB }

        /**
         * The page's text, one block (heading, paragraph, list item…) per line: scripts, styles, navigation, footers
         * and asides removed; the `article` or `main` element when there is one.
         */
        internal fun readable(doc: org.jsoup.nodes.Document): WebPage {
            doc.select("script, style, noscript, template, svg, iframe, nav, footer, aside, form, header").remove()
            val root: Element = doc.selectFirst("article") ?: doc.selectFirst("main") ?: doc.body()
            val text = StringBuilder()
            NodeTraversor.traverse(object : NodeVisitor {
                override fun head(node: Node, depth: Int) {
                    when (node) {
                        is TextNode -> text.append(node.text())
                        is Element -> if (node.isBlock || node.nameIs("br")) text.append('\n')
                    }
                }

                override fun tail(node: Node, depth: Int) {
                    if (node is Element && node.isBlock) text.append('\n')
                }
            }, root)
            val lines = text.lines().map { it.replace(SPACES, " ").trim() }.filter { it.isNotEmpty() }
            return WebPage(doc.title().trim(), lines.joinToString("\n"))
        }
    }
}

/** Refuses names that resolve to any private address, checked on the addresses OkHttp will actually connect to. */
class PublicOnlyDns(private val base: Dns = Dns.SYSTEM) : Dns {
    override fun lookup(hostname: String): List<InetAddress> {
        val addresses = base.lookup(hostname)
        if (addresses.any(PrivateNetwork::isPrivateAddress)) throw PrivateAddressException(hostname)
        return addresses
    }
}

/** A page's address is on a private network, so it isn't fetched. */
class PrivateAddressException(host: String) : UnknownHostException("$host is on a private network")

/** Loopback, link-local and private ranges, and names that only mean something on a LAN. No regexes (L25). */
object PrivateNetwork {
    fun isPrivateHost(host: String): Boolean {
        val h = host.lowercase().trim('[', ']')
        if (h == "localhost" || h.endsWith(".localhost") || h.endsWith(".local") || h.endsWith(".lan") ||
            h.endsWith(".internal") || h.endsWith(".home.arpa") || '.' !in h && ':' !in h
        ) return true
        if (':' in h) return h == "::1" || h == "::" || h.startsWith("fc") || h.startsWith("fd") || h.startsWith("fe80")
        val parts = h.split('.').map { it.toIntOrNull()?.takeIf { n -> n in 0..255 } }
        if (parts.size != 4 || parts.any { it == null }) return false   // a domain name
        val first = parts[0] ?: return false
        val second = parts[1] ?: return false
        return isPrivateV4(first, second)
    }

    fun isPrivateAddress(address: InetAddress): Boolean {
        if (address.isLoopbackAddress || address.isAnyLocalAddress || address.isLinkLocalAddress ||
            address.isSiteLocalAddress || address.isMulticastAddress
        ) return true
        val b = address.address.map { it.toInt() and 0xff }
        return when (address) {
            is Inet4Address -> isPrivateV4(b[0], b[1])
            is Inet6Address -> b[0] and 0xfe == 0xfc   // fc00::/7, unique local
            else -> false
        }
    }

    private fun isPrivateV4(a: Int, b: Int) =
        a == 10 || a == 127 || a == 0 || (a == 169 && b == 254) || (a == 192 && b == 168) || (a == 172 && b in 16..31) ||
            (a == 100 && b in 64..127) || a >= 224
}
