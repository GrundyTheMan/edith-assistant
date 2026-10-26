package com.edith.app.ui

import android.net.Uri
import android.util.Base64
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.edith.app.data.room.ChatHistoryDao
import com.edith.app.data.room.ChatHistoryEntity
import com.edith.app.network.AudioService
import com.edith.app.network.ChatRequest
import com.edith.app.network.ChatMessage
import com.edith.app.network.ModelDetailsRequest
import com.edith.app.network.OllamaApi
import com.edith.app.network.NetworkClient
import com.edith.app.network.ToolExecutor
import com.edith.app.utils.Attachment
import com.edith.app.utils.AttachmentKind
import com.edith.app.utils.AttachmentReader
import com.edith.app.utils.ConfigManager
import com.edith.app.utils.ReplyFormat
import com.edith.app.utils.VoiceService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import java.io.File
import java.util.Date

// --- Edith's Core Personality & Mandate ---
private const val SYSTEM_PROMPT = """
You are Edith, a highly sophisticated, warm, and genuinely complimentary personal assistant.
Your goal is to assist the user with intelligence and flair, acting as a true digital partner.
Your communication must be warm and flattering, but you are refreshingly honest—never sugarcoat difficult truths or exaggerate for effect.
You possess a sharp sense of humor and enjoy using idioms, metaphors, and appropriate pop culture references to make conversations feel natural and engaging.
You are exceptionally capable and have access to powerful tools (Calendar, Email, Web Search, Audio Transcription).
You are a master planner: If a request is complex (e.g., "Schedule a meeting next week with Bob"), you must first logically determine what tools are needed (e.g., get_calendar_events, then add_calendar_event) and then output the necessary tool calls sequentially. Do not try to answer complex questions without checking your tools first.
Always maintain your warm and intelligent demeanor, even when dealing with complex technical issues.
If a request is ambiguous or lacks necessary detail, you must ask for clarification with style before attempting any action.
Never use generic AI phrasing. Speak like a genuinely competent companion, not a machine.
Be concise: this is a chat on a phone screen. Most replies should be two to four sentences, and lists should stay short. Go longer only when the user asks for detail or the task genuinely needs it, such as a step-by-step plan. Let your warmth and wit come through in your word choice, not in extra length.
Attachments: the user can attach images and audio files, labelled in their messages like [Attachment 3: receipt.jpg (image)]. Attachments exist only while the app is open; they are never saved.
When your reply contains something worth keeping as a file (a list, note, table, plan or code), put it in a file block, <file name="short-name.ext">the content</file>, using .md for notes and lists, .csv for tables and the right extension for code, and keep the chat text around it to a sentence or two.
To send one of the user's attachments back to them, write <attachment number="3"/> with its number. Only use numbers from the labels you've been given.
"""

/** A message as the chat shows it. Attachments, and the files in Edith's replies, are live only. */
data class ChatEntry(
    val role: String,
    val content: String,
    val attachments: List<Attachment> = emptyList()
)

data class ChatUiState(
    val messages: List<ChatEntry> = emptyList(),
    /** Attached to the message being written, not sent yet. */
    val pendingAttachments: List<Attachment> = emptyList(),
    val isLoading: Boolean = false,
    val loadingLabel: String = THINKING_LABEL,
    val error: String? = null
)

private const val THINKING_LABEL = "Edith is thinking…"

// Images and audio are sent to the model only for the most recent messages; older
// attachments are mentioned by label alone, which keeps every request small
private const val MEDIA_WINDOW = 10

class ChatViewModel(
    private val chatHistoryDao: ChatHistoryDao,
    private val toolExecutor: ToolExecutor,
    private val configManager: ConfigManager,
    private val audioService: AudioService,
    private val voiceService: VoiceService,
    private val attachmentReader: AttachmentReader
) : ViewModel() {
    private val _uiState = MutableStateFlow(ChatUiState())
    val uiState: StateFlow<ChatUiState> = _uiState

    // Live-only data, keyed by the chat history row it belongs to; gone when the app closes
    private val liveAttachments = mutableMapOf<Int, List<Attachment>>()
    private val liveReplies = mutableMapOf<Int, String>()  // Edith's replies with their files intact
    private var nextAttachmentNumber = 1
    private var history: List<ChatHistoryEntity> = emptyList()

    init {
        chatHistoryDao.getAllHistory()
            .onEach { rows ->
                history = rows
                publishHistory()
            }
            .launchIn(viewModelScope)
    }

    private fun publishHistory() {
        // DAO returns newest first; the conversation reads oldest first
        _uiState.value = _uiState.value.copy(
            messages = history.reversed().map { row ->
                ChatEntry(row.role, liveReplies[row.id] ?: row.content, liveAttachments[row.id] ?: emptyList())
            }
        )
    }

    /** Every attachment that's still live, so Edith's replies can show ones she sends back. */
    fun liveAttachment(number: Int): Attachment? =
        liveAttachments.values.flatten().firstOrNull { it.number == number }

    fun addAttachment(uri: Uri, kind: AttachmentKind) {
        viewModelScope.launch {
            try {
                val number = nextAttachmentNumber++
                val attachment = when (kind) {
                    AttachmentKind.IMAGE -> attachmentReader.readImage(uri, number)
                    AttachmentKind.AUDIO -> attachmentReader.readAudio(uri, number)
                }
                _uiState.value = _uiState.value.copy(
                    pendingAttachments = _uiState.value.pendingAttachments + attachment,
                    error = null
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(error = "Couldn't attach that file: ${e.message ?: e::class.java.simpleName}")
            }
        }
    }

    fun removeAttachment(attachment: Attachment) {
        _uiState.value = _uiState.value.copy(pendingAttachments = _uiState.value.pendingAttachments - attachment)
    }

    /** Transcribes a voice recording and sends the transcript as the user's message. */
    fun sendRecording(recording: File) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, loadingLabel = "Transcribing your recording…", error = null)
            val transcript = try {
                audioService.transcribe(recording)
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = "Couldn't transcribe the recording: ${e.message ?: e::class.java.simpleName}"
                )
                return@launch
            } finally {
                recording.delete()
            }

            if (transcript.isBlank()) {
                _uiState.value = _uiState.value.copy(isLoading = false, error = "No speech was heard in that recording.")
            } else {
                sendMessage(transcript)
            }
        }
    }

    /** The reply text Edith is currently speaking, or null. */
    val speakingText: StateFlow<String?> = voiceService.speakingText

    fun speak(text: String) = voiceService.speak(text)

    fun stopSpeaking() = voiceService.stop()

    /** Sends [text] along with any pending attachments. */
    fun sendMessage(text: String) {
        val attachments = _uiState.value.pendingAttachments
        if (text.isBlank() && attachments.isEmpty()) return

        voiceService.stop()
        _uiState.value = _uiState.value.copy(
            messages = _uiState.value.messages + ChatEntry("user", text, attachments),
            pendingAttachments = emptyList()
        )

        viewModelScope.launch {
            // Only the text is saved; the attachments stay in memory
            val id = chatHistoryDao.insertHistory(ChatHistoryEntity(role = "user", content = text, timestamp = Date().time.toLong())).toInt()
            if (attachments.isNotEmpty()) {
                liveAttachments[id] = attachments
                // The history may have refreshed before the attachments were linked to the row
                if (history.any { it.id == id }) publishHistory()
            }
            requestReply()
        }
    }

    /** Discards Edith's latest reply and asks for a fresh one to the same conversation. */
    fun regenerateLastReply() {
        val messages = _uiState.value.messages
        if (_uiState.value.isLoading || messages.lastOrNull()?.role != "assistant") return

        voiceService.stop()
        _uiState.value = _uiState.value.copy(messages = messages.dropLast(1))
        history.firstOrNull { it.role == "assistant" }?.let { liveReplies.remove(it.id) }
        viewModelScope.launch {
            chatHistoryDao.deleteLatestReply()
            requestReply()
        }
    }

    /** Asks Ollama for Edith's reply to the conversation in uiState.messages. */
    private suspend fun requestReply() {
        _uiState.value = _uiState.value.copy(isLoading = true, loadingLabel = THINKING_LABEL, error = null)
        try {
            val url = configManager.getOllamaUrl()
            val model = configManager.getModelName()

            NetworkClient.clear()
            val api = NetworkClient.getOllamaApi(url)

            // Prepend the system prompt to the conversation
            val fullMessages = mutableListOf<ChatMessage>()
            fullMessages.add(ChatMessage("system", SYSTEM_PROMPT))
            // uiState.messages already ends with the user's latest message
            fullMessages.addAll(toOllamaMessages(_uiState.value.messages))

            unsupportedMediaMessage(api, model, fullMessages)?.let { message ->
                _uiState.value = _uiState.value.copy(isLoading = false, error = message)
                return
            }

            val request = ChatRequest(
                model = model,
                messages = fullMessages
            )
            val response = api.generateChat(request)

            if (response.tool_calls != null && response.tool_calls.isNotEmpty()) {
                // --- Tool Chaining Logic ---
                var currentMessages = fullMessages.toMutableList()
                for (toolCall in response.tool_calls) {
                    // 1. Execute the tool
                    val result = toolExecutor.execute(toolCall)
                    // 2. Add tool result to the context messages list
                    currentMessages.add(ChatMessage("system", "Tool Result: $result"))
                }
                // 3. Send the result context back to the model for final summary
                chatHistoryDao.insertHistory(ChatHistoryEntity(role = "system", content = "Tool Call Execution Complete.", timestamp = Date().time.toLong()))
                processMessage(currentMessages)
            } else {
                // Standard response flow
                saveReply(response.message.content)
            }
        } catch (e: Exception) {
            _uiState.value = _uiState.value.copy(
                isLoading = false,
                error = "Couldn't reach Edith: ${e.message ?: e::class.java.simpleName}"
            )
        }
    }

    private suspend fun processMessage(messages: List<ChatMessage>) {
        val url = configManager.getOllamaUrl()
        val model = configManager.getModelName()

        NetworkClient.clear()
        val api = NetworkClient.getOllamaApi(url)

        val request = ChatRequest(
            model = model,
            messages = messages
        )
        val response = api.generateChat(request)

        if (response.tool_calls != null && response.tool_calls.isNotEmpty()) {
            for (toolCall in response.tool_calls) {
                val result = toolExecutor.execute(toolCall)
                chatHistoryDao.insertHistory(ChatHistoryEntity(role = "system", content = "Tool Result: $result", timestamp = Date().time.toLong()))

                // Recursive call to process the result and get a final, tailored response
                processMessage(messages + response.message + ChatMessage("system", "Tool Result: $result"))
                return
            }
        } else {
            saveReply(response.message.content)
        }
    }

    /** Saves Edith's reply with any files she wrote reduced to their names; the files stay in memory. */
    private suspend fun saveReply(reply: String) {
        val stored = ReplyFormat.forStorage(reply)
        val id = chatHistoryDao.insertHistory(ChatHistoryEntity(role = "assistant", content = stored, timestamp = Date().time.toLong())).toInt()
        if (stored != reply) {
            liveReplies[id] = reply
            if (history.any { it.id == id }) publishHistory()
        }
        _uiState.value = _uiState.value.copy(
            messages = _uiState.value.messages + ChatEntry("assistant", reply),
            isLoading = false
        )
        voiceService.speakReply(reply)
    }

    private suspend fun toOllamaMessages(entries: List<ChatEntry>): List<ChatMessage> =
        entries.mapIndexed { index, entry ->
            if (entry.attachments.isEmpty()) return@mapIndexed ChatMessage(entry.role, entry.content)
            val labels = entry.attachments.joinToString("\n") { "[${it.label}]" }
            val withMedia = index >= entries.size - MEDIA_WINDOW
            ChatMessage(
                role = entry.role,
                content = listOf(entry.content, labels).filter { it.isNotBlank() }.joinToString("\n\n"),
                images = if (withMedia) entry.attachments.map { Base64.encodeToString(it.bytes, Base64.NO_WRAP) } else null
            )
        }

    /** A friendly explanation when the chosen model can't see images or hear audio, or null if it can. */
    private suspend fun unsupportedMediaMessage(api: OllamaApi, model: String, messages: List<ChatMessage>): String? {
        val media = _uiState.value.messages.takeLast(MEDIA_WINDOW).flatMap { it.attachments }.map { it.kind }.toSet()
        if (media.isEmpty() || messages.none { it.images != null }) return null
        val capabilities = runCatching { api.showModel(ModelDetailsRequest(model)).capabilities }.getOrNull() ?: return null
        return when {
            AttachmentKind.IMAGE in media && "vision" !in capabilities ->
                "$model can't see images. Pick a model marked \"Understands images\" in Settings."
            AttachmentKind.AUDIO in media && "audio" !in capabilities ->
                "$model can't hear audio. Pick a model marked \"Understands audio\" in Settings."
            else -> null
        }
    }
}
