package com.edith.app.chat.engine

import com.edith.app.core.error.Outcome
import com.edith.app.core.log.Log
import com.edith.app.core.log.LogTag
import com.edith.app.data.chat.ChatRepository
import com.edith.app.data.db.MessageRole
import com.edith.app.data.db.MessageStatus
import com.edith.app.data.db.TitleSource
import com.edith.app.ollama.ChatCall
import com.edith.app.ollama.OllamaClient
import com.edith.app.ollama.OllamaMessage
import com.edith.app.settings.SettingsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.util.Collections

/**
 * Decision D12: after the first finished reply in a chat, Edith writes the chat a short title with one extra,
 * non-streaming model call. Until then (and if this fails) the chat keeps its provisional first-message title.
 * Never replaces a title the owner set. Runs in [scope], off the turn, so it never delays the next send.
 */
class ConversationTitler(
    private val repo: ChatRepository,
    private val ollama: OllamaClient,
    private val settings: SettingsRepository,
    private val scope: CoroutineScope,
) : ReplyListener {
    /** One attempt per conversation per app run, so a model that can't title doesn't cost a call every reply. */
    private val attempted: MutableSet<Long> = Collections.synchronizedSet(mutableSetOf())

    override fun onFinished(conversationId: Long, messageId: Long?, text: String) {
        if (!attempted.add(conversationId)) return
        scope.launch { titleIfNeeded(conversationId) }
    }

    /** Returns the title it set, or null. Public for tests. */
    suspend fun titleIfNeeded(conversationId: Long): String? {
        try {
            val conversation = repo.conversation(conversationId) ?: return null
            if (conversation.titleSource == TitleSource.Owner || conversation.titleSource == TitleSource.Edith) return null
            val history = repo.recent(conversationId, RECENT_MESSAGES)
            val firstUser = history.firstOrNull { it.message.role == MessageRole.User }?.message?.content ?: return null
            val firstReply = history.firstOrNull {
                it.message.role == MessageRole.Assistant && it.message.status == MessageStatus.Complete
            }?.message ?: return null
            val current = settings.current()
            val model = firstReply.model ?: current.model ?: return null

            val prompt = "Owner: ${firstUser.take(EXCERPT_CHARS)}\nEdith: ${firstReply.content.take(EXCERPT_CHARS)}"
            val result = ollama.chat(current.serverUrl, ChatCall(model, listOf(OllamaMessage("system", INSTRUCTION), OllamaMessage("user", prompt))))
            val title = when (result) {
                is Outcome.Success -> clean(result.value.content)
                is Outcome.Failure -> {
                    Log.w(LogTag.Chat, "Couldn't title conversation $conversationId: ${result.error::class.simpleName}")
                    null
                }
            } ?: return null
            return title.takeIf { repo.setEdithTitle(conversationId, it) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(LogTag.Chat, "Couldn't title conversation $conversationId", e)   // never worth a crash
            return null
        }
    }

    companion object {
        const val INSTRUCTION = "You name chat conversations. Reply with only a short title for this one: 2 to 6 words, " +
            "no quotes, no ending punctuation, no emoji."
        const val MAX_CHARS = 60
        /** Titling runs right after the first reply, so the newest few messages hold the first exchange. */
        private const val RECENT_MESSAGES = 6
        private const val EXCERPT_CHARS = 1_000

        /** First non-blank line, without quotes, markdown, a "Title:" label or ending punctuation. Null if unusable. */
        fun clean(raw: String): String? {
            val line = raw.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() } ?: return null
            val title = line
                .removePrefix("#").trim()
                .replace(Regex("""^(?i)title\s*:\s*"""), "")
                .trim('"', '\'', '*', '_', '`', '“', '”', '‘', '’', ' ')
                .trimEnd('.', '!', '?', ',', ';', ':')
                .replace(Regex("""\s+"""), " ")
                .trim()
            if (title.isEmpty()) return null
            return if (title.length <= MAX_CHARS) title else title.take(MAX_CHARS - 1).substringBeforeLast(' ').trimEnd() + "…"
        }
    }
}
