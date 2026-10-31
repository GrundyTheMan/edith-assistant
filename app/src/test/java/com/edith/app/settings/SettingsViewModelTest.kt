package com.edith.app.settings

import com.edith.app.core.error.AppError
import com.edith.app.ollama.ModelCatalog
import com.edith.app.testing.FakeLocalNetworkAccess
import com.edith.app.testing.FakeOllamaClient
import com.edith.app.testing.FakeSettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val client = FakeOllamaClient()
    private val settings = FakeSettingsRepository()
    private val network = FakeLocalNetworkAccess()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    private fun viewModel(): SettingsViewModel {
        val catalog = ModelCatalog(client)
        return SettingsViewModel(settings, catalog, client, ModelSelection(settings, catalog), network)
    }

    @Test fun `loads models on start, hides embedding models, and picks a default`() = runTest(dispatcher) {
        val vm = viewModel()
        advanceUntilIdle()

        val models = (vm.state.value.models as ModelsState.Loaded).list.map { it.name }
        assertEquals(listOf("gemma4:12b-it-q8_0", "qwen3.5:9b-q8_0"), models)
        assertEquals("gemma4:12b-it-q8_0", vm.state.value.selectedModel)
        assertEquals("gemma4:12b-it-q8_0", settings.current().model)
        assertEquals("0.40.13", vm.state.value.ollamaVersion)
    }

    @Test fun `error then Retry reloads (regression test for L17)`() = runTest(dispatcher) {
        client.failure = AppError.ServerUnreachable("http://10.0.2.2:11434")
        val vm = viewModel()
        advanceUntilIdle()
        assertTrue(vm.state.value.models is ModelsState.Failed)

        client.failure = null
        vm.loadModels()   // the Retry button
        advanceUntilIdle()
        assertTrue(vm.state.value.models is ModelsState.Loaded)
    }

    @Test fun `rapid address edits collapse into one load after 600 ms`() = runTest(dispatcher) {
        val vm = viewModel()
        advanceUntilIdle()
        val before = client.listModelsCalls

        "192.168.4.82".forEachIndexed { i, _ ->
            vm.onServerFieldChange("192.168.4.82".take(i + 1))
            advanceTimeBy(100)
        }
        assertEquals("no load while typing", before, client.listModelsCalls)
        advanceTimeBy(SettingsViewModel.DEBOUNCE_MS + 1)
        advanceUntilIdle()

        assertEquals(before + 1, client.listModelsCalls)
        assertEquals("http://192.168.4.82:11434", client.listModelsUrls.last())
    }

    @Test fun `save normalizes and persists, and invalid input shows why`() = runTest(dispatcher) {
        val vm = viewModel()
        advanceUntilIdle()

        vm.onServerFieldChange("bad address")
        vm.saveServer()
        assertEquals("The address can't contain spaces.", vm.state.value.serverError)

        vm.onServerFieldChange("192.168.4.82")
        assertTrue(vm.state.value.hasUnsavedServer)
        vm.saveServer()
        advanceUntilIdle()
        assertEquals("http://192.168.4.82:11434", settings.current().serverUrl)
        assertEquals("http://192.168.4.82:11434", vm.state.value.serverField)
        assertTrue(!vm.state.value.hasUnsavedServer)
    }

    @Test fun `without the local network permission nothing connects, and granting it loads`() = runTest(dispatcher) {
        network.granted = false
        val vm = viewModel()
        advanceUntilIdle()
        assertEquals(PermissionState.NeedsRequest, vm.state.value.permission)
        assertEquals(ModelsState.NeedsPermission, vm.state.value.models)
        assertEquals(0, client.listModelsCalls)

        vm.onPermissionResult(granted = false)
        vm.testConnection()
        advanceUntilIdle()
        assertEquals(PermissionState.Denied, vm.state.value.permission)
        assertEquals(ConnectionState.Failed(AppError.PermissionDenied("local network").userMessage), vm.state.value.connection)

        network.granted = true
        vm.refreshPermission()   // back from Android settings
        advanceUntilIdle()
        assertTrue(vm.state.value.models is ModelsState.Loaded)
        assertEquals("the old permission error is cleared", ConnectionState.Idle, vm.state.value.connection)
    }

    @Test fun `test connection shows the version`() = runTest(dispatcher) {
        val vm = viewModel()
        advanceUntilIdle()
        vm.testConnection()
        advanceUntilIdle()
        assertEquals(ConnectionState.Ok("0.40.13"), vm.state.value.connection)
    }

    @Test fun `selecting a model saves it immediately`() = runTest(dispatcher) {
        val vm = viewModel()
        advanceUntilIdle()
        vm.selectModel("qwen3.5:9b-q8_0")
        advanceUntilIdle()
        assertEquals("qwen3.5:9b-q8_0", settings.current().model)
    }

    @Test fun `a saved model that is gone is replaced with a notice`() = runTest(dispatcher) {
        settings.update { it.copy(model = "bogus-model") }
        val vm = viewModel()
        advanceUntilIdle()
        assertEquals("gemma4:12b-it-q8_0", vm.state.value.selectedModel)
        assertEquals("Your model bogus-model is no longer installed; switched to gemma4:12b-it-q8_0.", vm.state.value.notice)
    }
}
