package com.edith.app.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.edith.app.core.error.AppError
import com.edith.app.core.error.Outcome
import com.edith.app.core.permissions.LocalNetworkAccess
import com.edith.app.ollama.ModelCatalog
import com.edith.app.ollama.ModelInfo
import com.edith.app.ollama.OllamaClient
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** What the Settings screen shows. */
data class SettingsUiState(
    val serverField: String = "",
    val serverSaved: String = "",
    /** Why the typed address can't be used, if it can't. */
    val serverError: String? = null,
    val permission: PermissionState = PermissionState.Unknown,
    val connection: ConnectionState = ConnectionState.Idle,
    val models: ModelsState = ModelsState.Loading,
    val selectedModel: String? = null,
    val savedModelMissing: Boolean = false,
    /** One-time message, e.g. after an automatic model switch. */
    val notice: String? = null,
    val ollamaVersion: String? = null,
) {
    val hasUnsavedServer get() = serverField.trim() != serverSaved
}

/** Whether Edith may use the local network (L23). */
enum class PermissionState { Unknown, Granted, NeedsRequest, Denied }

/** How testing the connection to Ollama is going. */
sealed interface ConnectionState {
    data object Idle : ConnectionState
    data object Testing : ConnectionState
    data class Ok(val version: String) : ConnectionState
    data class Failed(val msg: String) : ConnectionState
}

/** The installed models' list as Settings shows it: loading, loaded or failed. */
sealed interface ModelsState {
    data object Loading : ModelsState
    data class Loaded(val list: List<ModelInfo>) : ModelsState
    data class Failed(val msg: String) : ModelsState
    data object Empty : ModelsState
    /** Can't load until the local network permission is granted (04-lessons L23). */
    data object NeedsPermission : ModelsState
}

/** Server & model settings (C04 §4). Network work goes through the C03 client; nothing blocks the UI. */
class SettingsViewModel(
    private val settings: SettingsRepository,
    private val catalog: ModelCatalog,
    private val ollama: OllamaClient,
    private val modelSelection: ModelSelection,
    private val localNetwork: LocalNetworkAccess,
    /** The platform's rule for plain-http addresses (the iPhone's, D49); null: any. */
    private val plainHttp: com.edith.app.settings.PlainHttpRule? = null,
) : ViewModel() {
    private val _state = MutableStateFlow(SettingsUiState())
    val state: StateFlow<SettingsUiState> = _state.asStateFlow()

    /** Address edits; collected with a 600 ms debounce so typing doesn't fire a request per keystroke. */
    private val fieldEdits = MutableSharedFlow<String>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    private var loadJob: Job? = null

    init {
        viewModelScope.launch {
            val saved = settings.current()
            _state.update {
                it.copy(serverField = saved.serverUrl, serverSaved = saved.serverUrl, selectedModel = saved.model)
            }
            refreshPermission()
            loadModels()
        }
        viewModelScope.launch {
            @OptIn(ExperimentalCoroutinesApi::class)
            fieldEdits.collectLatest {
                delay(DEBOUNCE_MS)
                loadModels()
            }
        }
    }

    // --- Permission ------------------------------------------------------------------------------------------

    /** Re-checks the permission (e.g. when the screen returns from Android's settings). Reloads if it was just granted. */
    fun refreshPermission() {
        val was = _state.value.permission
        val granted = localNetwork.isGranted()
        _state.update {
            it.copy(
                permission = when {
                    granted -> PermissionState.Granted
                    it.permission == PermissionState.Denied -> PermissionState.Denied
                    else -> PermissionState.NeedsRequest
                },
            )
        }
        if (granted && was != PermissionState.Granted && was != PermissionState.Unknown) onGranted()
    }

    /** Result of the system permission dialog. */
    fun onPermissionResult(granted: Boolean) {
        _state.update { it.copy(permission = if (granted) PermissionState.Granted else PermissionState.Denied) }
        if (granted) onGranted() else _state.update { it.copy(models = ModelsState.NeedsPermission) }
    }

    /** Clears a stale "needs permission" result and loads what couldn't be loaded before. */
    private fun onGranted() {
        _state.update { it.copy(connection = ConnectionState.Idle) }
        loadModels()
    }

    private fun permissionError(): String? =
        if (_state.value.permission == PermissionState.Granted) null
        else AppError.PermissionDenied("local network").userMessage

    // --- Server address --------------------------------------------------------------------------------------

    fun onServerFieldChange(text: String) {
        _state.update { it.copy(serverField = text, serverError = null, connection = ConnectionState.Idle) }
        fieldEdits.tryEmit(text)
    }

    fun saveServer() {
        when (val result = ServerUrl.normalize(_state.value.serverField, plainHttp)) {
            is ServerUrl.Result.Invalid -> _state.update { it.copy(serverError = result.message) }
            is ServerUrl.Result.Valid -> viewModelScope.launch {
                settings.update { it.copy(serverUrl = result.url) }
                _state.update { it.copy(serverField = result.url, serverSaved = result.url, serverError = null) }
                loadModels()
            }
        }
    }

    fun testConnection() {
        val url = typedUrlOrShowError() ?: return
        permissionError()?.let { msg -> _state.update { it.copy(connection = ConnectionState.Failed(msg)) }; return }
        _state.update { it.copy(connection = ConnectionState.Testing) }
        viewModelScope.launch {
            val result = ollama.version(url)
            _state.update {
                when (result) {
                    is Outcome.Success -> it.copy(connection = ConnectionState.Ok(result.value), ollamaVersion = result.value)
                    is Outcome.Failure -> it.copy(connection = ConnectionState.Failed(result.error.userMessage))
                }
            }
        }
    }

    /** The normalized typed address, or null after showing why it can't be used. */
    private fun typedUrlOrShowError(): String? = when (val result = ServerUrl.normalize(_state.value.serverField, plainHttp)) {
        is ServerUrl.Result.Valid -> result.url
        is ServerUrl.Result.Invalid -> { _state.update { it.copy(serverError = result.message) }; null }
    }

    // --- Models ----------------------------------------------------------------------------------------------

    /** Loads the model list for the typed address. Also the Retry button. */
    fun loadModels() {
        loadJob?.cancel()
        val url = typedUrlOrShowError() ?: run {
            _state.update { it.copy(models = ModelsState.Failed(it.serverError ?: "Enter a valid address.")) }
            return
        }
        if (permissionError() != null) {
            _state.update { it.copy(models = ModelsState.NeedsPermission) }
            return
        }
        _state.update { it.copy(models = ModelsState.Loading) }
        loadJob = viewModelScope.launch {
            catalog.invalidate()
            when (val result = catalog.chatModels(url)) {
                is Outcome.Failure -> _state.update { it.copy(models = ModelsState.Failed(result.error.userMessage)) }
                is Outcome.Success -> {
                    val list = result.value
                    _state.update { it.copy(models = if (list.isEmpty()) ModelsState.Empty else ModelsState.Loaded(list)) }
                    (ollama.version(url) as? Outcome.Success)?.let { v -> _state.update { it.copy(ollamaVersion = v.value) } }
                    if (url == _state.value.serverSaved) ensureSavedModelIsInstalled(list)
                    else markMissing(list)
                }
            }
        }
    }

    /** For the saved server: if no model is chosen or it's gone, pick one, save it, and show the notice (L21). */
    private suspend fun ensureSavedModelIsInstalled(list: List<ModelInfo>) {
        if (list.any { it.name == _state.value.selectedModel }) return markMissing(list)
        when (val result = modelSelection.ensureModel()) {
            is Outcome.Success -> _state.update {
                it.copy(selectedModel = result.value.name, savedModelMissing = false, notice = modelSelection.takeNotice())
            }
            is Outcome.Failure -> markMissing(list)
        }
    }

    private fun markMissing(list: List<ModelInfo>) {
        _state.update { s -> s.copy(savedModelMissing = s.selectedModel != null && list.none { it.name == s.selectedModel }) }
    }

    /** Picking a model saves it immediately (C04 §3). */
    fun selectModel(name: String) {
        _state.update { it.copy(selectedModel = name, savedModelMissing = false) }
        viewModelScope.launch { settings.update { it.copy(model = name) } }
    }

    fun dismissNotice() = _state.update { it.copy(notice = null) }

    companion object {
        const val DEBOUNCE_MS = 600L
    }
}
