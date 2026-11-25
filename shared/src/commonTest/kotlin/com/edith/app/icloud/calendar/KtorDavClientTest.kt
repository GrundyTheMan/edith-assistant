package com.edith.app.icloud.calendar

import com.edith.app.testing.assertEquals
import com.edith.app.testing.assertFalse
import com.edith.app.testing.assertTrue
import com.edith.app.testing.testDispatchers
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.Test
import kotlin.time.Instant

/** K4.8: the iPhone's CalDAV client against a pretend iCloud: the principal on `caldav.icloud.com`, the calendars on a partition host. */
@OptIn(ExperimentalEncodingApi::class)
class KtorDavClientTest {
    private val seen = mutableListOf<HttpRequestData>()
    private val auth = "Basic " + Base64.encode("owner@icloud.com:abcd-efgh-ijkl-mnop".encodeToByteArray())

    private fun MockRequestHandleScope.xml(body: String, code: HttpStatusCode = HttpStatusCode.MultiStatus) =
        respond(body, code, headersOf(HttpHeaders.ContentType, "application/xml; charset=utf-8"))

    private fun handle(scope: MockRequestHandleScope, request: HttpRequestData): HttpResponseData = with(scope) {
        seen += request
        val host = request.url.host
        val path = request.url.encodedPath
        if (request.headers[HttpHeaders.Authorization] != auth) return respond("", HttpStatusCode.Unauthorized)
        when {
            host == "caldav.icloud.com" && path == "/" -> xml(
                """<?xml version="1.0"?><multistatus xmlns="DAV:"><response><href>/</href><propstat><prop><current-user-principal><href>/12345/principal/</href></current-user-principal></prop><status>HTTP/1.1 200 OK</status></propstat></response></multistatus>""",
            )
            host == "caldav.icloud.com" && path == "/12345/principal/" -> xml(
                """<?xml version="1.0"?><d:multistatus xmlns:d="DAV:" xmlns:c="urn:ietf:params:xml:ns:caldav"><d:response><d:href>/12345/principal/</d:href>
                <d:propstat><d:prop><c:calendar-home-set><d:href>https://p01-caldav.icloud.com/12345/calendars</d:href></c:calendar-home-set>
                <c:schedule-inbox-URL><d:href>/12345/inbox/</d:href></c:schedule-inbox-URL></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response></d:multistatus>""",
            )
            host == "caldav.icloud.com" && path == "/12345/inbox/" -> xml(
                """<d:multistatus xmlns:d="DAV:" xmlns:c="urn:ietf:params:xml:ns:caldav"><d:response><d:href>/12345/inbox/</d:href><d:propstat><d:prop><c:schedule-default-calendar-URL><d:href>https://p01-caldav.icloud.com/12345/calendars/work/</d:href></c:schedule-default-calendar-URL></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response></d:multistatus>""",
            )
            host == "p01-caldav.icloud.com" && path == "/12345/calendars" -> respond("", HttpStatusCode.MovedPermanently, headersOf(HttpHeaders.Location, "/12345/calendars/"))
            host == "p01-caldav.icloud.com" && path == "/12345/calendars/" -> xml(CALENDARS)
            host == "p01-caldav.icloud.com" && request.method.value == "REPORT" -> xml(REPORT)
            else -> respond("", HttpStatusCode.NotFound)
        }
    }

    private fun client(password: String = "abcd-efgh-ijkl-mnop"): KtorDavClient {
        val engine = MockEngine { request -> handle(this, request) }
        return KtorDavClient(lazy { HttpClient(engine) }, testDispatchers(), "owner@icloud.com", password)
    }

    @Test fun discoveryFindsTheEventCalendarsOnThePartitionHost() = runTest {
        val home = (client().discover() as DavResult.Ok).value
        assertEquals(listOf("Home", "Work", "Holidays"), home.calendars.map { it.name })   // Reminders' VTODO list and the home itself left out
        assertEquals(listOf(true, true, false), home.calendars.map { it.canWrite })
        assertEquals(Url("https://p01-caldav.icloud.com/12345/calendars/work/"), home.defaultHref)
        assertEquals("#FF2968FF", home.calendars[0].color)
        assertTrue("every request carried the password", seen.all { it.headers[HttpHeaders.Authorization] == auth })
        assertTrue("the redirect was followed for PROPFIND", seen.any { it.url.encodedPath == "/12345/calendars/" })
    }

    @Test fun aWrongPasswordIsUnauthorized() = runTest {
        assertEquals(DavResult.Unauthorized, client("wrong").discover())
    }

    @Test fun theQueryReturnsEachEventWithItsEtagAndTextIntact() = runTest {
        val r = client().query(Url("https://p01-caldav.icloud.com/12345/calendars/home/"), Instant.parse("2026-11-03T05:00:00Z"), Instant.parse("2026-11-10T05:00:00Z"))
        val events = (r as DavResult.Ok).value
        assertEquals(1, events.size)
        assertEquals("\"etag-1\"", events[0].etag)
        assertEquals(Url("https://p01-caldav.icloud.com/12345/calendars/home/dentist.ics"), events[0].href)
        assertTrue("the lines of the iCalendar kept their breaks", events[0].ics.contains("SUMMARY:Dentist\n") || events[0].ics.contains("SUMMARY:Dentist\r\n"))
    }

    @Test fun thePasswordGoesOnlyToApplesOwnHosts() {
        val base = Url("https://caldav.icloud.com/")
        assertTrue(KtorDavClient.carriesPassword(Url("https://caldav.icloud.com/x"), base))
        assertTrue(KtorDavClient.carriesPassword(Url("https://p42-caldav.icloud.com/x"), base))
        assertFalse(KtorDavClient.carriesPassword(Url("http://p42-caldav.icloud.com/x"), base))
        assertFalse(KtorDavClient.carriesPassword(Url("https://evilicloud.com/x"), base))
        assertFalse(KtorDavClient.carriesPassword(Url("https://caldav.icloud.com@evil.example/x"), base))
        assertFalse(KtorDavClient.carriesPassword(Url("https://evil.example/caldav.icloud.com"), base))
    }

    private companion object {
        const val CALENDARS = """<?xml version="1.0"?><d:multistatus xmlns:d="DAV:" xmlns:c="urn:ietf:params:xml:ns:caldav" xmlns:a="http://apple.com/ns/ical/">
          <d:response><d:href>/12345/calendars/</d:href><d:propstat><d:prop><d:resourcetype><d:collection/></d:resourcetype></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>
          <d:response><d:href>/12345/calendars/home/</d:href><d:propstat><d:prop><d:resourcetype><d:collection/><c:calendar/></d:resourcetype><d:displayname>Home</d:displayname><a:calendar-color>#FF2968FF</a:calendar-color>
            <c:supported-calendar-component-set><c:comp name="VEVENT"/></c:supported-calendar-component-set><d:current-user-privilege-set><d:privilege><d:write/></d:privilege></d:current-user-privilege-set></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>
          <d:response><d:href>/12345/calendars/work/</d:href><d:propstat><d:prop><d:resourcetype><d:collection/><c:calendar/></d:resourcetype><d:displayname>Work</d:displayname>
            <c:supported-calendar-component-set><c:comp name="VEVENT"/></c:supported-calendar-component-set><d:current-user-privilege-set><d:privilege><d:all/></d:privilege></d:current-user-privilege-set></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>
          <d:response><d:href>/12345/calendars/reminders/</d:href><d:propstat><d:prop><d:resourcetype><d:collection/><c:calendar/></d:resourcetype><d:displayname>Reminders</d:displayname>
            <c:supported-calendar-component-set><c:comp name="VTODO"/></c:supported-calendar-component-set></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>
          <d:response><d:href>/12345/calendars/holidays/</d:href><d:propstat><d:prop><d:resourcetype><d:collection/><c:calendar/></d:resourcetype><d:displayname>Holidays</d:displayname>
            <c:supported-calendar-component-set><c:comp name="VEVENT"/></c:supported-calendar-component-set><d:current-user-privilege-set><d:privilege><d:read/></d:privilege></d:current-user-privilege-set></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>
        </d:multistatus>"""

        val REPORT = "<?xml version=\"1.0\"?><multistatus xmlns=\"DAV:\" xmlns:c=\"urn:ietf:params:xml:ns:caldav\"><response><href>/12345/calendars/home/dentist.ics</href><propstat><prop><getetag>\"etag-1\"</getetag>" +
            "<c:calendar-data>BEGIN:VCALENDAR\nVERSION:2.0\nBEGIN:VEVENT\nUID:uid-dentist\nSUMMARY:Dentist\nDTSTART:20261103T201500Z\nEND:VEVENT\nEND:VCALENDAR\n</c:calendar-data></prop><status>HTTP/1.1 200 OK</status></propstat></response></multistatus>"
    }
}
