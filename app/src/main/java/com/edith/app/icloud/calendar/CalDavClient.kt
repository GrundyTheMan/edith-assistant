package com.edith.app.icloud.calendar

import com.edith.app.core.dispatchers.AppDispatchers
import com.edith.app.core.log.Log
import com.edith.app.core.log.LogTag
import kotlinx.coroutines.withContext
import okhttp3.Credentials
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.w3c.dom.Element
import org.w3c.dom.Node
import org.xml.sax.InputSource
import java.io.IOException
import java.io.StringReader
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import javax.xml.parsers.DocumentBuilderFactory

/** One calendar collection found by discovery (C24 §3). */
data class DavCalendar(val href: HttpUrl, val name: String, val canWrite: Boolean, val color: String?)

/** What discovery found: the calendars that hold events, and the default one if the server names it. */
data class DavHome(val calendars: List<DavCalendar>, val defaultHref: HttpUrl?)

/** One resource from a calendar query: where it is, its ETag, and its iCalendar text. */
data class DavResource(val href: HttpUrl, val etag: String?, val ics: String)

/** How a CalDAV request went. */
sealed interface DavResult<out T> {
    data class Ok<T>(val value: T) : DavResult<T>
    /** 401: the app-specific password stopped working. */
    data object Unauthorized : DavResult<Nothing>
    /** 412: the resource changed since it was read. */
    data object Changed : DavResult<Nothing>
    data object NotFound : DavResult<Nothing>
    data class Failed(val why: String, val network: Boolean = false) : DavResult<Nothing>
}

/**
 * iCloud's calendars over CalDAV (RFC 4791, 6638) with OkHttp and HTTP Basic (the address and the app-specific password,
 * over TLS only). The credentials are added by a network interceptor, so they follow Apple's redirect to the account's
 * partition host (`pNN-caldav.icloud.com`), and only to [baseUrl]'s host or an `icloud.com` host. Nothing it sends or
 * receives is logged.
 */
class CalDavClient(
    http: Lazy<OkHttpClient>,
    private val dispatchers: AppDispatchers,
    user: String,
    password: String,
    private val baseUrl: String = BASE_URL,
) {
    private val base = baseUrl.toHttpUrl()
    private val auth = Credentials.basic(user, password, Charsets.UTF_8)
    private val client: OkHttpClient by lazy {
        http.value.newBuilder()
            .addNetworkInterceptor { chain ->
                val host = chain.request().url.host
                val trusted = host == base.host || host == "icloud.com" || host.endsWith(".icloud.com")
                chain.proceed(if (trusted) chain.request().newBuilder().header("Authorization", auth).build() else chain.request())
            }
            .build()
    }

    /** Discovery (§3): the principal, its calendar home (on the partition host) and the calendars there. */
    suspend fun discover(): DavResult<DavHome> {
        val principalDoc = when (val r = propfind(base.resolve("/")!!, 0, PRINCIPAL_BODY)) { is DavResult.Ok -> r.value; else -> return r.map() }
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
    suspend fun query(calendar: HttpUrl, from: Instant, to: Instant): DavResult<List<DavResource>> {
        val r = send("REPORT", calendar, queryBody(from, to), depth = 1)
        return when (r) {
            is DavResult.Ok -> DavResult.Ok(parseResources(r.value.first, r.value.second))
            else -> r.map()
        }
    }

    /** One resource and its ETag. */
    suspend fun get(href: HttpUrl): DavResult<DavResource> = withContext(dispatchers.io) {
        execute(Request.Builder().url(href).get().build()) { body, response -> DavResource(href, response.header("ETag"), body) }
    }

    /** `PUT` [ics] at [href]: a new resource ([etag] null: `If-None-Match: *`) or a change (`If-Match`). Returns the new ETag. */
    suspend fun put(href: HttpUrl, ics: String, etag: String?): DavResult<String?> = withContext(dispatchers.io) {
        val builder = Request.Builder().url(href).put(ics.toRequestBody(ICS))
        if (etag == null) builder.header("If-None-Match", "*") else builder.header("If-Match", etag)
        execute(builder.build()) { _, response -> response.header("ETag") }
    }

    suspend fun delete(href: HttpUrl, etag: String?): DavResult<Unit> = withContext(dispatchers.io) {
        val builder = Request.Builder().url(href).delete()
        etag?.let { builder.header("If-Match", it) }
        execute(builder.build()) { _, _ -> }
    }

    // --- HTTP and XML -------------------------------------------------------------------------------------------

    private suspend fun propfind(url: HttpUrl, depth: Int, body: String) = send("PROPFIND", url, body, depth)

    /** Sends an XML request; the answer's document and the URL it finally came from (after redirects). */
    private suspend fun send(method: String, url: HttpUrl, body: String, depth: Int): DavResult<Pair<Element, HttpUrl>> = withContext(dispatchers.io) {
        val request = Request.Builder().url(url).method(method, body.toRequestBody(XML)).header("Depth", depth.toString()).build()
        execute(request) { text, response -> parseXml(text) to response.request.url }
    }

    private fun <T> execute(request: Request, read: (String, okhttp3.Response) -> T): DavResult<T> = try {
        client.newCall(request).execute().use { response ->
            val text = response.body.string()
            when {
                response.isSuccessful -> DavResult.Ok(read(text, response))
                response.code == 401 -> DavResult.Unauthorized
                response.code == 412 -> DavResult.Changed
                response.code == 404 || response.code == 410 -> DavResult.NotFound
                else -> {
                    Log.w(LogTag.Accounts, "CalDAV ${request.method} HTTP ${response.code}")
                    DavResult.Failed("iCloud Calendar said no (HTTP ${response.code}).")
                }
            }
        }
    } catch (e: IOException) {
        Log.w(LogTag.Accounts, "CalDAV ${request.method} didn't reach iCloud (${e::class.simpleName})")
        DavResult.Failed("Couldn't reach iCloud Calendar. Check the phone's connection.", network = true)
    } catch (e: org.xml.sax.SAXException) {
        Log.w(LogTag.Accounts, "CalDAV ${request.method}: unreadable XML")
        DavResult.Failed("iCloud Calendar sent something Edith couldn't read.")
    }

    private fun <T> DavResult<*>.map(): DavResult<T> = when (this) {
        is DavResult.Ok -> error("not a failure")
        DavResult.Unauthorized -> DavResult.Unauthorized
        DavResult.Changed -> DavResult.Changed
        DavResult.NotFound -> DavResult.NotFound
        is DavResult.Failed -> this
    }

    private fun parseResources(doc: Element, at: HttpUrl): List<DavResource> = doc.responses().mapNotNull { resp ->
        val href = resp.child("href")?.textContent?.trim()?.let { at.resolve(it) } ?: return@mapNotNull null
        val prop = resp.okProp() ?: return@mapNotNull null
        val data = prop.child("calendar-data")?.textContent ?: return@mapNotNull null
        DavResource(href, prop.child("getetag")?.textContent?.trim(), data)
    }

    companion object {
        const val BASE_URL = "https://caldav.icloud.com/"
        private val XML = "application/xml; charset=utf-8".toMediaType()
        private val ICS = "text/calendar; charset=utf-8".toMediaType()
        private val DAV_TIME = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC)

        private const val PRINCIPAL_BODY = """<?xml version="1.0" encoding="utf-8"?><d:propfind xmlns:d="DAV:"><d:prop><d:current-user-principal/></d:prop></d:propfind>"""
        private const val HOME_BODY = """<?xml version="1.0" encoding="utf-8"?><d:propfind xmlns:d="DAV:" xmlns:c="urn:ietf:params:xml:ns:caldav"><d:prop><c:calendar-home-set/><c:schedule-inbox-URL/><c:schedule-default-calendar-URL/></d:prop></d:propfind>"""
        private const val INBOX_BODY = """<?xml version="1.0" encoding="utf-8"?><d:propfind xmlns:d="DAV:" xmlns:c="urn:ietf:params:xml:ns:caldav"><d:prop><c:schedule-default-calendar-URL/></d:prop></d:propfind>"""
        private const val CALENDARS_BODY = """<?xml version="1.0" encoding="utf-8"?><d:propfind xmlns:d="DAV:" xmlns:c="urn:ietf:params:xml:ns:caldav" xmlns:a="http://apple.com/ns/ical/"><d:prop><d:resourcetype/><d:displayname/><a:calendar-color/><c:supported-calendar-component-set/><d:current-user-privilege-set/></d:prop></d:propfind>"""

        /** The calendar-query (RFC 4791 §7.8): VEVENTs overlapping [from, to), expanded into instances. */
        fun queryBody(from: Instant, to: Instant): String {
            val s = DAV_TIME.format(from)
            val e = DAV_TIME.format(to)
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
