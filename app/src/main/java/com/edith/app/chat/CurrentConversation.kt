package com.edith.app.chat

import com.edith.app.chat.engine.ChatEngine
import com.edith.app.core.log.Log
import com.edith.app.core.log.LogTag
import com.edith.app.data.chat.ChatRepository
import com.edith.app.settings.SettingsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Which conversation is open (C06b, decision D6). Remembered in settings, so the app reopens on the last chat.
 * Also owns the rules for New chat and Delete, so every screen gets them the same way. Lives in AppContainer.
 */
class CurrentConversation(
    private val repo: ChatRepository,
    private val settings: SettingsRepository,
    private val engine: ChatEngine,
) {
    private val _id = MutableStateFlow<Long?>(null)

    /** The open conversation; null only until [resolve] has run at startup. */
    val id: StateFlow<Long?> = _id.asStateFlow()

    private val mutex = Mutex()

    /** Waits for startup to pick the conversation. */
    suspend fun await(): Long = id.filterNotNull().first()

    /**
     * Startup: the saved conversation if it still exists, else the most recently active, else a new one. Then
     * removes other empty conversations (abandoned "New chat"s). Safe to call again; it re-checks.
     */
    suspend fun resolve(): Long = mutex.withLock {
        val saved = settings.current().currentConversationId?.let { repo.conversation(it) }
        val chosen = saved?.id ?: repo.mostRecentConversation()?.id ?: repo.createConversation()
        val pruned = repo.deleteEmptyConversationsExcept(chosen)
        if (pruned > 0) Log.d(LogTag.Chat, "Removed $pruned empty conversation(s)")
        setLocked(chosen)
        chosen
    }

    /** Opens [conversationId], if it exists. */
    suspend fun select(conversationId: Long) = mutex.withLock {
        if (repo.conversation(conversationId) != null) setLocked(conversationId)
        else Log.w(LogTag.Chat, "Can't open conversation $conversationId: it doesn't exist")
    }

    /** Opens a new empty chat — or stays put if the open one is already empty, so blank chats don't pile up. */
    suspend fun newChat(): Long = mutex.withLock {
        val current = _id.value
        if (current != null && repo.conversation(current) != null && repo.isEmpty(current)) return@withLock current
        repo.createConversation().also { setLocked(it) }
    }

    /**
     * Deletes a conversation. A reply running in it is stopped first, so nothing is written into a deleted chat.
     * If it was open, the next most recent one opens (or a new empty one when none is left).
     */
    suspend fun delete(conversationId: Long) {
        engine.cancelAndJoin(conversationId)
        mutex.withLock {
            repo.deleteConversation(conversationId)
            if (_id.value == conversationId) setLocked(repo.mostRecentConversation()?.id ?: repo.createConversation())
        }
    }

    private suspend fun setLocked(conversationId: Long) {
        _id.value = conversationId
        settings.update { it.copy(currentConversationId = conversationId) }
    }
}
