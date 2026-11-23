package com.edith.app.printer

import com.edith.app.core.net.HttpClients
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import okio.buffer
import mockwebserver3.MockResponse
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** C34: the HTTP layer against a pretend Moonraker. */
class MoonrakerApiTest {
    private val printer = FakeMoonraker()
    private val api = printer.api()
    @After fun tearDown() = printer.shutdown()

    @Test fun `an answer's result is unwrapped`() = runBlocking {
        val r = api.get(printer.conn(), "/printer/info")
        assertTrue(r is PrinterReply.Ok)
        assertTrue((r as PrinterReply.Ok).value.toString().contains("ad5x"))
    }

    @Test fun `the API key is sent when there is one, and only then`() = runBlocking {
        api.get(printer.conn(), "/printer/info")
        assertNull(printer.requests.last().headers["X-Api-Key"])
        api.get(printer.conn("secret-key"), "/printer/info")
        assertEquals("secret-key", printer.requests.last().headers["X-Api-Key"])
        api.get(printer.conn("   "), "/printer/info")
        assertNull("a blank key is no key", printer.requests.last().headers["X-Api-Key"])
    }

    @Test fun `a printer that wants a key says so, whichever way it refuses`() = runBlocking {
        printer.requireKey = "right"
        val r = api.get(printer.conn("wrong"), "/printer/info") as PrinterReply.Failed
        assertEquals(401, r.status); assertTrue(r.why, "API key" in r.why)
        assertTrue(api.get(printer.conn("right"), "/printer/info") is PrinterReply.Ok)
    }

    @Test fun `an error message Moonraker gives is passed on`() = runBlocking {
        printer.overrides["/printer/print/pause"] = { MockResponse.Builder().code(400).body("""{"error":{"code":400,"message":"Klipper is not ready"}}""").build() }
        val r = api.post(printer.conn(), "/printer/print/pause") as PrinterReply.Failed
        assertEquals("Klipper is not ready", r.why); assertEquals(400, r.status)
    }

    @Test fun `an answer that is not Moonraker's is said so, and a bare error status gets its number`() = runBlocking {
        printer.overrides["/odd"] = { MockResponse.Builder().code(200).body("<html>hello</html>").build() }
        printer.overrides["/bare"] = { MockResponse.Builder().code(503).body("").build() }
        assertTrue((api.get(printer.conn(), "/odd") as PrinterReply.Failed).why.contains("wasn't what Moonraker sends"))
        assertEquals("The printer answered HTTP 503", (api.get(printer.conn(), "/bare") as PrinterReply.Failed).why)
    }

    @Test fun `a printer that does not answer is unreachable, not an error`() = runBlocking {
        val conn = printer.conn()
        printer.shutdown()
        assertEquals(PrinterReply.Unreachable, api.get(conn, "/printer/info"))
        assertEquals(PrinterReply.Unreachable, api.post(conn, "/printer/print/pause"))
        assertEquals(PrinterReply.Unreachable, api.image(conn, conn.urls.web + "/webcam/?action=snapshot"))
    }

    @Test fun `a picture is returned, and a page that is not a picture is not`() = runBlocking {
        val ok = api.image(printer.conn(), printer.address + "/webcam/?action=snapshot")
        assertEquals(printer.picture.toList(), (ok as PrinterReply.Ok).value.toList())
        printer.overrides["/webcam/"] = { MockResponse.Builder().code(200).body("<html/>").addHeader("Content-Type", "text/html").build() }
        assertTrue(api.image(printer.conn(), printer.address + "/webcam/?action=snapshot") is PrinterReply.Failed)
        printer.overrides["/webcam/"] = { MockResponse.Builder().code(404).build() }
        assertTrue((api.image(printer.conn(), printer.address + "/webcam/") as PrinterReply.Failed).why.contains("404"))
    }

    @Test fun `the answer is read off the thread that asked, so the main thread never reads the network`() = runBlocking {
        // Found with Alexa: Android kills the app for a network read on the main thread (NetworkOnMainThreadException).
        val readers = java.util.concurrent.CopyOnWriteArrayList<String>()
        val client = okhttp3.OkHttpClient.Builder().addNetworkInterceptor { chain ->
            val response = chain.proceed(chain.request())
            val real = response.body!!
            val watched = object : okhttp3.ResponseBody() {
                override fun contentType() = real.contentType()
                override fun contentLength() = real.contentLength()
                override fun source(): okio.BufferedSource = object : okio.ForwardingSource(real.source()) {
                    override fun read(sink: okio.Buffer, byteCount: Long): Long { readers += Thread.currentThread().name; return super.read(sink, byteCount) }
                }.buffer()
            }
            response.newBuilder().body(watched).build()
        }.build()
        val main = java.util.concurrent.Executors.newSingleThreadExecutor { Thread(it, "fake-main") }
        try {
            val asking = main.asCoroutineDispatcher()
            val reply = kotlinx.coroutines.withContext(asking) { MoonrakerApi(lazy { HttpClients.over(client) }).get(printer.conn(), "/printer/info") }
            assertTrue(reply is PrinterReply.Ok)
            assertTrue("the body was read", readers.isNotEmpty())
            assertTrue("never on the asking thread: $readers", readers.none { it.startsWith("fake-main") })
        } finally { main.shutdown() }
    }
}
