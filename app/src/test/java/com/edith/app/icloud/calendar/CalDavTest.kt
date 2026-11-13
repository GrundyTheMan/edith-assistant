package com.edith.app.icloud.calendar

import com.edith.app.accounts.Account
import com.edith.app.accounts.Provider
import com.edith.app.accounts.sources.EventChange
import com.edith.app.core.error.AppError
import com.edith.app.core.error.Outcome
import com.edith.app.testing.AppDispatchersForTests
import kotlinx.coroutines.runBlocking
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.Credentials
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Collections

/**
 * C24 §3: CalDAV against a stand-in for iCloud. [main] is caldav.icloud.com; [partition] is the account's partition
 * host (`pNN-caldav.icloud.com`). Multistatus answers follow RFC 4791/6638 (made up).
 */
class CalDavTest {
    private val zone = ZoneId.of("America/Detroit")
    private val main = MockWebServer()
    private val partition = MockWebServer()
    private val requests = Collections.synchronizedList(mutableListOf<RecordedRequest>())
    private val writes get() = requests.filter { it.method in setOf("PUT", "DELETE") }
    private val auth = Credentials.basic("owner@icloud.com", "abcd-efgh-ijkl-mnop", Charsets.UTF_8)
    private val resources = mutableMapOf<String, Pair<String, String>>()   // path → (etag, ics)
    private var putAnswers = ArrayDeque<Int>()
    private val account = Account("i1", Provider.ICloud, "owner@icloud.com", "icloud")

    private fun xml(body: String, code: Int = 207) = MockResponse.Builder().code(code).body(body).addHeader("Content-Type", "application/xml; charset=utf-8").build()
    private fun at(d: Int, h: Int, m: Int = 0) = ZonedDateTime.of(2026, 11, d, h, m, 0, 0, zone)

    @Before fun setUp() {
        main.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests += request
                if (request.headers["Authorization"] != auth) return MockResponse.Builder().code(401).build()
                return when (request.url.encodedPath) {
                    "/" -> xml("""<?xml version="1.0"?><multistatus xmlns="DAV:"><response><href>/</href><propstat><prop><current-user-principal><href>/12345/principal/</href></current-user-principal></prop><status>HTTP/1.1 200 OK</status></propstat></response></multistatus>""")
                    "/12345/principal/" -> xml(
                        """<?xml version="1.0"?><d:multistatus xmlns:d="DAV:" xmlns:c="urn:ietf:params:xml:ns:caldav"><d:response><d:href>/12345/principal/</d:href>
                        <d:propstat><d:prop><c:calendar-home-set><d:href>${partition.url("/12345/calendars")}</d:href></c:calendar-home-set>
                        <c:schedule-inbox-URL><d:href>/12345/inbox/</d:href></c:schedule-inbox-URL></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response></d:multistatus>""",
                    )
                    // RFC 6638 §9.2: the scheduling inbox names the default calendar, as iCloud does.
                    "/12345/inbox/" -> xml(
                        """<d:multistatus xmlns:d="DAV:" xmlns:c="urn:ietf:params:xml:ns:caldav"><d:response><d:href>/12345/inbox/</d:href><d:propstat><d:prop><c:schedule-default-calendar-URL><d:href>${partition.url("/12345/calendars/work/")}</d:href></c:schedule-default-calendar-URL></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response></d:multistatus>""",
                    )
                    else -> MockResponse.Builder().code(404).build()
                }
            }
        }
        partition.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests += request
                if (request.headers["Authorization"] != auth) return MockResponse.Builder().code(401).build()
                val path = request.url.encodedPath
                return when {
                    path == "/12345/calendars" -> MockResponse.Builder().code(301).addHeader("Location", "/12345/calendars/").build()
                    path == "/12345/calendars/" && request.method == "PROPFIND" -> xml(CALENDARS)
                    request.method == "REPORT" && path == "/12345/calendars/home/" -> xml(report("/12345/calendars/home/"))
                    request.method == "REPORT" -> xml("""<?xml version="1.0"?><multistatus xmlns="DAV:"/>""")
                    request.method == "GET" -> resources[path]?.let { (etag, ics) ->
                        MockResponse.Builder().code(200).addHeader("ETag", etag).body(ics).build()
                    } ?: MockResponse.Builder().code(404).build()
                    request.method == "PUT" -> MockResponse.Builder().code(putAnswers.removeFirstOrNull() ?: 201).addHeader("ETag", "\"new\"").build()
                    request.method == "DELETE" -> MockResponse.Builder().code(204).build()
                    else -> MockResponse.Builder().code(404).build()
                }
            }
        }
        main.start()
        partition.start()
    }

    @After fun tearDown() {
        main.close()
        partition.close()
    }

    private fun client() = CalDavClient(lazy { OkHttpClient() }, AppDispatchersForTests.unconfined, "owner@icloud.com", "abcd-efgh-ijkl-mnop", main.url("/").toString())
    private fun source() = ICloudCalendarSource({ account }, ::client, { zone }, { Instant.parse("2026-11-02T18:00:00Z") })

    @Test fun `discovery - the principal, the home on the partition host, event calendars only`() = runBlocking {
        val home = (client().discover() as DavResult.Ok).value
        assertEquals("Reminders' VTODO list skipped, the home itself too", listOf("Home", "Work", "Holidays"), home.calendars.map { it.name })
        assertEquals(listOf(true, true, false), home.calendars.map { it.canWrite })
        assertEquals(partition.url("/12345/calendars/work/"), home.defaultHref)
        assertEquals("#FF2968FF", home.calendars[0].color)
        assertTrue("the password followed the redirect to the partition host", requests.filter { it.url.port == partition.port }.all { it.headers["Authorization"] == auth })
        val listing = requests.first { it.url.encodedPath == "/12345/calendars/" }
        assertEquals("PROPFIND", listing.method)
        assertEquals("1", listing.headers["Depth"])
    }

    @Test fun `calendars - the server's default first, and a wrong password is a 401`() = runBlocking {
        val cals = (source().calendars() as Outcome.Success).value
        assertEquals(listOf("Work", "Home", "Holidays"), cals.map { it.name })
        assertTrue(cals[0].isDefault)

        val wrong = CalDavClient(lazy { OkHttpClient() }, AppDispatchersForTests.unconfined, "owner@icloud.com", "wrong", main.url("/").toString())
        assertEquals(DavResult.Unauthorized, wrong.discover())
    }

    @Test fun `the calendar-query asks for VEVENTs in the range, expanded`() = runBlocking {
        val body = CalDavClient.queryBody(Instant.parse("2026-11-03T05:00:00Z"), Instant.parse("2026-11-10T05:00:00Z"))
        assertTrue(body, body.contains("""<c:expand start="20261103T050000Z" end="20261110T050000Z"/>"""))
        assertTrue(body.contains("""<c:comp-filter name="VCALENDAR"><c:comp-filter name="VEVENT"><c:time-range start="20261103T050000Z" end="20261110T050000Z"/>"""))
        assertTrue(body.contains("<d:getetag/>"))
    }

    @Test fun `events - instances of a series, an all-day one, cancelled left out, labelled by calendar`() = runBlocking {
        val events = (source().events(at(3, 0).toInstant(), at(10, 0).toInstant()) as Outcome.Success).value
        assertEquals(listOf("Standup", "Dentist", "Standup", "Holiday"), events.map { it.title })
        val dentist = events[1]
        assertEquals(at(3, 15), dentist.start)
        assertEquals("Home", dentist.calendarName)
        assertEquals("i1", dentist.accountId)
        assertEquals("uid-dentist", dentist.iCalUid)
        assertEquals(partition.url("/12345/calendars/home/dentist.ics").toString(), dentist.id)
        assertTrue("an instance is marked", events[0].id.endsWith("#20261103T140000Z"))
        assertTrue(events[3].allDay)
        assertEquals(at(5, 0), events[3].start)
        val report = requests.first { it.method == "REPORT" }
        assertEquals("1", report.headers["Depth"])
        assertTrue(report.body!!.utf8().contains("<c:expand"))
    }

    @Test fun `create - a PUT with If-None-Match to the default calendar, timed in UTC`() = runBlocking {
        val e = (source().create(null, EventChange("Test", at(3, 15), at(3, 16))) as Outcome.Success).value
        val put = writes.single()
        assertEquals("*", put.headers["If-None-Match"])
        assertNull(put.headers["If-Match"])
        assertTrue(put.url.encodedPath, put.url.encodedPath.startsWith("/12345/calendars/work/") && put.url.encodedPath.endsWith(".ics"))
        assertTrue(put.headers["Content-Type"]!!.startsWith("text/calendar"))
        val ics = put.body!!.utf8()
        assertTrue(ics, ics.contains("DTSTART:20261103T200000Z\r\n") && ics.contains("DTEND:20261103T210000Z\r\n") && ics.contains("SUMMARY:Test\r\n"))
        assertEquals("Work", e.calendarName)
    }

    @Test fun `update - GET, then PUT with If-Match - a 412 reads again once, twice says it changed`() = runBlocking {
        resources["/12345/calendars/home/dentist.ics"] = "\"v1\"" to report("").let { DENTIST }
        val s = source()
        val event = (s.events(at(3, 0).toInstant(), at(10, 0).toInstant()) as Outcome.Success).value.first { it.title == "Dentist" }

        putAnswers = ArrayDeque(listOf(412, 204))
        val renamed = (s.update(event, EventChange(title = "Dentist (moved)")) as Outcome.Success).value
        assertEquals("Dentist (moved)", renamed.title)
        val puts = writes.filter { it.method == "PUT" }
        assertEquals(listOf("\"v1\"", "\"v1\""), puts.map { it.headers["If-Match"] })
        assertTrue(puts.last().body!!.utf8().contains("SUMMARY:Dentist (moved)\r\n"))
        assertTrue("the rest kept", puts.last().body!!.utf8().contains("LOCATION:Main St\r\n"))

        requests.clear()
        putAnswers = ArrayDeque(listOf(412, 412))
        assertEquals(Outcome.Failure(AppError.ToolFailed("calendar", ICloudCalendarSource.CHANGED)), s.update(event, EventChange(title = "x")))
        assertEquals(2, writes.size)
    }

    @Test fun `delete uses If-Match, and one occurrence of a series is refused without a request`() = runBlocking {
        resources["/12345/calendars/home/dentist.ics"] = "\"v1\"" to DENTIST
        val s = source()
        val events = (s.events(at(3, 0).toInstant(), at(10, 0).toInstant()) as Outcome.Success).value
        assertEquals(Outcome.Success(Unit), s.delete(events.first { it.title == "Dentist" }))
        assertEquals("\"v1\"", writes.single().headers["If-Match"])

        requests.clear()
        val r = s.delete(events.first { it.title == "Standup" })
        assertEquals(Outcome.Failure(AppError.ToolFailed("calendar", ICloudCalendarSource.ONE_OCCURRENCE)), r)
        assertTrue("never the whole series instead", requests.isEmpty())
        assertFalse(s.update(events.first { it.title == "Standup" }, EventChange(title = "x")) is Outcome.Success)
        assertTrue(writes.isEmpty())
    }

    companion object {
        private const val CALENDARS = """<?xml version="1.0"?>
<d:multistatus xmlns:d="DAV:" xmlns:c="urn:ietf:params:xml:ns:caldav" xmlns:a="http://apple.com/ns/ical/">
 <d:response><d:href>/12345/calendars/</d:href><d:propstat><d:prop><d:resourcetype><d:collection/></d:resourcetype></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>
 <d:response><d:href>/12345/calendars/home/</d:href><d:propstat><d:prop><d:resourcetype><d:collection/><c:calendar/></d:resourcetype><d:displayname>Home</d:displayname><a:calendar-color>#FF2968FF</a:calendar-color>
   <c:supported-calendar-component-set><c:comp name="VEVENT"/></c:supported-calendar-component-set>
   <d:current-user-privilege-set><d:privilege><d:read/></d:privilege><d:privilege><d:write/></d:privilege></d:current-user-privilege-set></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>
 <d:response><d:href>/12345/calendars/work/</d:href><d:propstat><d:prop><d:resourcetype><d:collection/><c:calendar/></d:resourcetype><d:displayname>Work</d:displayname>
   <c:supported-calendar-component-set><c:comp name="VEVENT"/></c:supported-calendar-component-set>
   <d:current-user-privilege-set><d:privilege><d:all/></d:privilege></d:current-user-privilege-set></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>
 <d:response><d:href>/12345/calendars/tasks/</d:href><d:propstat><d:prop><d:resourcetype><d:collection/><c:calendar/></d:resourcetype><d:displayname>Reminders</d:displayname>
   <c:supported-calendar-component-set><c:comp name="VTODO"/></c:supported-calendar-component-set></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>
 <d:response><d:href>/12345/calendars/holidays/</d:href><d:propstat><d:prop><d:resourcetype><d:collection/><c:calendar/></d:resourcetype><d:displayname>Holidays</d:displayname>
   <c:supported-calendar-component-set><c:comp name="VEVENT"/></c:supported-calendar-component-set>
   <d:current-user-privilege-set><d:privilege><d:read/></d:privilege></d:current-user-privilege-set></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>
</d:multistatus>"""

        private val DENTIST = "BEGIN:VCALENDAR\r\nVERSION:2.0\r\nBEGIN:VEVENT\r\nUID:uid-dentist\r\nSUMMARY:Dentist\r\nLOCATION:Main St\r\n" +
            "DTSTART;TZID=America/Detroit:20261103T150000\r\nDTEND;TZID=America/Detroit:20261103T160000\r\nEND:VEVENT\r\nEND:VCALENDAR\r\n"

        private fun data(ics: String) = ics.replace("&", "&amp;").replace("<", "&lt;")

        private fun report(base: String) = """<?xml version="1.0"?>
<d:multistatus xmlns:d="DAV:" xmlns:c="urn:ietf:params:xml:ns:caldav">
 <d:response><d:href>${base}dentist.ics</d:href><d:propstat><d:prop><d:getetag>"v1"</d:getetag><c:calendar-data>${data(DENTIST)}</c:calendar-data></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>
 <d:response><d:href>${base}standup.ics</d:href><d:propstat><d:prop><d:getetag>"s1"</d:getetag><c:calendar-data>${data(
            "BEGIN:VCALENDAR\r\nBEGIN:VEVENT\r\nUID:uid-standup\r\nRECURRENCE-ID:20261103T140000Z\r\nSUMMARY:Standup\r\nDTSTART:20261103T140000Z\r\nDTEND:20261103T141500Z\r\nEND:VEVENT\r\n" +
                "BEGIN:VEVENT\r\nUID:uid-standup\r\nRECURRENCE-ID:20261104T140000Z\r\nSUMMARY:Standup\r\nDTSTART:20261104T140000Z\r\nDTEND:20261104T141500Z\r\nEND:VEVENT\r\nEND:VCALENDAR\r\n",
        )}</c:calendar-data></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>
 <d:response><d:href>${base}holiday.ics</d:href><d:propstat><d:prop><d:getetag>"h1"</d:getetag><c:calendar-data>${data(
            "BEGIN:VCALENDAR\r\nBEGIN:VEVENT\r\nUID:uid-holiday\r\nSUMMARY:Holiday\r\nDTSTART;VALUE=DATE:20261105\r\nDTEND;VALUE=DATE:20261106\r\nEND:VEVENT\r\n" +
                "BEGIN:VEVENT\r\nUID:uid-gone\r\nSTATUS:CANCELLED\r\nSUMMARY:Gone\r\nDTSTART:20261104T170000Z\r\nDTEND:20261104T180000Z\r\nEND:VEVENT\r\nEND:VCALENDAR\r\n",
        )}</c:calendar-data></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>
</d:multistatus>"""
    }
}
