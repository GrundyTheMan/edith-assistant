package com.edith.app.attachments

import com.edith.app.attachments.ReplyFormat.Part
import com.edith.app.data.db.AttachmentDirection
import com.edith.app.data.db.AttachmentEntity
import com.edith.app.data.db.AttachmentKind
import com.edith.app.data.db.MessageEntity
import com.edith.app.data.db.MessageRole
import com.edith.app.data.db.MessageSource
import com.edith.app.data.db.MessageStatus
import com.edith.app.data.db.MessageWithAttachments
import com.edith.app.ollama.ModelInfo
import com.edith.app.voice.speech.SpeechText
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/** C16 §3, §5: Edith's file blocks and resends, the names she gives files, and what goes back to the model. */
class ReplyFormatTest {

    @Test fun `text, a file and a resend come apart in order`() {
        val reply = "Here's your list.\n<file name=\"packing-list.md\">- towel\n- sunscreen\n</file>\nAnd your receipt: <attachment number=\"3\"/>"
        assertEquals(
            listOf(Part.Text("Here's your list.\n"), Part.File("packing-list.md", "- towel\n- sunscreen"), Part.Text("\nAnd your receipt: "), Part.Resend(3)),
            ReplyFormat.parse(reply),
        )
    }

    @Test fun `an unclosed file runs to the end of the reply`() {
        assertEquals(listOf(Part.Text("Plan:"), Part.File("plan.md", "1. Pack\n2. Go")), ReplyFormat.parse("Plan:<file name=\"plan.md\">1. Pack\n2. Go"))
    }

    @Test fun `resend tags in the forms models write`() {
        assertEquals(listOf(Part.Resend(2)), ReplyFormat.parse("<attachment number=\"2\"/>"))
        assertEquals(listOf(Part.Resend(12)), ReplyFormat.parse("<attachment number=12 />"))
        assertEquals(listOf(Part.Text("Just text, no tags.")), ReplyFormat.parse("Just text, no tags."))
    }

    @Test fun `file names are made safe`() {
        assertEquals("packing-list.md", ReplyFormat.sanitize("packing list.md"))
        assertEquals("no path tricks", "etcpasswd.md", ReplyFormat.sanitize("../../etc/passwd"))
        assertEquals("note.md", ReplyFormat.sanitize(""))
        assertEquals("note.md", ReplyFormat.sanitize("..."))
        assertEquals("shopping.md", ReplyFormat.sanitize("shopping"))
        assertEquals("ASCII only", "caf-menu.csv", ReplyFormat.sanitize("café-menu.csv"))
        assertEquals(ReplyFormat.MAX_NAME, ReplyFormat.sanitize("a".repeat(100) + ".md").length)
    }

    @Test fun `streaming text shows a file as being written and hides resend tags`() {
        assertEquals("Here you go.\n\n_Writing list.md…_", ReplyFormat.streaming("Here you go.\n<file name=\"list.md\">- tow"))
        assertEquals("And:", ReplyFormat.streaming("And: <attachment number=\"3\"/>"))
    }

    @Test fun `placeholders are hidden in the bubble and swapped for the file for the model`() {
        val stored = "Here's your list.\n\n[Attached file: list.md]\n\nHave fun!"
        assertEquals("Here's your list.\n\nHave fun!", ReplyFormat.withoutPlaceholders(stored))
        assertEquals("Here's your list.\n\n<f>\n\nHave fun!", ReplyFormat.replacePlaceholders(stored) { if (it == "list.md") "<f>" else null })
        assertEquals("", ReplyFormat.withoutPlaceholders("[Attached file: a.md]"))
    }

    @Test fun `mime types follow the extension`() {
        assertEquals("text/markdown", ReplyFormat.mimeType("a.md"))
        assertEquals("text/csv", ReplyFormat.mimeType("a.csv"))
        assertEquals("text/plain", ReplyFormat.mimeType("script.py"))
    }

    @Test fun `speech skips resend tags and file placeholders`() {
        assertEquals("Here it is. Done.", SpeechText.clean("Here it is. <attachment number=\"3\"/> [Attached file: list.md] Done."))
    }

    // --- What the model sees of Edith's attachments (C16 §5) ---------------------------------------------------

    private class Store(private val available: Set<String>) : AttachmentStore {
        override fun keep(pending: PendingAttachment, session: Boolean) = error("unused")
        override fun discard(pending: PendingAttachment) {}
        override fun write(fileName: String, content: String, session: Boolean) = error("unused")
        override fun writeBytes(fileName: String, bytes: ByteArray, session: Boolean) = error("unused")
        override fun exists(storedPath: String?) = file(storedPath) != null
        override fun readBytes(storedPath: String?): ByteArray? = file(storedPath)?.name?.encodeToByteArray()
        override fun storedFiles(): List<StoredFile> = emptyList()
        override fun clearSessionAndTemp() {}
        override fun file(storedPath: String?): File? = storedPath?.takeIf { it in available }?.let { File(it) }
        override fun durationMs(storedPath: String?): Long? = null
        override fun delete(storedPath: String?) {}
    }

    private val model = ModelInfo("gemma4:12b", setOf("vision", "audio"))

    private fun edith(text: String, vararg a: AttachmentEntity) = MessageWithAttachments(
        MessageEntity(id = 5, conversationId = 1, role = MessageRole.Assistant, content = text, createdAt = 5, status = MessageStatus.Complete, source = MessageSource.Typed),
        a.toList(),
    )

    private fun row(number: Int, name: String, path: String?, resendOf: Long? = null, kind: AttachmentKind = AttachmentKind.File) = AttachmentEntity(
        id = number.toLong(), messageId = 5, number = number, direction = AttachmentDirection.FromEdith, kind = kind, fileName = name,
        mimeType = "text/markdown", sizeBytes = 1, storedPath = path, resendOf = resendOf, createdAt = 0,
    )

    private val context = MediaAttachmentContext(Store(setOf("attachments/list.md")), encode = { "b64" }, readText = { "- towel\n- sunscreen" })

    @Test fun `Edith sees her own file again within the window, and the placeholder after`() {
        val item = edith("Here's your list.\n\n[Attached file: list.md]", row(4, "list.md", "attachments/list.md"))
        assertEquals("Here's your list.\n\n<file name=\"list.md\">- towel\n- sunscreen</file>", context.toOllama(item, item.message.content, model, 0).content)
        assertEquals("Here's your list.\n\n[Attached file: list.md]", context.toOllama(item, item.message.content, model, 10).content)
        val gone = edith("[Attached file: old.md]", row(4, "old.md", null))
        assertEquals("[Attached file: old.md]", context.toOllama(gone, gone.message.content, model, 0).content)
    }

    @Test fun `a resend goes back as the tag she wrote, with no media`() {
        val item = edith("Here it is again.", row(3, "receipt.jpg", null, resendOf = 9, kind = AttachmentKind.Image))
        val m = context.toOllama(item, item.message.content, model, 0)
        assertEquals("Here it is again.\n<attachment number=\"3\"/>", m.content)
        assertEquals(null, m.images)
    }
}
