package com.edith.app.settings

import com.edith.app.core.error.AppError
import com.edith.app.core.error.Outcome
import com.edith.app.ollama.ModelCatalog
import com.edith.app.ollama.ModelInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.getAndUpdate

/**
 * Makes sure a usable chat model is selected (C04 §2, 04-lessons L21). The chat engine (C06) calls [ensureModel]
 * before every send; the Settings screen calls it after loading models.
 */
class ModelSelection(private val settings: SettingsRepository, private val catalog: ModelCatalog) {
    private val pendingNotice = MutableStateFlow<String?>(null)

    /**
     * The saved model if it's installed. Otherwise picks one with [chooseDefault], saves it and leaves a one-time
     * notice ("Your model X is no longer installed; switched to Y.") for [takeNotice].
     */
    suspend fun ensureModel(): Outcome<ModelInfo> {
        val current = settings.current()
        val models = when (val result = catalog.chatModels(current.serverUrl)) {
            is Outcome.Failure -> return result
            is Outcome.Success -> result.value
        }
        models.firstOrNull { it.name == current.model }?.let { return Outcome.Success(it) }

        val chosen = chooseDefault(models) ?: return Outcome.Failure(AppError.NoChatModels)
        settings.update { it.copy(model = chosen.name) }
        if (current.model != null) {
            pendingNotice.value = "Your model ${current.model} is no longer installed; switched to ${chosen.name}."
        }
        return Outcome.Success(chosen)
    }

    /** The notice from the last automatic switch, once; null if there's none. */
    fun takeNotice(): String? = pendingNotice.getAndUpdate { null }

    companion object {
        /** Preference order: gemma4:12b*, then any gemma4, then the first that uses tools, then the first chat model. */
        fun chooseDefault(models: List<ModelInfo>): ModelInfo? =
            models.firstOrNull { it.name.startsWith("gemma4:12b") }
                ?: models.firstOrNull { it.name.startsWith("gemma4") }
                ?: models.firstOrNull { it.canUseTools }
                ?: models.firstOrNull()
    }
}
