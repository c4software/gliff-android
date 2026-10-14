package io.github.c4software.gliff.presentation.session

import io.github.c4software.gliff.data.local.MemoryStore
import io.github.c4software.gliff.data.local.StoredMachineRepository
import io.github.c4software.gliff.data.local.StoredSettingsRepository
import io.github.c4software.gliff.data.mirror.WebMirror
import io.github.c4software.gliff.data.session.Connector
import io.github.c4software.gliff.data.session.FakeDecoder
import io.github.c4software.gliff.data.session.Peer
import io.github.c4software.gliff.data.session.Sessions
import io.github.c4software.gliff.domain.machine.Machine
import io.github.c4software.gliff.domain.session.SessionState
import io.github.c4software.gliff.proto.ChromaMode
import io.github.c4software.gliff.proto.ClientMsg
import io.github.c4software.gliff.proto.Codec
import io.github.c4software.gliff.proto.ServerMsg
import io.github.c4software.gliff.proto.VideoPipeline
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain

/** What the session's window is told, and what it asks of the session. */
@OptIn(ExperimentalCoroutinesApi::class)
class SessionViewModelTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val store = MemoryStore()
    private val machines = StoredMachineRepository(store)
    private val settings = StoredSettingsRepository(store)
    private val machine = Machine(user = "val", host = "box", hostKey = "k").also { machines.save(it) }
    private val peers = LinkedBlockingQueue<Peer>()
    private val sessions = Sessions(
        machines, settings, Connector { Peer().also { peers.add(it) }.transport }, scope, Dispatchers.IO,
        clock = { 4242 },
        decoders = { FakeDecoder() },
    )
    private val mirror = object : WebMirror {
        override val watched = MutableStateFlow(false)
        override fun urls(token: String) = emptyList<String>()
    }

    private fun peer(): Peer = peers.poll(5, TimeUnit.SECONDS) ?: error("no connection within 5 s")

    private fun config() = ServerMsg.StreamConfig(
        Codec.H264, ChromaMode.Single420, VideoPipeline.Gpu, 1000, 800, 1000, ByteArray(0), null, 1000, 800, 60,
    )

    @BeforeTest
    fun setUp() = Dispatchers.setMain(Dispatchers.Unconfined)

    @AfterTest
    fun tearDown() {
        sessions.endSession(machine.id)
        scope.cancel()
        Dispatchers.resetMain()
    }

    @Test
    fun theAreaReachesTheRemoteShrunkUnderTheKeyboard() {
        val viewModel = SessionViewModel(machine.id, sessions, settings, mirror)
        viewModel.onArea(1000, 800, density = 2.5f, zoom = 1f)
        val p = peer()
        p.handshake(config())
        assertEquals(ClientMsg.Resize(1000, 800, 2.5f), p.expect())
        viewModel.onArea(1000, 800, density = 2.5f, zoom = 0.5f)
        assertEquals(ClientMsg.Resize(1000, 800, 1.25f), p.expect())
    }

    @Test
    fun theScreenDarkensOnlyWhileABrowserWatchesAndTheUserWantsIt() {
        val viewModel = SessionViewModel(machine.id, sessions, settings, mirror)
        val watcher = CoroutineScope(Dispatchers.Unconfined)
        watcher.launch { viewModel.uiState.collect {} }
        try {
            assertEquals("val@box", viewModel.uiState.value.address)
            settings.update { it.copy(httpMirror = true, mirrorDark = true) }
            assertEquals(false, viewModel.uiState.value.darkens)
            mirror.watched.value = true
            assertEquals(true, viewModel.uiState.value.darkens)
            settings.update { it.copy(mirrorDark = false) }
            assertEquals(false, viewModel.uiState.value.darkens)
        } finally {
            watcher.cancel()
        }
    }

    @Test
    fun leavingEndsTheSessionOnce() {
        val viewModel = SessionViewModel(machine.id, sessions, settings, null)
        val state = viewModel.sessionState
        viewModel.end()
        assertEquals(SessionState.Closed, state.value)
        assertNull(sessions.frontSession)
        // Opened again meanwhile: the window that left does not end the new session.
        val again = sessions.session(machine.id)
        viewModel.end()
        assertEquals(again, sessions.session(machine.id))
    }
}
