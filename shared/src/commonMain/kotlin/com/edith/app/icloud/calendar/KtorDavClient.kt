package com.edith.app.icloud.calendar

import com.edith.app.core.dispatchers.AppDispatchers
import com.edith.app.core.log.Log
import com.edith.app.core.log.LogTag
import com.fleeksoft.ksoup.Ksoup
import com.fleeksoft.ksoup.nodes.Element
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpRedirect
import io.ktor.client.plugins.api.createClientPlugin
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.URLProtocol
import io.ktor.http.Url
import io.ktor.http.content.TextContent
import io.ktor.http.isSuccess
import io.ktor.http.withCharset
import io.ktor.utils.io.charsets.Charsets
import kotlinx.coroutines.withContext
import kotlinx.io.IOException
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.time.Instant

/**
 * iCloud's calendars over CalDAV (RFC 4791, 6638) on Ktor, for the iPhone (K4.8; Android's [DavClient] is `CalDavClient` over OkHttp): HTTP Basic with
 * the address and the app-specific password, over TLS only. The password is added to every hop, redirects included, only where [carriesPassword]
 * says: [baseUrl]'s own host over its own scheme, or an `icloud.com` host over `https` (Apple redirects to the account's `pNN-caldav.icloud.com`).
 * An address Apple's answers name with `http://` never gets the password. XML is read with Ksoup's XML parser, which matches names by their local
 * part, so any namespace prefix works. Nothing sent or received is logged.
 */
@OptIn(ExperimentalEncodingApi::class)
class KtorDavClient(
    http: Lazy<HttpClient>,
    private val dispatchers: AppDispatchers,
    user: String,
    password: String,
    private val baseUrl: String = BASE_URL,
) : DavClient {
    private val base = Url(baseUrl)
    private val auth = "Basic " + Base64.encode("$user:$password".encodeToByteArray())

    /** The caller's client plus the password policy and a redirect that follows PROPFIND and REPORT too. */
    private val client: HttpClient by lazy {
        http.value.config {
            followRedirects = false
            install(HttpRedirect) { checkHttpMethod = false; allowHttpsDowngrade = false }
            install(createClientPlugin("DavAuthorization") {
                onRequest { request, _ -> if (carriesPassword(request.url.build(), base)) request.headers[HttpHeaders.Authorization] = auth }
            })
            expectSuccess = false
        }
    }

    /** Discovery (§3): the principal, its calendar home (on the partition host) and the calendars there. */
    override suspend fun discover(): DavResult<DavHome> {
        val root = base.resolve("/") ?: return DavResult.Failed("the server address can't be used")
        val principalDoc = when (val r = propfind(root, 0, PRINCIPAL_BODY)) { is DavResult.Ok -> r.value; else -> return r.map() }
        val principal = principalDoc.first.firstHref("current-user-principal")?.let { principalDoc.second.resolve(it) }
            ?: return DavResult.Failed("no current-user-principal")
        val homeDoc = when (val r = propfind(principal, 0, HOME_BODY)) { is DavResult.Ok -> r.value; else -> return r.map() }
        val home = homeDoc.first.firstHref("calendar-home-set")?.let { homeDoc.second.resolve(it) } ?: return DavResult.Failed("no calendar-home-set")
        // RFC 6638 §9.2: the default calendar is a property of the scheduling inbox, not the principal. A server that puts it on the principal is accepted too.
        val default = homeDoc.first.firstHref("schedule-default-calendar-URL")?.let { homeDoc.second.resolve(it) }
            ?: homeDoc.first.firstHref("schedule-inbox-URL")?.let { homeDoc.second.resolve(it) }?.let { inbox ->
                when (val r = propfind(inbox, 0, INBOX_BODY)) {
                    is DavResult.Ok -> r.value.first.firstHref("schedule-default-calendar-URL")?.let { r.value.second.resolve(it) }
                    else -> null   // no default named: the first writable calendar is used
                }
            }
        val listDoc = when (val r = propfind(home, 1, CALENDARS_BODY)) { is DavResult.Ok -> r.value; else -> return r.map() }
        val calendars = listDoc.first.responses().mapNotNull { resp ->
            val href = resp.child("href")?.text()?.trim()?.let { listDoc.second.resolve(it) } ?: return@mapNotNull null
            val prop = resp.okProp() ?: return@mapNotNull null
            val isCalendar = prop.child("resourcetype")?.child("calendar") != null
            val comps = prop.child("supported-calendar-component-set")?.children("comp")?.map { it.attr("name").uppercase() }
            if (!isCalendar || (comps != null && "VEVENT" !in comps)) return@mapNotNull null   // Reminders' VTODO lists
            val privileges = prop.child("current-user-privilege-set")?.children("privilege")?.flatMap { p -> p.children().map { it.local() } }
            DavCalendar(
                href = href,
                name = prop.child("displayname")?.text()?.trim()?.takeIf { it.isNotEmpty() } ?: "Calendar",
                canWrite = privileges == null || privileges.any { it == "write" || it == "write-content" || it == "all" },
                color = prop.child("calendar-color")?.text()?.trim(),
            )
        }
        Log.i(LogTag.Accounts, "CalDAV: ${calendars.size} calendar(s), default named: ${default != null}")
        return DavResult.Ok(DavHome(calendars, default))
    }

    /** `REPORT` calendar-query: `VEVENT`s in [from, to), with `<C:expand>` so a series comes back as instances. */
    override suspend fun query(calendar: Url, from: Instant, to: Instant): DavResult<List<DavResource>> =
        when (val r = send("REPORT", calendar, queryBody(from, to), depth = 1)) {
            is DavResult.Ok -> DavResult.Ok(parseResources(r.value.first, r.value.second))
            else -> r.map()
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

    /** Sends an XML request; the answer's root and the URL it finally came from (after redirects). */
    private suspend fun send(method: String, url: Url, body: String, depth: Int): DavResult<Pair<Element, Url>> = withContext(dispatchers.io) {
        execute(HttpMethod.parse(method), url, TextContent(body, XML), mapOf("Depth" to depth.toString())) { text, response -> parseXml(text) to response.call.request.url }
    }

    private suspend fun <T> execute(method: HttpMethod, url: Url, body: TextContent?, headers: Map<String, String>, read: (String, HttpResponse) -> T): DavResult<T> = try {
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
    } catch (e: IllegalArgumentException) {
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
        val href = resp.child("href")?.text()?.trim()?.let { at.resolve(it) } ?: return@mapNotNull null
        val prop = resp.okProp() ?: return@mapNotNull null
        val data = prop.child("calendar-data")?.wholeText() ?: return@mapNotNull null
        DavResource(href, prop.child("getetag")?.text()?.trim(), data)
    }

    companion object {
        const val BASE_URL = "https://caldav.icloud.com/"

        /**
         * Whether the app-specific password may be sent to [url]. [base] is where the client was pointed: in the app `https://caldav.icloud.com/`, so its
         * host is only trusted over `https`; a stand-in server's plain `http` in tests is trusted over `http`. Apple's partition hosts (`pNN-caldav.icloud.com`)
         * need `https`. The dot in `.icloud.com` keeps `evilicloud.com` out, and the user-info trick (`https://caldav.icloud.com@evil.example`) cannot pass
         * because the host is what Ktor parses, `evil.example`.
         */
        fun carriesPassword(url: Url, base: Url): Boolean {
            val host = url.host.lowercase()
            val ownOrigin = host == base.host.lowercase() && url.protocol == base.protocol
            val apple = url.protocol == URLProtocol.HTTPS && (host == "icloud.com" || host.endsWith(".icloud.com"))
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

        internal fun parseXml(text: String): Element = Ksoup.parseXml(text).children().firstOrNull() ?: throw IllegalArgumentException("empty XML")

        /** The element's name without its namespace prefix, lower case: `D:Response` is `response`. */
        private fun Element.local(): String = tagName().substringAfter(':').lowercase()
        private fun Element.children(local: String): List<Element> = children().filter { it.local() == local.lowercase() }
        private fun Element.child(local: String): Element? = children().firstOrNull { it.local() == local.lowercase() }
        private fun Element.responses(): List<Element> = children("response")

        /** The `prop` of the `propstat` whose status is 200. */
        private fun Element.okProp(): Element? =
            children("propstat").firstOrNull { it.child("status")?.text()?.contains(" 200") != false }?.child("prop")

        /** The first `href` inside the property [local] anywhere in the document's 200 propstats. */
        private fun Element.firstHref(local: String): String? = responses().firstNotNullOfOrNull { r ->
            val prop = r.okProp()?.child(local) ?: return@firstNotNullOfOrNull null
            // The RFC wraps it in <href>; iCloud gives schedule-default-calendar-URL as plain text.
            (prop.child("href")?.text() ?: prop.text()).trim().takeIf { it.startsWith("/") || it.startsWith("http") }
        }
    }
}
