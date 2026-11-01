package com.edith.app.chat.engine

import com.edith.app.data.db.MessageRole
import com.edith.app.data.db.MessageStatus
import com.edith.app.data.db.MessageWithAttachments
import com.edith.app.ollama.ModelInfo
import com.edith.app.ollama.OllamaMessage
import kotlin.math.ceil

/** Turns a stored message into the Ollama message sent as context; C15 adds attachment labels and media. */
fun interface AttachmentContext {
    /** [newestIndex] is 0 for the newest kept message, 1 for the one before, … (for C15's media window). */
    fun toOllama(item: MessageWithAttachments, text: String, model: ModelInfo, newestIndex: Int): OllamaMessage
}

/** Default until C15: text only. */
val NoAttachments = AttachmentContext { item, text, _, _ -> OllamaMessage(item.message.role.ollamaRole(), text) }

private fun MessageRole.ollamaRole() = when (this) {
    MessageRole.User -> "user"
    MessageRole.Assistant -> "assistant"
}

/**
 * History → the messages Ollama sees (C06 §2). Order of rules matters; see each step. Only `user`/`assistant`
 * rows that completed are sent: a failed or cancelled turn's text must not teach the model anything (lesson L3).
 */
class ContextBuilder(private val attachmentContext: AttachmentContext = NoAttachments) {

    fun build(history: List<MessageWithAttachments>, system: String, model: ModelInfo): List<OllamaMessage> {
        // 2 + 3: completed rows only, with text-form tool calls removed; drop what's left empty (no text, no attachments).
        val usable = history
            .filter { it.message.status == MessageStatus.Complete }
            .map { it to stripToolCalls(it.message.content) }
            .filter { (item, text) -> text.isNotBlank() || item.attachments.isNotEmpty() }   // a photo alone is a message (C15)

        // 4: newest first, keep while within the token budget; the latest user message always stays.
        val latestUser = usable.lastOrNull { it.first.message.role == MessageRole.User }
        val kept = ArrayDeque<Pair<MessageWithAttachments, String>>()
        var tokens = 0
        for (entry in usable.asReversed()) {
            val isLatestUser = entry === latestUser
            val text = if (isLatestUser) truncateMiddle(entry.second, MAX_CHARS) else entry.second
            val cost = estimateTokens(text)
            if (!isLatestUser && tokens + cost > TOKEN_BUDGET) break
            tokens += cost
            kept.addFirst(entry.first to text)
        }

        // 5: the model should see a user turn first.
        while (kept.firstOrNull()?.first?.message?.role == MessageRole.Assistant) kept.removeFirst()

        // 1 + 6: system prompt first, then each message through the attachment hook.
        return listOf(OllamaMessage("system", system)) + kept.mapIndexed { i, (item, text) ->
            attachmentContext.toOllama(item, text, model, newestIndex = kept.size - 1 - i)
        }
    }

    companion object {
        /** Leaves room for tools, media and the reply inside num_ctx 16384 (C03). */
        const val TOKEN_BUDGET = 10_000
        const val CHARS_PER_TOKEN = 3.5
        private val MAX_CHARS = (TOKEN_BUDGET * CHARS_PER_TOKEN).toInt()
        private const val GAP = "[…]"
        private val toolCallText = Regex("""(?s)<tool_call>.*?(</tool_call>|$)""")

        fun estimateTokens(text: String): Int = ceil(text.length / CHARS_PER_TOKEN).toInt()

        /** Removes `<tool_call>…</tool_call>`, and an unclosed `<tool_call>` to the end. */
        fun stripToolCalls(text: String): String = text.replace(toolCallText, "").trim()

        /** Keeps the start and end of an over-long message, with "[…]" in the middle. */
        fun truncateMiddle(text: String, maxChars: Int): String {
            if (text.length <= maxChars) return text
            val keep = maxChars - GAP.length
            return text.take(keep - keep / 2) + GAP + text.takeLast(keep / 2)
        }
    }
}
