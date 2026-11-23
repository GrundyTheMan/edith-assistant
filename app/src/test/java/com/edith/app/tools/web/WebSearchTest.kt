package com.edith.app.tools.web

import com.edith.app.chat.engine.PromptContext
import com.edith.app.core.dispatchers.AppDispatchers
import com.edith.app.core.error.AppError
import com.edith.app.core.error.Outcome
import com.edith.app.core.log.LogRingBuffer
import com.edith.app.ollama.ModelInfo
import com.edith.app.settings.Settings
import com.edith.app.testing.FakeClock
import com.edith.app.testing.InMemorySecretStore
import com.edith.app.tools.framework.Tool
import com.edith.app.tools.framework.ToolArgs
import com.edith.app.tools.framework.ToolContext
import com.edith.app.tools.framework.ToolGuidanceSection
import com.edith.app.tools.framework.ToolRegistry
import com.edith.app.tools.framework.ToolResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.Dns
import okhttp3.OkHttpClient
import org.jsoup.Jsoup
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.InetAddress
import java.time.Instant
import java.time.ZoneId

/**
 * C10 (D3): DuckDuckGo search from real captured pages, the optional Ollama key and its fallback, reading pages on the
 * phone (private addresses refused), and the key's storage.
 */
class WebSearchTest {
    /** Plays DuckDuckGo and web pages. */
    private val web = MockWebServer()
    /** Plays ollama.com. */
    private val ollamaServer = MockWebServer()
    private val dispatchers = AppDispatchers(Dispatchers.Unconfined, Dispatchers.IO, Dispatchers.Default)
    private val secrets = InMemorySecretStore()
    private val setup = WebSearchSetup(secrets)
    private lateinit var ollama: OllamaWebSearch
    private lateinit var ddg: DuckDuckGoSearch
    private lateinit var search: WebSearch
    private lateinit var reader: PageReader
    private val ctx = ToolContext(FakeClock(Instant.parse("2026-11-01T16:00:00Z"), ZoneId.of("America/Detroit")), Settings(), dispatchers)
    private val key = "test-key-not-real-7f3a"
    private val client = lazyOf(OkHttpClient())

    @Before fun start() {
        web.start()
        ollamaServer.start()
        ollama = OllamaWebSearch(client, dispatchers, apiKey = { setup.key() }, baseUrl = ollamaServer.url("/").toString())
        ddg = DuckDuckGoSearch(client, dispatchers, htmlUrl = web.url("/html/").toString(), liteUrl = web.url("/lite/").toString())
        search = WebSearch(ddg, ollama, setup)
        // The test server is on this machine: allow it by name, keep everything else checked.
        reader = PageReader(client, dispatchers, dns = Dns.SYSTEM, hostAllowed = { it == web.hostName || !PrivateNetwork.isPrivateHost(it) })
    }

    @After fun stop() {
        web.close()
        ollamaServer.close()
    }

    private fun fixture(name: String) = javaClass.classLoader!!.getResource("web/$name")!!.readText()
    private fun ok(body: String, type: String = "text/html; charset=utf-8") = MockResponse.Builder().code(200).setHeader("Content-Type", type).body(body).build()
    private fun status(code: Int, body: String = """{"error":"x"}""") = MockResponse.Builder().code(code).body(body).build()
    private fun args(tool: Tool, json: String) = ToolArgs(Json.parseToJsonElement(json).jsonObject, tool.spec)
    private fun Outcome<*>.detail() = ((this as Outcome.Failure).error as AppError.ToolFailed).detail

    // DuckDuckGo

    @Test fun `duckduckgo search posts the query and parses the real results page`() = runTest {
        web.enqueue(ok(fixture("ddg_html.html")))
        val hits = (ddg.search("Pistons game tonight", 5) as Outcome.Success).value
        val request = web.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/html/", request.url.encodedPath)
        assertEquals("q=Pistons+game+tonight", request.body!!.utf8())
        assertEquals(DuckDuckGoSearch.USER_AGENT, request.headers["User-Agent"])
        assertEquals(5, hits.size)
        assertEquals(SearchHit("Schedule | Detroit Pistons - NBA.com", "https://www.nba.com/pistons/schedule", hits[0].snippet), hits[0])
        assertTrue(hits[0].snippet, hits[0].snippet.startsWith("Check the Detroit Pistons schedule"))
        assertTrue(hits.all { it.title.isNotBlank() && it.url.startsWith("https://") && "duckduckgo.com" !in it.url })
    }

    @Test fun `a bot check on the main page falls back to the lite page`() = runTest {
        web.enqueue(status(202, fixture("ddg_challenge.html")))
        web.enqueue(ok(fixture("ddg_lite.html")))
        val hits = (ddg.search("Pistons game tonight", 10) as Outcome.Success).value
        assertEquals("/html/", web.takeRequest().url.encodedPath)
        assertEquals("/lite/", web.takeRequest().url.encodedPath)
        assertEquals(10, hits.size)
        assertEquals("https://www.nba.com/pistons/schedule", hits[0].url)
        assertTrue(hits[0].snippet, hits[0].snippet.startsWith("Check the Detroit Pistons schedule"))
        assertTrue("every lite hit has its own snippet", hits.all { it.snippet.isNotBlank() } && hits.map { it.snippet }.toSet().size == hits.size)
    }

    @Test fun `blocked, empty and unreachable searches say so plainly`() = runTest {
        repeat(2) { web.enqueue(status(202, fixture("ddg_challenge.html"))) }
        assertEquals(DuckDuckGoSearch.BLOCKED, ddg.search("x", 5).detail())
        assertTrue(DuckDuckGoSearch.isChallenge(Jsoup.parse(fixture("ddg_challenge.html"))))
        assertFalse(DuckDuckGoSearch.isChallenge(Jsoup.parse(fixture("ddg_html.html"))))

        repeat(2) { web.enqueue(ok("<html><body><div class='no-results'>No results.</div></body></html>")) }
        assertEquals(Outcome.Success(emptyList<SearchHit>()), ddg.search("zzqqxx", 5))

        repeat(2) { web.enqueue(status(500, "")) }
        assertEquals(WebSearch.UNAVAILABLE, ddg.search("x", 5).detail())
    }

    @Test fun `result links are unwrapped and duckduckgo's own links dropped`() {
        assertEquals("https://www.nba.com/pistons/schedule",
            DuckDuckGoSearch.resultUrl("//duckduckgo.com/l/?uddg=https%3A%2F%2Fwww.nba.com%2Fpistons%2Fschedule&rut=abc"))
        assertEquals("https://example.com/a", DuckDuckGoSearch.resultUrl("https://example.com/a"))
        assertNull("ad", DuckDuckGoSearch.resultUrl("//duckduckgo.com/y.js?ad_domain=x"))
        assertNull(DuckDuckGoSearch.resultUrl("javascript:void(0)"))
    }

    // The optional Ollama key

    @Test fun `without a key only duckduckgo is used, with one Ollama comes first`() = runTest {
        web.enqueue(ok(fixture("ddg_html.html")))
        assertTrue(search.search("Pistons", 3) is Outcome.Success)
        assertEquals(0, ollamaServer.requestCount)

        setup.save(key)
        ollamaServer.enqueue(ok(fixture("web_search.json"), "application/json"))
        val hits = (search.search("Pistons game tonight", 3) as Outcome.Success).value
        val request = ollamaServer.takeRequest()
        assertEquals("/api/web_search", request.url.encodedPath)
        assertEquals("Bearer $key", request.headers["Authorization"])
        val body = Json.parseToJsonElement(request.body!!.utf8()).jsonObject
        assertEquals("Pistons game tonight", body["query"]!!.jsonPrimitive.content)
        assertEquals(3, body["max_results"]!!.jsonPrimitive.int)
        assertTrue(hits.isNotEmpty() && hits.all { it.title.isNotBlank() && it.url.startsWith("http") && it.snippet.isNotBlank() })
        assertEquals("DuckDuckGo not asked", 1, web.requestCount)
    }

    @Test fun `a failing key falls back to duckduckgo, and the key's message is kept when both fail`() = runTest {
        setup.save(key)
        ollamaServer.enqueue(status(401))
        web.enqueue(ok(fixture("ddg_html.html")))
        assertEquals("https://www.nba.com/pistons/schedule", (search.search("Pistons", 3) as Outcome.Success).value[0].url)

        ollamaServer.enqueue(status(401))
        repeat(2) { web.enqueue(status(202, fixture("ddg_challenge.html"))) }
        assertEquals(OllamaWebSearch.REJECTED, search.search("Pistons", 3).detail())
    }

    @Test fun `Ollama errors map to plain messages, and nothing is sent without a key`() = runTest {
        suspend fun detail(code: Int): String {
            ollamaServer.enqueue(status(code))
            return ollama.search("x", 1).detail()
        }
        assertEquals(OllamaWebSearch.NO_KEY, ollama.search("x", 1).detail())
        assertEquals(0, ollamaServer.requestCount)
        setup.save(key)
        assertEquals(OllamaWebSearch.REJECTED, detail(401))
        assertEquals(OllamaWebSearch.REJECTED, detail(403))
        assertEquals(OllamaWebSearch.LIMITED, detail(429))
        assertEquals(OllamaWebSearch.UNAVAILABLE, detail(500))
        ollamaServer.enqueue(ok("not json", "application/json"))
        assertEquals(OllamaWebSearch.UNAVAILABLE, ollama.search("x", 1).detail())

        ollamaServer.enqueue(ok(fixture("web_fetch.json"), "application/json"))
        val page = (ollama.fetch("https://ollama.com") as Outcome.Success).value
        repeat(5) { ollamaServer.takeRequest() }   // the five searches above
        assertEquals("/api/web_fetch", ollamaServer.takeRequest().url.encodedPath)
        assertTrue(page.title.isNotBlank() && page.content.length > 100)
    }

    @Test fun `the key never reaches the logs`() = runTest {
        setup.save(key)
        listOf(401, 429, 500).forEach { ollamaServer.enqueue(status(it)); ollama.search("x", 1) }
        ollamaServer.close()
        ollama.search("x", 1)   // network failure
        val log = LogRingBuffer.tail(LogRingBuffer.CAPACITY).joinToString("\n")
        assertFalse("key in log: $log", log.contains(key))
        assertTrue(log.contains("HTTP 401"))   // the failures were logged, just without the key
    }

    @Test fun `the key is stored in SecretStore and a blank one removes it`() = runTest {
        setup.save("  $key ")
        assertEquals(key, secrets.get("ollama_api_key"))
        assertTrue(setup.keySaved.value)
        setup.save("  ")
        assertNull(secrets.get("ollama_api_key"))
        assertFalse(setup.keySaved.value)
    }

    // The tools

    @Test fun `search_web lists labelled, numbered results within 1500 characters`() = runTest {
        val tool = SearchWebTool(search)
        web.enqueue(ok(fixture("ddg_html.html")))
        val text = (tool.execute(args(tool, """{"query":"Pistons"}"""), ctx) as ToolResult.Ok).text
        assertTrue(text, text.startsWith("$UNTRUSTED\n1. ") && text.contains(" — ") && text.contains("(https://www.nba.com/") &&
            text.endsWith("\n" + SearchWebTool.READ_IF_NEEDED))
        assertTrue(text.length <= 1_500)

        val long = (1..10).map { SearchHit("Title $it", "https://example.com/$it", "word ".repeat(200)) }
        val formatted = "$UNTRUSTED\n" + tool.format(long) + "\n" + SearchWebTool.READ_IF_NEEDED
        assertTrue("≤ 1500, was ${formatted.length}", formatted.length <= 1_500)
        assertTrue("every hit fits", formatted.contains("10. Title 10") && formatted.contains("https://example.com/10"))

        repeat(2) { web.enqueue(ok("<html><body></body></html>")) }
        assertEquals(ToolResult.Ok("No results for \"zzz\"."), tool.execute(args(tool, """{"q":"zzz"}"""), ctx))
        repeat(2) { web.enqueue(status(202, fixture("ddg_challenge.html"))) }
        assertEquals(ToolResult.Error(DuckDuckGoSearch.BLOCKED), tool.execute(args(tool, """{"query":"x"}"""), ctx))
    }

    @Test fun `pages are read on the phone as clean text`() = runTest {
        val html = """
            <html><head><title>Pistons beat Bulls</title><style>p { color: red }</style></head><body>
            <nav><a href="/">Home</a> <a href="/nba">NBA</a></nav>
            <div>Sidebar junk outside the article</div>
            <article><h1>Pistons 112, Bulls 104</h1>
              <p>Cade   Cunningham scored
                 31 points.</p><script>track()</script>
              <ul><li>Next game: Friday</li><li>Record: 5-2</li></ul></article>
            <footer>© 2026</footer></body></html>
        """.trimIndent()
        web.enqueue(ok(html))
        val tool = ReadWebPageTool(search.pages(reader))
        val url = web.url("/game").toString()
        assertEquals(
            ToolResult.Ok("$UNTRUSTED\nPistons beat Bulls ($url)\n\nPistons 112, Bulls 104\nCade Cunningham scored 31 points.\nNext game: Friday\nRecord: 5-2"),
            tool.execute(args(tool, """{"url":"$url"}"""), ctx),
        )
        assertEquals(DuckDuckGoSearch.USER_AGENT, web.takeRequest().headers["User-Agent"])
        assertTrue("the result isn't cut by the loop's 2,000 default", tool.maxResultChars > ReadWebPageTool.MAX_CHARS)

        web.enqueue(ok("<html><head><title>T</title></head><body><p>${"x".repeat(7_000)}</p></body></html>"))
        val big = (tool.execute(args(tool, """{"url":"${web.url("/long")}"}"""), ctx) as ToolResult.Ok).text
        assertEquals("cut at 6,000 + …", 6_000 + 1, big.substringAfter("\n\n").length)

        web.enqueue(ok("just text\n\n  more", "text/plain"))
        assertEquals(WebPage("", "just text\n\n  more"), (reader.read(web.url("/a.txt").toString()) as Outcome.Success).value)
    }

    @Test fun `files, errors and oversized pages are handled`() = runTest {
        web.enqueue(ok("%PDF-1.7", "application/pdf"))
        assertEquals("That's a application/pdf file, not a web page, so it can't be read.", reader.read(web.url("/f.pdf").toString()).detail())
        web.enqueue(status(404, "nope"))
        assertEquals("That page wasn't found (HTTP 404).", reader.read(web.url("/gone").toString()).detail())
        web.enqueue(status(403, "no"))
        assertEquals("That site refused to show the page (HTTP 403).", reader.read(web.url("/no").toString()).detail())

        web.enqueue(ok("<html><body><p>" + "y".repeat(3 * 1024 * 1024) + "</p></body></html>"))
        val page = (reader.read(web.url("/huge").toString()) as Outcome.Success).value
        assertTrue("2 MB cap, was ${page.content.length}", page.content.length <= PageReader.MAX_BYTES)
    }

    @Test fun `private addresses are refused, including through redirects and DNS`() = runTest {
        val tool = ReadWebPageTool(search.pages(reader))
        listOf("http://192.168.4.82:11434", "http://10.0.2.2/", "http://127.0.0.1", "http://localhost:8080", "http://172.20.1.1",
            "http://169.254.1.1", "http://router.local", "http://nas", "http://[::1]/", "https://[fd00::1]/", "ftp://example.com/f",
            "file:///etc/hosts",
        ).forEach { url -> assertTrue(url, tool.execute(args(tool, """{"url":"$url"}"""), ctx) is ToolResult.Error) }
        assertEquals(0, web.requestCount)

        web.enqueue(MockResponse.Builder().code(302).setHeader("Location", "http://192.168.1.1/admin").build())
        assertEquals(PageReader.PRIVATE, reader.read(web.url("/bounce").toString()).detail())
        assertEquals("only the redirect itself was fetched", 1, web.requestCount)

        val lan = PublicOnlyDns { listOf(InetAddress.getByName("93.184.216.34"), InetAddress.getByName("192.168.1.5")) }
        assertTrue(runCatching { lan.lookup("sneaky.example") }.exceptionOrNull() is PrivateAddressException)
        assertEquals(1, PublicOnlyDns { listOf(InetAddress.getByName("93.184.216.34")) }.lookup("example.com").size)
        listOf("10.1.2.3", "127.0.0.1", "100.64.0.1", "169.254.9.9", "172.31.0.1", "fd00::1", "fe80::1", "::1", "::ffff:10.0.0.1", "224.0.0.1")
            .forEach { assertTrue(it, PrivateNetwork.isPrivateAddress(InetAddress.getByName(it))) }
        listOf("8.8.8.8", "172.15.0.1", "2606:4700::1111").forEach { assertFalse(it, PrivateNetwork.isPrivateAddress(InetAddress.getByName(it))) }
        assertFalse(PrivateNetwork.isPrivateHost("nba.com"))
        assertFalse(PrivateNetwork.isPrivateHost("172.15.0.1"))
        assertEquals("Reading nba.com…", tool.statusLabelFor(args(tool, """{"url":"https://www.nba.com/game"}""")))
    }

    @Test fun `with a key, Ollama's fetch backs up a page the phone couldn't read, but never a refused address`() = runTest {
        val tool = ReadWebPageTool(search.pages(reader))
        web.enqueue(status(403, "no"))
        assertEquals(ToolResult.Error("That site refused to show the page (HTTP 403)."),
            tool.execute(args(tool, """{"url":"${web.url("/blocked")}"}"""), ctx))
        assertEquals("no key, no Ollama", 0, ollamaServer.requestCount)

        setup.save(key)
        web.enqueue(status(403, "no"))
        ollamaServer.enqueue(ok("""{"title":"Via Ollama","content":"Line one\nLine two"}""", "application/json"))
        assertEquals(ToolResult.Ok("$UNTRUSTED\nVia Ollama (${web.url("/blocked")})\n\nLine one\nLine two"),
            tool.execute(args(tool, """{"url":"${web.url("/blocked")}"}"""), ctx))

        web.enqueue(MockResponse.Builder().code(302).setHeader("Location", "http://10.0.0.1/").build())
        assertEquals(PageReader.PRIVATE, search.pages(reader).read(web.url("/bounce").toString()).detail())
        assertEquals("a refused address never goes to Ollama", 1, ollamaServer.requestCount)
    }

    @Test fun `web tools are always offered to a tool-capable model`() = runTest {
        val registry = ToolRegistry(listOf(SearchWebTool(search), ReadWebPageTool(search.pages(reader))))
        val model = ModelInfo("gemma4:12b-it-q8_0", setOf("completion", "tools"))
        val prompt = ToolGuidanceSection { registry }.text(PromptContext(kotlin.time.Instant.parse("2026-11-01T16:00:00Z"), model))!!
        assertEquals(listOf("search_web", "read_web_page"), registry.available(model).map { it.spec.name })
        assertTrue(prompt, prompt.contains("search_web (") && prompt.contains("search first; read a page"))
        assertFalse(prompt.contains("isn't set up"))
    }
}
