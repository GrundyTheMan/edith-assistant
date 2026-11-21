package spike

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OllamaChatTest {
    private val reply = listOf(
        """{"model":"m","message":{"role":"assistant","content":"Hel"},"done":false}""",
        """{"model":"m","message":{"role":"assistant","content":"lo"},"done":false}""",
        """{"model":"m","message":{"role":"assistant","content":""},"done":true}""",
    ).joinToString("\n") + "\n"

    private fun chat(body: String, status: HttpStatusCode = HttpStatusCode.OK, seen: MutableList<String> = mutableListOf()) = OllamaChat(
        HttpClient(
            MockEngine { request ->
                seen += request.url.toString() + " " + ((request.body as? TextContent)?.text ?: "")
                respond(ByteReadChannel(body), status, headersOf(HttpHeaders.ContentType, "application/x-ndjson"))
            },
        ),
    )

    @Test
    fun streamsTheReplyPieceByPiece() = runTest {
        val seen = mutableListOf<String>()
        val pieces = chat(reply, seen = seen).stream("http://pc:11434/", "m", "hi").toList()
        assertEquals(listOf("Hel", "lo"), pieces)
        assertTrue(seen.single().startsWith("http://pc:11434/api/chat "))
        assertTrue(seen.single().contains("\"stream\":true"))
        assertTrue(seen.single().contains("\"content\":\"hi\""))
    }

    @Test
    fun aServerErrorIsToldInWords() = runTest {
        val e = assertFailsWith<ChatException> { chat("{}", HttpStatusCode.NotFound).stream("http://pc:11434", "m", "hi").toList() }
        assertEquals("The server answered HTTP 404.", e.message)
    }

    @Test
    fun anErrorLineBecomesAnException() = runTest {
        val e = assertFailsWith<ChatException> { chat("""{"error":"model 'x' not found"}""" + "\n").stream("http://pc", "x", "hi").toList() }
        assertEquals("model 'x' not found", e.message)
    }

    @Test
    fun linesAreReadAsTheyAre() {
        assertNull(parseChatLine(""))
        assertNull(parseChatLine("   "))
        assertNull(parseChatLine("not json"))
        assertEquals(ChatChunk("a", false), parseChatLine("""{"message":{"content":"a"}}"""))
        assertEquals(ChatChunk("", true), parseChatLine("""{"done":true}"""))
    }
}
