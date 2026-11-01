package com.edith.app.attachments

import com.edith.app.chat.engine.ChatEngine
import com.edith.app.chat.engine.ContextBuilder
import com.edith.app.chat.engine.PromptContext
import com.edith.app.core.error.AppError
import com.edith.app.data.db.AttachmentDirection
import com.edith.app.data.db.AttachmentEntity
import com.edith.app.data.db.AttachmentKind
import com.edith.app.data.db.MessageEntity
import com.edith.app.data.db.MessageRole
import com.edith.app.data.db.MessageSource
import com.edith.app.data.db.MessageStatus
import com.edith.app.data.db.MessageWithAttachments
import com.edith.app.ollama.ModelInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File
import java.time.ZonedDateTime

/** C15 §3: labels and media in what the model sees, the media window, the capability gate, and the prompt line. */
class AttachmentContextTest {
    private val sees = ModelInfo("gemma4:12b", setOf("completion", "vision", "audio", "tools"))
    private val textOnly = ModelInfo("ornith:9b", setOf("completion", "tools"))

    /** A store where attachment ids in [available] have a file; "encoding" gives "b64:<id>". */
    /** Attachment [id] is stored at "attachments/<id>"; the ids in [available] have a file. Text files read as "text of <id>". */
    private class FakeStore(private val available: Set<Long>) : AttachmentStore {
        override fun keep(pending: PendingAttachment, session: Boolean) = error("unused")
        override fun write(fileName: String, content: String, session: Boolean) = error("unused")
        override fun file(storedPath: String?): File? =
            storedPath?.removePrefix("attachments/")?.toLongOrNull()?.takeIf { it in available }?.let { File("/att/$it") }
        override fun durationMs(storedPath: String?): Long? = null
        override fun delete(storedPath: String?) {}
    }

    private fun context(vararg available: Long) =
        MediaAttachmentContext(FakeStore(available.toSet()), encode = { "b64:${it.name}" }, readText = { "text of ${it.name}" })

    private var nextId = 1L
    private fun message(text: String, vararg attachments: AttachmentEntity, role: MessageRole = MessageRole.User): MessageWithAttachments {
        val id = nextId++
        return MessageWithAttachments(
            MessageEntity(id = id, conversationId = 1, role = role, content = text, createdAt = id, status = MessageStatus.Complete, source = MessageSource.Typed),
            attachments.map { it.copy(messageId = id) },
        )
    }

    private fun attachment(id: Long, number: Int, name: String, kind: AttachmentKind) = AttachmentEntity(
        id = id, messageId = 0, number = number, direction = AttachmentDirection.FromUser, kind = kind, fileName = name,
        mimeType = if (kind == AttachmentKind.Image) "image/jpeg" else "audio/wav", sizeBytes = 10, storedPath = "attachments/$id", createdAt = 0,
    )

    @Test fun `labels follow the text and media goes in images`() {
        val item = message("what's on this list?", attachment(7, 3, "list.jpg", AttachmentKind.Image), attachment(8, 4, "note.wav", AttachmentKind.Audio))
        val m = context(7, 8).toOllama(item, item.message.content, sees, newestIndex = 0)
        assertEquals("what's on this list?\n[Attachment 3: list.jpg (image)]\n[Attachment 4: note.wav (audio)]", m.content)
        assertEquals(listOf("b64:7", "b64:8"), m.images)
        assertEquals("user", m.role)
    }

    @Test fun `an attachment alone is just its label`() {
        val item = message("", attachment(7, 1, "receipt.jpg", AttachmentKind.Image))
        assertEquals("[Attachment 1: receipt.jpg (image)]", context(7).toOllama(item, "", sees, 0).content)
    }

    @Test fun `media is sent only within the newest ten messages`() {
        val item = message("look", attachment(7, 1, "a.jpg", AttachmentKind.Image))
        assertEquals(listOf("b64:7"), context(7).toOllama(item, "look", sees, newestIndex = 9).images)
        val old = context(7).toOllama(item, "look", sees, newestIndex = 10)
        assertNull(old.images)
        assertEquals("look\n[Attachment 1: a.jpg (image)]", old.content)
    }

    @Test fun `a file that's gone is labelled no longer available and not sent`() {
        val item = message("and this?", attachment(7, 2, "old.jpg", AttachmentKind.Image))
        val m = context().toOllama(item, "and this?", sees, 0)
        assertEquals("and this?\n[Attachment 2: old.jpg (image)] — no longer available", m.content)
        assertNull(m.images)
    }

    @Test fun `media the model can't use is left out (older messages after a model switch)`() {
        val item = message("hi", attachment(7, 1, "a.jpg", AttachmentKind.Image))
        assertNull(context(7).toOllama(item, "hi", textOnly, 0).images)
    }

    @Test fun `the capability gate names the model and the Settings label`() {
        val image = listOf(attachment(1, 1, "a.jpg", AttachmentKind.Image))
        val audio = listOf(attachment(2, 2, "b.wav", AttachmentKind.Audio))
        val e = ChatEngine.cantUse(image, textOnly)!!
        assertEquals("ornith:9b can't see images — pick a model marked ‘Sees images’ in Settings.", e.userMessage)
        assertEquals("ornith:9b can't hear audio — pick a model marked ‘Hears audio’ in Settings.", ChatEngine.cantUse(audio, textOnly)!!.userMessage)
        assertNull(ChatEngine.cantUse(image + audio, sees))
        assertNull(ChatEngine.cantUse(emptyList(), textOnly))
        assertEquals("see images", (e as AppError.ModelLacksCapability).capability)
    }

    @Test fun `a message with only an attachment is kept in the context`() {
        val photo = message("", attachment(7, 1, "a.jpg", AttachmentKind.Image))
        val built = ContextBuilder(context(7)).build(listOf(message("first"), photo), "system", sees)
        assertEquals(3, built.size)
        assertEquals("[Attachment 1: a.jpg (image)]", built.last().content)
        assertEquals(listOf("b64:7"), built.last().images)
    }

    @Test fun `the prompt explains labels only when there are attachments`() {
        val now = ZonedDateTime.now()
        assertNull(AttachmentsSection().text(PromptContext(now, sees, hasAttachments = false)))
        assertEquals(
            "The user can attach images and audio files; they appear as labels like [Attachment 3: receipt.jpg (image)].",
            AttachmentsSection().text(PromptContext(now, sees, hasAttachments = true)),
        )
    }

    @Test fun `photos are scaled to 1536 on the longest side and never up`() {
        assertEquals(1536 to 1152, AttachmentIntake.scaled(4000, 3000))
        assertEquals(1152 to 1536, AttachmentIntake.scaled(3000, 4000))
        assertEquals(800 to 600, AttachmentIntake.scaled(800, 600))
        with(AttachmentIntake) {
            assertEquals("receipt.jpg", "receipt.png".withExtension("jpg"))
            assertEquals("voice note.wav", "voice note.m4a".withExtension("wav"))
            assertEquals("photo.jpg", "photo".withExtension("jpg"))
        }
    }
}
