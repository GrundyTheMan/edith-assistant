package com.edith.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.edith.app.data.room.ChatHistoryDao
import com.edith.app.data.room.ChatHistoryEntity
import com.edith.app.network.AudioService
import com.edith.app.network.ChatRequest
import com.edith.app.network.ChatMessage
import com.edith.app.network.OllamaApi
import com.edith.app.network.NetworkClient
import com.edith.app.network.ToolExecutor
import com.edith.app.utils.ConfigManager
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
"""

data class ChatUiState(
    val messages: List<ChatMessage> = emptyList(),
    val isLoading: Boolean = false,
    val loadingLabel: String = THINKING_LABEL,
    val error: String? = null
)

private const val THINKING_LABEL = "Edith is thinking…"

class ChatViewModel(
    private val chatHistoryDao: ChatHistoryDao,
    private val toolExecutor: ToolExecutor,
    private val configManager: ConfigManager,
    private val audioService: AudioService,
    private val voiceService: VoiceService
) : ViewModel() {
    private val _uiState = MutableStateFlow(ChatUiState())
    val uiState: StateFlow<ChatUiState> = _uiState

    init {
        chatHistoryDao.getAllHistory()
            .onEach { history ->
                // DAO returns newest first; the conversation reads oldest first
                _uiState.value = _uiState.value.copy(
                    messages = history.reversed().map { ChatMessage(it.role, it.content) }
                )
            }
            .launchIn(viewModelScope)
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

    fun sendMessage(text: String) {
        voiceService.stop()
        val newMessage = ChatMessage("user", text)
        _uiState.value = _uiState.value.copy(messages = _uiState.value.messages + newMessage)

        viewModelScope.launch {
            chatHistoryDao.insertHistory(ChatHistoryEntity(role = "user", content = text, timestamp = Date().time.toLong()))
            requestReply()
        }
    }

    /** Discards Edith's latest reply and asks for a fresh one to the same conversation. */
    fun regenerateLastReply() {
        val messages = _uiState.value.messages
        if (_uiState.value.isLoading || messages.lastOrNull()?.role != "assistant") return

        voiceService.stop()
        _uiState.value = _uiState.value.copy(messages = messages.dropLast(1))
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
            fullMessages.addAll(_uiState.value.messages)

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
                chatHistoryDao.insertHistory(ChatHistoryEntity(role = "assistant", content = response.message.content, timestamp = Date().time.toLong()))
                _uiState.value = _uiState.value.copy(
                    messages = _uiState.value.messages + response.message,
                    isLoading = false
                )
                voiceService.speakReply(response.message.content)
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
            chatHistoryDao.insertHistory(ChatHistoryEntity(role = "assistant", content = response.message.content, timestamp = Date().time.toLong()))
            _uiState.value = _uiState.value.copy(
                messages = _uiState.value.messages + response.message,
                isLoading = false
            )
            voiceService.speakReply(response.message.content)
        }
    }
}