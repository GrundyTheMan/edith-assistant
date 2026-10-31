package com.edith.app.di

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.initializer
import com.edith.app.EdithApp

/**
 * Builds a ViewModel factory that gets its dependencies from the [AppContainer] (C02 §1). Usage:
 * ```
 * class ChatViewModel(private val engine: ChatEngine) : ViewModel() {
 *     companion object { val Factory = viewModelFactory { container, _ -> ChatViewModel(container.chatEngine) } }
 * }
 * // in a screen:
 * val vm: ChatViewModel = viewModel(factory = ChatViewModel.Factory)
 * ```
 * The [SavedStateHandle] is for state that must survive the app being killed in the background.
 */
inline fun <reified VM : ViewModel> viewModelFactory(
    crossinline create: (container: AppContainer, savedState: SavedStateHandle) -> VM,
): ViewModelProvider.Factory = androidx.lifecycle.viewmodel.viewModelFactory {
    initializer {
        val app = checkNotNull(this[APPLICATION_KEY]) { "viewModelFactory needs the Application in CreationExtras" }
        create((app as EdithApp).container, createSavedStateHandle())
    }
}
