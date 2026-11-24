package com.edith.app.ui.components

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.edith.app.di.viewModelFactory
import com.edith.app.settings.SettingsRepository
import com.edith.app.voice.speech.ChoosingTts
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Whether the Chat tab shows "I can't speak yet" (C36 §5): replies should be spoken, nothing can speak, and the owner
 * hasn't dismissed it. The dismissal is saved.
 */
class VoiceBannerViewModel(private val settings: SettingsRepository, choosing: ChoosingTts) : ViewModel() {
    val visible: StateFlow<Boolean> = combine(settings.settings, choosing.available) { s, available ->
        s.speakReplies && !available && !s.voiceBannerDismissed
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    fun dismiss() {
        viewModelScope.launch { settings.update { it.copy(voiceBannerDismissed = true) } }
    }

    companion object {
        val Factory = viewModelFactory { container, _ -> VoiceBannerViewModel(container.settingsRepository, container.choosingTts) }
    }
}
