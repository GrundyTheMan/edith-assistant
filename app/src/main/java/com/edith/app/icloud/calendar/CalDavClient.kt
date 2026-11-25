package com.edith.app.icloud.calendar

import com.edith.app.core.dispatchers.AppDispatchers
import com.edith.app.core.log.Log
import com.edith.app.core.log.LogTag
import kotlinx.coroutines.withContext
import com.edith.app.core.net.HttpClients
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.URLBuilder
import io.ktor.http.Url
import io.ktor.http.content.TextContent
import io.ktor.http.isSuccess
import io.ktor.http.takeFrom
import io.ktor.http.withCharset
import okhttp3.Credentials
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.w3c.dom.Element
import org.w3c.dom.Node
import org.xml.sax.InputSource
import java.io.IOException
import java.io.StringReader
import kotlin.time.Instant
import javax.xml.parsers.DocumentBuilderFactory

/**
 * iCloud's calendars over CalDAV (RFC 4791, 6638) with Ktor, over an OkHttp client, and HTTP Basic (the address and the app-specific password,
 * over TLS only). The credentials are added by a network interceptor, so they follow Apple's redirect to the account's
 * partition host (`pNN-caldav.icloud.com`), and only where [carriesPassword] says: [baseUrl]'s own host over its own
 * scheme, or an `icloud.com` host over `https`. An address Apple's answers name with `http://` never gets the password,
 * although cleartext is allowed app-wide (01-architecture §9). Nothing it sends or receives is logged.
 */
class CalDavClient(
    http: Lazy<OkHttpClient>,
    private val dispatchers: AppDispatchers,
    user: String,
    password: String,
    private val baseUrl: String = BASE_URL,
) : DavClient {
    private val okBase = baseUrl.toHttpUrl()
    private val base = Url(baseUrl)
    private val auth = Credentials.basic(user, password, Charsets.UTF_8)
    /**
     * Ktor follows Apple's redirects itself (any method: PROPFIND and REPORT too), and every hop goes through the OkHttp engine, so
     * this network interceptor sees each one and adds the password only where [carriesPassword] says.
     */
    private val client: HttpClient by lazy {
        val okHttp = http.value.newBuilder()
            .addNetworkInterceptor { chain ->
                val trusted = carriesPassword(chain.request().url, okBase)
                chain.proceed(if (trusted) chain.request().newBuilder().header("Authorization", auth).build() else chain.request())
            }
            .build()
        HttpClients.over(okHttp, redirectAnyMethod = true)
    }

    /** Discovery (§3): the principal, its calendar home (on the partition host) and the calendars there. */
    override suspend fun discover(): DavResult<DavHome> {
        val root = base.resolve("/") ?: return DavResult.Failed("the server address can't be used")
        val principalDoc = when (val r = propfind(root, 0, PRINCIPAL_BODY)) { is DavResult.Ok -> r.value; else -> return r.map() }
        val principal = principalDoc.first.firstHref("current-user-principal")?.let { principalDoc.second.resolve(it) }
            ?: return DavResult.Failed("no current-user-principal")
        val homeDoc = when (val r = propfind(principal, 0, HOME_BODY)) { is DavResult.Ok -> r.value; else -> return r.map() }
        val home = homeDoc.first.firstHref("calendar-home-set")?.let { homeDoc.second.resolve(it) } ?: return DavResult.Failed("no calendar-home-set")
        // RFC 6638 §9.2: the default calendar is a property of the scheduling inbox, not the principal. A server that
        // puts it on the principal is accepted too.
        val default = homeDoc.first.firstHref("schedule-default-calendar-URL")?.let { homeDoc.second.resolve(it) }
            ?: homeDoc.first.firstHref("schedule-inbox-URL")?.let { homeDoc.second.resolve(it) }?.let { inbox ->
                when (val r = propfind(inbox, 0, INBOX_BODY)) {
                    is DavResult.Ok -> r.value.first.firstHref("schedule-default-calendar-URL")?.let { r.value.second.resolve(it) }
                    else -> null   // no default named: the first writable calendar is used
                }
            }
        val listDoc = when (val r = propfind(home, 1, CALENDARS_BODY)) { is DavResult.Ok -> r.value; else -> return r.map() }
        val calendars = listDoc.first.responses().mapNotNull { resp ->
            val href = resp.child("href")?.textContent?.trim()?.let { listDoc.second.resolve(it) } ?: return@mapNotNull null
            val prop = resp.okProp() ?: return@mapNotNull null
            val isCalendar = prop.child("resourcetype")?.child("calendar") != null
            val comps = prop.child("supported-calendar-component-set")?.children("comp")?.map { it.getAttribute("name").uppercase() }
            if (!isCalendar || (comps != null && "VEVENT" !in comps)) return@mapNotNull null   // Reminders' VTODO lists
            val privileges = prop.child("current-user-privilege-set")?.children("privilege")?.flatMap { p -> p.elements().map { it.localName } }
            DavCalendar(
                href = href,
                name = prop.child("displayname")?.textContent?.trim()?.takeIf { it.isNotEmpty() } ?: "Calendar",
                canWrite = privileges == null || privileges.any { it == "write" || it == "write-content" || it == "all" },
                color = prop.child("calendar-color")?.textContent?.trim(),
            )
        }
        Log.i(LogTag.Accounts, "CalDAV: ${calendars.size} calendar(s), default named: ${default != null}")
        return DavResult.Ok(DavHome(calendars, default))
    }

    /** `REPORT` calendar-query: `VEVENT`s in [from, to), with `<C:expand>` so a series comes back as instances. */
    override suspend fun query(calendar: Url, from: Instant, to: Instant): DavResult<List<DavResource>> {
        val r = send("REPORT", calendar, queryBody(from, to), depth = 1)
        return when (r) {
            is DavResult.Ok -> DavResult.Ok(parseResources(r.value.first, r.value.second))
            else -> r.map()
        }
    }

    /** One resource and its ETag. */
    override suspend fun get(href: Url): DavResult<DavResource> = withContext(dispatchers.io) {
        execute(HttpMethod.Get, href, null, emptyMap()) { body, response -> DavResource(href, response.headers["ETag"], body) }
    }

    /** `PUT` [ics] at [href]: a new resource ([etag] null: `If-None-Match: *`) or a change (`If-Match`). Returns the new ETag. */
    override suspend fun put(href: Url, ics: String, etag: String?): DavResult<String?> = withContext(dispatchers.io) {
        val condition = if (etag == null) "If-None-Match" to "*" else "If-Match" to etag
        execute(HttpMethod.Put, href, TextContent(ics, ICS), mapOf(condition)) { _, response -> response.headers["ETag"] }
    }

    override suspend fun delete(href: Url, etag: String?): DavResult<Unit> = withContext(dispatchers.io) {
        execute(HttpMethod.Delete, href, null, listOfNotNull(etag?.let { "If-Match" to it }).toMap()) { _, _ -> }
    }

    // --- HTTP and XML -------------------------------------------------------------------------------------------

    private suspend fun propfind(url: Url, depth: Int, body: String) = send("PROPFIND", url, body, depth)

    /** Sends an XML request; the answer's document and the URL it finally came from (after redirects). */
    private suspend fun send(method: String, url: Url, body: String, depth: Int): DavResult<Pair<Element, Url>> = withContext(dispatchers.io) {
        execute(HttpMethod.parse(method), url, TextContent(body, XML), mapOf("Depth" to depth.toString())) { text, response -> parseXml(text) to response.call.request.url }
    }

    private suspend fun <T> execute(method: HttpMethod, url: Url, body: TextContent?, headers: Map<String, String>, read: (String, io.ktor.client.statement.HttpResponse) -> T): DavResult<T> = try {
        val response = client.request(url) {
            this.method = method
            headers.forEach { (k, v) -> header(k, v) }
            if (body != null) setBody(body)
        }
        val text = response.bodyAsText()
        val code = response.status.value
        when {
            response.status.isSuccess() -> DavResult.Ok(read(text, response))
            code == 401 -> DavResult.Unauthorized
            code == 412 -> DavResult.Changed
            code == 404 || code == 410 -> DavResult.NotFound
            else -> {
                Log.w(LogTag.Accounts, "CalDAV ${method.value} HTTP $code")
                DavResult.Failed("iCloud Calendar said no (HTTP $code).")
            }
        }
    } catch (e: IOException) {
        Log.w(LogTag.Accounts, "CalDAV ${method.value} didn't reach iCloud (${e::class.simpleName})")
        DavResult.Failed("Couldn't reach iCloud Calendar. Check the phone's connection.", network = true)
    } catch (e: org.xml.sax.SAXException) {
        Log.w(LogTag.Accounts, "CalDAV ${method.value}: unreadable XML")
        DavResult.Failed("iCloud Calendar sent something Edith couldn't read.")
    }

    private fun <T> DavResult<*>.map(): DavResult<T> = when (this) {
        is DavResult.Ok -> error("not a failure")
        DavResult.Unauthorized -> DavResult.Unauthorized
        DavResult.Changed -> DavResult.Changed
        DavResult.NotFound -> DavResult.NotFound
        is DavResult.Failed -> this
    }

    private fun parseResources(doc: Element, at: Url): List<DavResource> = doc.responses().mapNotNull { resp ->
        val href = resp.child("href")?.textContent?.trim()?.let { at.resolve(it) } ?: return@mapNotNull null
        val prop = resp.okProp() ?: return@mapNotNull null
        val data = prop.child("calendar-data")?.textContent ?: return@mapNotNull null
        DavResource(href, prop.child("getetag")?.textContent?.trim(), data)
    }

    companion object {
        const val BASE_URL = "https://caldav.icloud.com/"

        /**
         * Whether the app-specific password may be sent to [url]. [base] is where the client was pointed: in the app
         * `https://caldav.icloud.com/`, so its host is only trusted over `https`; a stand-in server's plain `http` in
         * tests is trusted over `http`. Apple's partition hosts (`pNN-caldav.icloud.com`) need `https`. The dot in
         * `.icloud.com` keeps `evilicloud.com` out; the user-info trick (`https://caldav.icloud.com@evil.example`)
         * can't pass, because [HttpUrl.host] is `evil.example`.
         */
        internal fun carriesPassword(url: okhttp3.HttpUrl, base: okhttp3.HttpUrl): Boolean {
            val ownOrigin = url.host == base.host && url.scheme == base.scheme
            val apple = url.isHttps && (url.host == "icloud.com" || url.host.endsWith(".icloud.com"))
            return ownOrigin || apple
        }

        private val XML = ContentType.Application.Xml.withCharset(Charsets.UTF_8)
        private val ICS = ContentType.parse("text/calendar").withCharset(Charsets.UTF_8)

        private const val PRINCIPAL_BODY = """<?xml version="1.0" encoding="utf-8"?><d:propfind xmlns:d="DAV:"><d:prop><d:current-user-principal/></d:prop></d:propfind>"""
        private const val HOME_BODY = """<?xml version="1.0" encoding="utf-8"?><d:propfind xmlns:d="DAV:" xmlns:c="urn:ietf:params:xml:ns:caldav"><d:prop><c:calendar-home-set/><c:schedule-inbox-URL/><c:schedule-default-calendar-URL/></d:prop></d:propfind>"""
        private const val INBOX_BODY = """<?xml version="1.0" encoding="utf-8"?><d:propfind xmlns:d="DAV:" xmlns:c="urn:ietf:params:xml:ns:caldav"><d:prop><c:schedule-default-calendar-URL/></d:prop></d:propfind>"""
        private const val CALENDARS_BODY = """<?xml version="1.0" encoding="utf-8"?><d:propfind xmlns:d="DAV:" xmlns:c="urn:ietf:params:xml:ns:caldav" xmlns:a="http://apple.com/ns/ical/"><d:prop><d:resourcetype/><d:displayname/><a:calendar-color/><c:supported-calendar-component-set/><d:current-user-privilege-set/></d:prop></d:propfind>"""

        /** The calendar-query (RFC 4791 §7.8): VEVENTs overlapping [from, to), expanded into instances. */
        fun queryBody(from: Instant, to: Instant): String {
            val s = ICalendar.utc(from)
            val e = ICalendar.utc(to)
            return """<?xml version="1.0" encoding="utf-8"?><c:calendar-query xmlns:d="DAV:" xmlns:c="urn:ietf:params:xml:ns:caldav">""" +
                """<d:prop><d:getetag/><c:calendar-data><c:expand start="$s" end="$e"/></c:calendar-data></d:prop>""" +
                """<c:filter><c:comp-filter name="VCALENDAR"><c:comp-filter name="VEVENT"><c:time-range start="$s" end="$e"/></c:comp-filter></c:comp-filter></c:filter>""" +
                """</c:calendar-query>"""
        }

        internal fun parseXml(text: String): Element {
            val factory = DocumentBuilderFactory.newInstance().apply {
                isNamespaceAware = true
                // No external entities or DTDs: the answer comes from the network.
                runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
                isExpandEntityReferences = false
            }
            return factory.newDocumentBuilder().parse(InputSource(StringReader(text))).documentElement
        }

        private fun Node.elements(): List<Element> = (0 until childNodes.length).map { childNodes.item(it) }.filterIsInstance<Element>()
        private fun Element.child(local: String): Element? = elements().firstOrNull { it.localName == local }
        private fun Element.children(local: String): List<Element> = elements().filter { it.localName == local }
        private fun Element.responses(): List<Element> = children("response")

        /** The `prop` of the `propstat` whose status is 200. */
        private fun Element.okProp(): Element? =
            children("propstat").firstOrNull { it.child("status")?.textContent?.contains(" 200") != false }?.child("prop")

        /** The first `href` inside the property [local] anywhere in the document's 200 propstats. */
        private fun Element.firstHref(local: String): String? = responses().firstNotNullOfOrNull { r ->
            val prop = r.okProp()?.child(local) ?: return@firstNotNullOfOrNull null
            // The RFC wraps it in <href>; iCloud gives schedule-default-calendar-URL as plain text.
            (prop.child("href")?.textContent ?: prop.textContent)?.trim()?.takeIf { it.startsWith("/") || it.startsWith("http") }
        }
    }
}
