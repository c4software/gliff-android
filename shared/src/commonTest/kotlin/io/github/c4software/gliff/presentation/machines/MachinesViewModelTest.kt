package io.github.c4software.gliff.presentation.machines

import io.github.c4software.gliff.data.local.MemoryStore
import io.github.c4software.gliff.data.local.StoredMachineRepository
import io.github.c4software.gliff.data.local.StoredSettingsRepository
import io.github.c4software.gliff.data.mirror.WebMirror
import io.github.c4software.gliff.di.AppDispatchers
import io.github.c4software.gliff.domain.identity.Identity
import io.github.c4software.gliff.domain.machine.Machine
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain

@OptIn(ExperimentalCoroutinesApi::class)
class MachinesViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val store = MemoryStore()
    private val machines = StoredMachineRepository(store)
    private val settings = StoredSettingsRepository(store)
    private var keysMade = 0
    private val identity = Identity {
        keysMade++
        "ssh-ed25519 AAAA gliff@test"
    }

    private class FakeMirror : WebMirror {
        override val watched: StateFlow<Boolean> = MutableStateFlow(false)
        override fun urls(token: String) = listOf("http://10.0.0.2:8765/$token/")
    }

    private fun viewModel(mirror: WebMirror? = FakeMirror()) =
        MachinesViewModel(machines, settings, identity, mirror, AppDispatchers(main = dispatcher, io = dispatcher))

    /** Keeps the state observed, as a screen would. */
    private fun TestScope.observe(viewModel: MachinesViewModel) {
        backgroundScope.launch(dispatcher) { viewModel.uiState.collect {} }
    }

    @BeforeTest
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun theStateFollowsTheSavedMachines() = runTest(dispatcher) {
        val viewModel = viewModel()
        observe(viewModel)
        val machine = Machine(user = "val", host = "box")
        viewModel.save(machine)
        advanceUntilIdle()
        assertEquals(listOf(machine), viewModel.uiState.value.machines)
        viewModel.remove(machine)
        advanceUntilIdle()
        assertEquals(emptyList(), viewModel.uiState.value.machines)
    }

    @Test
    fun theMirrorsAddressesShowOnlyWhileItIsOn() = runTest(dispatcher) {
        val viewModel = viewModel()
        observe(viewModel)
        advanceUntilIdle()
        assertEquals(emptyList(), viewModel.uiState.value.mirrorUrls)
        viewModel.updateSettings(settings.settings.value.copy(httpMirror = true, mirrorToken = "abc"))
        advanceUntilIdle()
        assertEquals(listOf("http://10.0.0.2:8765/abc/"), viewModel.uiState.value.mirrorUrls)
        assertEquals(true, settings.settings.value.httpMirror)
    }

    @Test
    fun withoutAMirrorItsOptionsDoNotShow() = runTest(dispatcher) {
        val viewModel = viewModel(mirror = null)
        observe(viewModel)
        viewModel.updateSettings(settings.settings.value.copy(httpMirror = true))
        advanceUntilIdle()
        assertNull(viewModel.uiState.value.mirrorUrls)
    }

    @Test
    fun theKeyIsReadOnceWhenAskedFor() = runTest(dispatcher) {
        val viewModel = viewModel()
        observe(viewModel)
        advanceUntilIdle()
        assertNull(viewModel.uiState.value.authorizedKey)
        assertEquals(0, keysMade)
        viewModel.loadKey()
        advanceUntilIdle()
        viewModel.loadKey()
        advanceUntilIdle()
        assertEquals("ssh-ed25519 AAAA gliff@test", viewModel.uiState.value.authorizedKey)
        assertEquals(1, keysMade)
    }
}
