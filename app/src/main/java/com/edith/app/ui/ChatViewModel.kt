package com.edith.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.edith.app.data.room.ChatHistoryDao
import com.edith.app.data.room.ChatHistoryEntity
import com.edith.app.network.ChatRequest
import com.edith.app.network.ChatMessage
import com.edith.app.network.OllamaApi
import com.edith.app.network.NetworkClient
import com.edith.app.network.ToolExecutor
import com.edith.app.utils.ConfigManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import java.util.Date

data class ChatUiState(
    val messages: List<ChatMessage> = emptyList(),
    val isLoading: Boolean = false
)

class ChatViewModel(
    private val chatHistoryDao: ChatHistoryDao,
    private val toolExecutor: ToolExecutor,
    private val configManager: ConfigManager
) : ViewModel() {
    private val _uiState = MutableStateFlow(ChatUiState())
    val uiState: StateFlow<ChatUiState> = _uiState

    init {
        chatHistoryDao.getAllHistory()
            .onEach { history ->
                _uiState.value = ChatUiState(
                    messages = history.map { ChatMessage(it.role, it.content) },
                    isLoading = false
                )
            }
            .launchIn(viewModelScope)
    }

    fun sendMessage(text: String) {
        val newMessage = ChatMessage("user", text)
        _uiState.value = _uiState.value.copy(messages = _uiState.value.messages + newMessage)

        viewModelScope.launch {
            chatHistoryDao.insertHistory(ChatHistoryEntity(role = "user", content = text, timestamp = Date().time.toLong()))
            
            _uiState.value = _uiState.value.copy(isLoading = true)
            try {
                val url = configManager.getOllamaUrl()
                val model = configManager.getModelName()
                
                NetworkClient.clear()
                val api = NetworkClient.getOllamaApi(url)
                
                val request = ChatRequest(
                    model = model,
                    messages = _uiState.value.messages + newMessage
                )
                val response = api.generateChat(request)
                
                if (response.tool_calls != null && response.tool_calls.isNotEmpty()) {
                    for (toolCall in response.tool_calls) {
                        val result = toolExecutor.execute(toolCall)
                        chatHistoryDao.insertHistory(ChatHistoryEntity(role = "system", content = "Tool Result: $result", timestamp = Date().time.toLong()))
                        
                        processMessage(messages + response.message + ChatMessage("system", "Tool Result: $result"))
                        return
                    }
                } else {
                    chatHistoryDao.insertHistory(ChatHistoryEntity(role = "assistant", content = response.message.content, timestamp = Date().time.toLong()))
                    _uiState.value = _uiState.value.copy(
                        messages = _uiState.value.messages + response.message,
                        isLoading = false
                    )
                }
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(isLoading = false)
            }
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
                
                processMessage(messages + response.message + ChatMessage("system", "Tool Result: $result"))
                return
            }
        } else {
            chatHistoryDao.insertHistory(ChatHistoryEntity(role = "assistant", content = response.message.content, timestamp = Date().time.toLong()))
            _uiState.value = _uiState.value.copy(
                messages = _uiState.value.messages + response.message,
                isLoading = false
            )
        }
    }
}
