package com.edith.app.attachments

import com.edith.app.chat.engine.AttachmentContext
import com.edith.app.chat.engine.PromptContext
import com.edith.app.chat.engine.PromptSection
import com.edith.app.data.db.AttachmentDirection
import com.edith.app.data.db.AttachmentEntity
import com.edith.app.data.db.AttachmentKind
import com.edith.app.data.db.MessageRole
import com.edith.app.data.db.MessageWithAttachments
import com.edith.app.ollama.ModelInfo
import com.edith.app.ollama.OllamaMessage
import kotlin.io.encoding.Base64

/**
 * Attachments as the model sees them (C15 §3, C16 §5):
 * - **The owner's:** a label line per attachment, `[Attachment 3: receipt.jpg (image)]`, plus " — no longer
 *   available" when its file is gone. Within the newest [MEDIA_WINDOW] messages, files the model can use (images with
 *   `vision`, audio with `audio`) go in `images` as base64.
 * - **Edith's files:** her `[Attached file: name]` placeholder becomes the file block again, so she sees what she
 *   wrote, within the window and while it's stored. Otherwise the placeholder stays.
 * - **Her resends:** the `<attachment number="N"/>` tag she wrote.
 */
class MediaAttachmentContext(
    private val store: AttachmentFiles,
    private val encode: (ByteArray) -> String = { Base64.encode(it) },
    private val readText: (ByteArray) -> String = { it.decodeToString() },
) : AttachmentContext {

    override fun toOllama(item: MessageWithAttachments, text: String, model: ModelInfo, newestIndex: Int): OllamaMessage {
        val attachments = item.attachments.sortedBy { it.number }
        if (item.message.role == MessageRole.Assistant) return OllamaMessage("assistant", edithText(text, attachments, newestIndex))
        if (attachments.isEmpty()) return OllamaMessage("user", text)
        val labels = attachments.joinToString("\n") { a -> label(a, available = store.exists(a.storedPath)) }
        val media = if (newestIndex >= MEDIA_WINDOW) emptyList() else attachments.mapNotNull { a ->
            val usable = (a.kind == AttachmentKind.Image && model.canSee) || (a.kind == AttachmentKind.Audio && model.canHear)
            if (usable) store.readBytes(a.storedPath)?.let(encode) else null
        }
        val content = if (text.isBlank()) labels else "$text\n$labels"
        return OllamaMessage("user", content, images = media.ifEmpty { null })
    }

    private fun edithText(text: String, attachments: List<AttachmentEntity>, newestIndex: Int): String {
        val files = attachments.filter { it.direction == AttachmentDirection.FromEdith && it.resendOf == null }
        var out = if (files.isEmpty() || newestIndex >= MEDIA_WINDOW) text else ReplyFormat.replacePlaceholders(text) { name ->
            files.firstOrNull { it.fileName == name }?.let { store.readBytes(it.storedPath) }?.let { bytes ->
                "<file name=\"$name\">${readText(bytes).take(MAX_FILE_CHARS)}</file>"
            }
        }
        attachments.filter { it.resendOf != null }.forEach { out += "\n<attachment number=\"${it.number}\"/>" }
        return out
    }

    companion object {
        /** Media is re-sent for the newest 10 messages (03 §5, v1's window). */
        const val MEDIA_WINDOW = 10
        /** Edith's file contents go back to her up to this length each (they cost context). */
        const val MAX_FILE_CHARS = 8_000

        fun label(a: AttachmentEntity, available: Boolean): String {
            val kind = when (a.kind) {
                AttachmentKind.Image -> "image"
                AttachmentKind.Audio -> "audio"
                AttachmentKind.File -> "file"
            }
            return "[Attachment ${a.number}: ${a.fileName} ($kind)]" + if (available) "" else " — no longer available"
        }
    }
}

/** Tells the model what attachment labels mean, when the conversation has any (C15 §3). */
class AttachmentsSection : PromptSection {
    override fun text(ctx: PromptContext): String? = if (!ctx.hasAttachments) null else
        "The user can attach images and audio files; they appear as labels like [Attachment 3: receipt.jpg (image)]."
}
