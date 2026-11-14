package com.edith.app.voice.own

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import mockwebserver3.SocketEffect
import okhttp3.OkHttpClient
import okio.Buffer
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.archivers.tar.TarConstants
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorOutputStream
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.CopyOnWriteArrayList

/** C36 §3: the voice download against MockWebServer, with a made-up voice (a tiny `.tar.bz2` of the same shape). */
class VoiceDownloaderTest {
    @get:Rule val temp = TemporaryFolder()
    private val server = MockWebServer()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val visible = MutableStateFlow(true)
    private val requests = CopyOnWriteArrayList<RecordedRequest>()

    @After fun tearDown() {
        scope.cancel()
        runCatching { server.close() }
    }

    // --- Archives ----------------------------------------------------------------------------------------------

    private sealed interface Item
    private class FileItem(val name: String, val content: ByteArray) : Item
    private class LinkItem(val name: String, val target: String) : Item
    private class DirItem(val name: String) : Item

    private fun file(name: String, content: String = name) = FileItem(name, content.repeat(50).toByteArray())

    private fun goodItems() = listOf(
        DirItem("vits-piper-test/"),
        file("vits-piper-test/en_US-test-medium.onnx", "model"),
        file("vits-piper-test/en_US-test-medium.onnx.json", "model-json"),
        file("vits-piper-test/tokens.txt", "tokens"),
        file("vits-piper-test/MODEL_CARD", "card"),
        DirItem("vits-piper-test/espeak-ng-data/"),
        file("vits-piper-test/espeak-ng-data/phondata", "phondata"),
        file("vits-piper-test/espeak-ng-data/voices/en", "en"),
        file("vits-piper-test/README.md", "not used"),
    )

    private fun archive(items: List<Item> = goodItems()): ByteArray {
        val out = ByteArrayOutputStream()
        BZip2CompressorOutputStream(out).use { bz ->
            TarArchiveOutputStream(bz).use { tar ->
                tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX)
                for (item in items) {
                    when (item) {
                        is FileItem -> {
                            val e = TarArchiveEntry(item.name, true)
                            e.size = item.content.size.toLong()
                            tar.putArchiveEntry(e)
                            tar.write(item.content)
                            tar.closeArchiveEntry()
                        }
                        is DirItem -> {
                            tar.putArchiveEntry(TarArchiveEntry(item.name, true))
                            tar.closeArchiveEntry()
                        }
                        is LinkItem -> {
                            val e = TarArchiveEntry(item.name, TarConstants.LF_SYMLINK)
                            e.linkName = item.target
                            tar.putArchiveEntry(e)
                            tar.closeArchiveEntry()
                        }
                    }
                }
            }
        }
        return out.toByteArray()
    }

    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun voiceFor(bytes: ByteArray, sha: String = sha256(bytes)) =
        OwnVoice("test-voice", "Test", server.url("/voice.tar.bz2").toString(), bytes.size.toLong(), sha, speakerId = 0)

    /** Serves [bytes] with Range support. [first] changes the first response (to cut the connection, slow it, …). */
    private fun serve(bytes: ByteArray, first: (MockResponse.Builder) -> MockResponse.Builder = { it }) {
        var served = 0
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests += request
                val range = request.headers["Range"]
                val builder = if (range != null) {
                    val from = range.removePrefix("bytes=").removeSuffix("-").toInt()
                    MockResponse.Builder().code(206)
                        .addHeader("Content-Range", "bytes $from-${bytes.size - 1}/${bytes.size}")
                        .body(Buffer().write(bytes, from, bytes.size - from))
                } else {
                    MockResponse.Builder().code(200).body(Buffer().write(bytes))
                }
                return (if (served++ == 0) first(builder) else builder).build()
            }
        }
        server.start()
    }

    private fun downloader(
        voice: OwnVoice,
        free: () -> Long = { Long.MAX_VALUE },
        root: File = File(temp.root, "voices"),
        retries: Int = 3,
    ) = VoiceDownloader(
        voice = voice, root = root, http = { OkHttpClient() }, visible = visible, scope = scope, io = Dispatchers.IO,
        freeBytes = free, maxRetries = retries, retryDelayMs = 10,
    )

    private fun VoiceDownloader.awaitState(timeoutMs: Long = 15_000, what: (OwnVoiceState) -> Boolean): OwnVoiceState = runBlocking {
        withTimeout(timeoutMs) { state.first(what) }
    }

    private val voicesRoot get() = File(temp.root, "voices")

    // --- A normal download -------------------------------------------------------------------------------------

    @Test fun `a download is checked, unpacked and ready, with only the files Edith uses`() {
        val bytes = archive()
        serve(bytes)
        val d = downloader(voiceFor(bytes))
        assertEquals(OwnVoiceState.NotDownloaded, d.state.value)

        d.start()
        val ready = d.awaitState { it is OwnVoiceState.Ready || it is OwnVoiceState.Failed }
        assertTrue("was $ready", ready is OwnVoiceState.Ready)

        val dir = File(voicesRoot, "test-voice")
        assertEquals("modelmodel".length * 25L, File(dir, OwnVoice.MODEL).length())   // "model" x50
        assertTrue(File(dir, OwnVoice.TOKENS).isFile)
        assertTrue(File(dir, OwnVoice.MODEL_CARD).isFile)
        assertTrue(File(dir, "espeak-ng-data/phondata").isFile)
        assertTrue(File(dir, "espeak-ng-data/voices/en").isFile)
        assertFalse("the model's json isn't used", File(dir, "en_US-test-medium.onnx.json").exists())
        assertFalse("nor the readme", File(dir, "README.md").exists())
        assertFalse(File(voicesRoot, "test-voice.part").exists())
        assertFalse(File(voicesRoot, "test-voice.tmp").exists())
        assertEquals("the folder holds just the voice", setOf(OwnVoice.MODEL, OwnVoice.TOKENS, OwnVoice.MODEL_CARD, OwnVoice.ESPEAK_DATA), dir.list()!!.toSet())

        // A new run of the app finds it.
        assertTrue(downloader(voiceFor(bytes)).state.value is OwnVoiceState.Ready)
    }

    @Test fun `checking and unpacking report their progress, rising to 100 and finishing`() {
        val bytes = archive(goodItems() + file("vits-piper-test/espeak-ng-data/big", "x".repeat(40_000)))
        serve(bytes)
        val d = downloader(voiceFor(bytes))
        val seen = CopyOnWriteArrayList<OwnVoiceState>()
        val collector = scope.launch(Dispatchers.Unconfined) { d.state.collect { seen += it } }
        d.start()
        d.awaitState { it is OwnVoiceState.Ready }
        runBlocking { delay(100) }
        collector.cancel()

        val verifying = seen.filterIsInstance<OwnVoiceState.Verifying>().map { it.percent }
        val unpacking = seen.filterIsInstance<OwnVoiceState.Unpacking>().map { it.percent }
        assertTrue("checking started at 0 and reached 100: $verifying", verifying.first() == 0 && verifying.last() == 100)
        assertTrue("unpacking started at 0 and reached 100: $unpacking", unpacking.first() == 0 && unpacking.last() == 100)
        assertEquals("never goes backwards", verifying.sorted(), verifying)
        assertEquals("never goes backwards", unpacking.sorted(), unpacking)
        assertTrue("in order: checking, then unpacking, then ready", seen.indexOfFirst { it is OwnVoiceState.Verifying } < seen.indexOfFirst { it is OwnVoiceState.Unpacking })
    }

    @Test fun `the hash check reports its progress on a big file, rising to 100`() {
        val big = temp.newFile("big.bin").apply { writeBytes(ByteArray(2_000_000) { (it % 251).toByte() }) }
        val seen = mutableListOf<Int>()
        val hash = VoiceDownloader.sha256(big) { seen += it }
        assertEquals(sha256(big.readBytes()), hash)
        assertEquals(100, seen.last())
        assertTrue("many steps, not just the end: ${seen.size}", seen.size > 20)
        assertEquals(seen.sorted(), seen)
        assertEquals("each value once", seen.distinct(), seen)
    }

    @Test fun `delete removes the voice`() {
        val bytes = archive()
        serve(bytes)
        val d = downloader(voiceFor(bytes))
        d.start()
        d.awaitState { it is OwnVoiceState.Ready }
        d.delete()
        assertEquals(OwnVoiceState.NotDownloaded, d.state.value)
        assertFalse(File(voicesRoot, "test-voice").exists())
    }

    // --- Cut connections ---------------------------------------------------------------------------------------

    @Test fun `a cut connection is resumed with a Range request and the result is whole`() {
        val bytes = archive(goodItems() + file("vits-piper-test/espeak-ng-data/big", "x".repeat(5000)))
        serve(bytes) { it.onResponseBody(SocketEffect.CloseSocket()) }
        val d = downloader(voiceFor(bytes))
        d.start()
        val end = d.awaitState { it is OwnVoiceState.Ready || it is OwnVoiceState.Failed }
        assertTrue("was $end", end is OwnVoiceState.Ready)

        assertNull("the first request asked for everything", requests[0].headers["Range"])
        val range = requests[1].headers["Range"]
        assertNotNull("the second one resumed", range)
        assertTrue("from where the first stopped, not from 0: $range", range!!.removePrefix("bytes=").removeSuffix("-").toInt() > 0)
    }

    @Test fun `a connection that keeps dropping ends in a plain failure that Try again can resume`() {
        val bytes = archive()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests += request
                return MockResponse.Builder().onRequestStart(SocketEffect.CloseSocket()).build()
            }
        }
        server.start()
        val d = downloader(voiceFor(bytes), retries = 1)
        d.start()
        val failed = d.awaitState { it is OwnVoiceState.Failed } as OwnVoiceState.Failed
        assertEquals(VoiceDownloader.NO_NETWORK, failed.message)
        assertEquals("one try and one retry", 2, requests.size)
        assertFalse(File(voicesRoot, "test-voice").exists())
    }

    @Test fun `an error from the server is reported`() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = MockResponse.Builder().code(404).build()
        }
        server.start()
        val d = downloader(voiceFor(archive()))
        d.start()
        val failed = d.awaitState { it is OwnVoiceState.Failed } as OwnVoiceState.Failed
        assertTrue(failed.message, "404" in failed.message)
    }

    // --- Checks ------------------------------------------------------------------------------------------------

    @Test fun `a file that doesn't match the pinned hash is deleted and nothing is used`() {
        val bytes = archive()
        serve(bytes)
        val d = downloader(voiceFor(bytes, sha = sha256("something else".toByteArray())))
        d.start()
        val failed = d.awaitState { it is OwnVoiceState.Failed } as OwnVoiceState.Failed
        assertEquals("The file didn't match what I expected. Try again.", failed.message)
        assertFalse(File(voicesRoot, "test-voice.part").exists())
        assertFalse(File(voicesRoot, "test-voice.tmp").exists())
        assertFalse(File(voicesRoot, "test-voice").exists())
    }

    @Test fun `not enough free space fails before any request`() {
        val bytes = archive()
        serve(bytes)
        val d = downloader(voiceFor(bytes), free = { bytes.size * 2L - 1 })
        d.start()
        val failed = d.awaitState { it is OwnVoiceState.Failed } as OwnVoiceState.Failed
        assertTrue(failed.message, failed.message.startsWith("Not enough space"))
        assertEquals(0, requests.size)
    }

    @Test fun `exactly twice the size free is enough`() {
        val bytes = archive()
        serve(bytes)
        val d = downloader(voiceFor(bytes), free = { bytes.size * 2L })
        d.start()
        assertTrue(d.awaitState { it is OwnVoiceState.Ready || it is OwnVoiceState.Failed } is OwnVoiceState.Ready)
    }

    private fun expectBad(items: List<Item>, message: String) {
        val bytes = archive(items)
        serve(bytes)
        val d = downloader(voiceFor(bytes))
        d.start()
        val failed = d.awaitState { it is OwnVoiceState.Failed } as OwnVoiceState.Failed
        assertEquals(message, VoiceDownloader.UNEXPECTED, failed.message)
        assertFalse("no half voice", File(voicesRoot, "test-voice").exists())
        assertFalse(File(voicesRoot, "test-voice.tmp").exists())
        assertFalse("nothing escaped the folder", File(temp.root, "evil").exists() || File(voicesRoot, "evil").exists())
    }

    @Test fun `a tar entry with dot-dot is refused`() =
        expectBad(goodItems() + file("vits-piper-test/../../evil", "evil"), "dot-dot")

    @Test fun `a tar entry with an absolute path is refused`() =
        expectBad(goodItems() + file("/evil", "evil"), "absolute")

    @Test fun `a tar entry with a drive letter or backslashes is refused`() {
        expectBad(goodItems() + file("C:/evil", "evil"), "drive")
    }

    @Test fun `a link in the archive is refused`() =
        expectBad(goodItems() + LinkItem("vits-piper-test/espeak-ng-data/link", "/etc/passwd"), "link")

    @Test fun `an archive missing an expected file is refused, and leaves no half voice`() =
        expectBad(goodItems().filterNot { it is FileItem && it.name.endsWith("tokens.txt") }, "no tokens")

    @Test fun `an archive with two models is refused`() =
        expectBad(goodItems() + file("vits-piper-test/another.onnx", "second"), "two models")

    // --- Limits (on the extractor, with small caps) --------------------------------------------------------------

    @Test fun `extraction reports the share of the archive read, ending at 100`() {
        val f = temp.newFile("p.tar.bz2").apply { writeBytes(archive(goodItems() + file("vits-piper-test/espeak-ng-data/big", "y".repeat(40_000)))) }
        val seen = mutableListOf<Int>()
        VoiceArchive.extract(f, File(temp.root, "op"), onProgress = { seen += it })
        assertTrue(seen.isNotEmpty())
        assertEquals(100, seen.last())
        assertEquals(seen.sorted(), seen)
        assertEquals("each value once", seen.distinct(), seen)
    }

    @Test fun `too many entries or too many bytes are refused`() {
        val f = temp.newFile("v.tar.bz2").apply { writeBytes(archive()) }
        try {
            VoiceArchive.extract(f, File(temp.root, "o1"), maxEntries = 3)
            fail("too many entries")
        } catch (e: BadVoiceArchive) { assertTrue(e.message, "Too many" in e.message!!) }
        try {
            VoiceArchive.extract(f, File(temp.root, "o2"), maxBytes = 1_000)
            fail("too big")
        } catch (e: BadVoiceArchive) { assertTrue(e.message, "bigger" in e.message!!) }
        VoiceArchive.extract(f, File(temp.root, "o3"))   // and the real limits let it through
        assertTrue(File(temp.root, "o3/${OwnVoice.MODEL}").isFile)
    }

    @Test fun `a kill during unpacking leaves no voice folder, and the next start cleans up`() {
        val bytes = archive()
        serve(bytes)
        // What a killed run leaves behind: a half-unpacked folder and the finished download.
        File(voicesRoot, "test-voice.tmp/espeak-ng-data").mkdirs()
        File(voicesRoot, "test-voice.tmp/${OwnVoice.MODEL}").writeText("half")
        val d = downloader(voiceFor(bytes))
        assertEquals("a half voice is not a voice", OwnVoiceState.NotDownloaded, d.state.value)
        assertFalse(File(voicesRoot, "test-voice").exists())

        d.start()
        assertTrue(d.awaitState { it is OwnVoiceState.Ready || it is OwnVoiceState.Failed } is OwnVoiceState.Ready)
        assertFalse(File(voicesRoot, "test-voice.tmp").exists())
        assertTrue(File(voicesRoot, "test-voice/${OwnVoice.TOKENS}").isFile)
    }

    // --- Only on screen ------------------------------------------------------------------------------------------

    @Test fun `it waits while Edith is off screen, and goes on when she is back`() {
        visible.value = false
        val bytes = archive()
        serve(bytes)
        val d = downloader(voiceFor(bytes))
        d.start()
        val waiting = d.awaitState { it is OwnVoiceState.Downloading && it.waitingForScreen } as OwnVoiceState.Downloading
        assertTrue(waiting.waitingForScreen)
        runBlocking { delay(300) }
        assertEquals("no request while off screen", 0, requests.size)

        visible.value = true
        assertTrue(d.awaitState { it is OwnVoiceState.Ready || it is OwnVoiceState.Failed } is OwnVoiceState.Ready)
    }

    @Test fun `leaving the screen mid-download pauses it, and coming back resumes with Range`() {
        val bytes = archive(goodItems() + file("vits-piper-test/espeak-ng-data/big", "y".repeat(20_000)))
        serve(bytes) { it.throttleBody(bytes.size / 4L, 1, java.util.concurrent.TimeUnit.SECONDS) }
        val d = downloader(voiceFor(bytes))
        d.start()
        d.awaitState { it is OwnVoiceState.Downloading && it.percent in 1..99 }

        visible.value = false
        d.awaitState { it is OwnVoiceState.Downloading && it.waitingForScreen }
        val countWhileAway = requests.size
        runBlocking { delay(500) }
        assertEquals("no new request while away", countWhileAway, requests.size)

        visible.value = true
        assertTrue(d.awaitState { it is OwnVoiceState.Ready || it is OwnVoiceState.Failed } is OwnVoiceState.Ready)
        val resumed = requests.last()
        assertNotNull("resumed with Range: ${requests.map { it.headers["Range"] }}", resumed.headers["Range"])
    }

    @Test fun `cancel stops the download and throws away what was fetched`() {
        val bytes = archive(goodItems() + file("vits-piper-test/espeak-ng-data/big", "z".repeat(20_000)))
        serve(bytes) { it.throttleBody(bytes.size / 4L, 1, java.util.concurrent.TimeUnit.SECONDS) }
        val d = downloader(voiceFor(bytes))
        d.start()
        d.awaitState { it is OwnVoiceState.Downloading && it.percent in 1..99 }
        d.cancel()
        assertEquals(OwnVoiceState.NotDownloaded, d.state.value)
        runBlocking { delay(300) }
        assertEquals("the state stays NotDownloaded", OwnVoiceState.NotDownloaded, d.state.value)
        assertFalse(File(voicesRoot, "test-voice.part").exists())
    }
}
