package com.edith.app.attachments

import android.util.Base64
import com.edith.app.chat.engine.AttachmentContext
import com.edith.app.chat.engine.PromptContext
import com.edith.app.chat.engine.PromptSection
import com.edith.app.data.db.AttachmentEntity
import com.edith.app.data.db.AttachmentKind
import com.edith.app.data.db.MessageRole
import com.edith.app.data.db.MessageWithAttachments
import com.edith.app.ollama.ModelInfo
import com.edith.app.ollama.OllamaMessage
import java.io.File

/**
 * Attachments as the model sees them (C15 §3):
 * - Each message's text gets a label line per attachment: `[Attachment 3: receipt.jpg (image)]`, plus
 *   " — no longer available" when its file is gone.
 * - Within the newest [MEDIA_WINDOW] messages, files the model can use (images with `vision`, audio with `audio`) go
 *   in `images` as base64. Older messages get the label only.
 */
class MediaAttachmentContext(
    private val store: AttachmentStore,
    private val encode: (File) -> String = { Base64.encodeToString(it.readBytes(), Base64.NO_WRAP) },
) : AttachmentContext {

    override fun toOllama(item: MessageWithAttachments, text: String, model: ModelInfo, newestIndex: Int): OllamaMessage {
        val role = if (item.message.role == MessageRole.User) "user" else "assistant"
        if (item.attachments.isEmpty()) return OllamaMessage(role, text)
        val attachments = item.attachments.sortedBy { it.number }
        val labels = attachments.joinToString("\n") { a -> label(a, available = store.file(a.id) != null) }
        val media = if (newestIndex >= MEDIA_WINDOW) emptyList() else attachments.mapNotNull { a ->
            val usable = (a.kind == AttachmentKind.Image && model.canSee) || (a.kind == AttachmentKind.Audio && model.canHear)
            if (usable) store.file(a.id)?.let(encode) else null
        }
        val content = if (text.isBlank()) labels else "$text\n$labels"
        return OllamaMessage(role, content, images = media.ifEmpty { null })
    }

    companion object {
        /** Media is re-sent for the newest 10 messages (03 §5, v1's window). */
        const val MEDIA_WINDOW = 10

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
