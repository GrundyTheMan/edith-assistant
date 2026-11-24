package com.edith.app.testing

import com.edith.app.settings.Settings
import com.edith.app.settings.SettingsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** In-memory [SettingsRepository] for tests that run on every platform. */
class FakeSettingsRepository(initial: Settings = Settings()) : SettingsRepository {
    private val state = MutableStateFlow(initial)
    override val settings: StateFlow<Settings> = state
    override suspend fun current(): Settings = state.value
    override suspend fun update(transform: (Settings) -> Settings) {
        state.value = transform(state.value)
    }
}
