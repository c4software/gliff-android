package io.github.c4software.gliff.presentation

import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import io.github.c4software.gliff.data.Logger
import io.github.c4software.gliff.data.clipboard.ClipboardWriter
import io.github.c4software.gliff.data.local.KeyValueStore
import io.github.c4software.gliff.data.local.MemoryStore
import io.github.c4software.gliff.data.session.Connector
import io.github.c4software.gliff.data.session.FakeDecoder
import io.github.c4software.gliff.data.session.Sessions
import io.github.c4software.gliff.data.video.VideoDecoderFactory
import io.github.c4software.gliff.di.AppDispatchers
import io.github.c4software.gliff.di.appModules
import io.github.c4software.gliff.domain.identity.Identity
import io.github.c4software.gliff.domain.machine.Machine
import io.github.c4software.gliff.domain.machine.MachineRepository
import io.github.c4software.gliff.domain.session.SessionError
import io.github.c4software.gliff.domain.session.SessionState
import io.github.c4software.gliff.presentation.machines.MachinesViewModel
import io.github.c4software.gliff.presentation.navigation.App
import io.github.c4software.gliff.presentation.navigation.SessionHost
import io.github.c4software.gliff.presentation.navigation.ViewModelScope
import io.github.c4software.gliff.presentation.session.SessionViewModel
import io.github.c4software.gliff.presentation.theme.GliffTheme
import io.github.c4software.gliff.transport.TransportException
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.StateFlow
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.core.parameter.parametersOf
import org.koin.dsl.module

/** The one-window root, on the whole graph: the machines, or the session of the window's machine. */
class AppTest {
    private val asked = CopyOnWriteArrayList<String>()

    /** The platform, as a test has it: nobody answers at the other end. */
    private val platform = module {
        single { AppDispatchers(main = Dispatchers.Default, io = Dispatchers.IO) }
        single<KeyValueStore> { MemoryStore() }
        single<Identity> { Identity { "ssh-ed25519 AAAA" } }
        single<Connector> {
            Connector { m ->
                asked.add(m.host)
                throw TransportException("nobody there")
            }
        }
        single<ClipboardWriter> { ClipboardWriter {} }
        single<Logger> { Logger { _, _ -> } }
        single { VideoDecoderFactory(::FakeDecoder) }
    }
    private val koin = startKoin { modules(appModules(platform)) }.koin
    private val sessions: Sessions = koin.get()
    private val machine = Machine(user = "val", host = "box", hostKey = "k").also { koin.get<MachineRepository>().save(it) }
    private val shown = CopyOnWriteArrayList<StateFlow<SessionState>?>()
    private val host = SessionHost(onSession = { shown.add(it) })

    private fun compose(only: String?, onLeave: () -> Unit = {}): ImageComposeScene =
        ImageComposeScene(820, 1700, Density(2f)) {
            ViewModelScope { GliffTheme { App(host, only, onLeave = onLeave) } }
        }.also {
            it.render(0)
            it.render(100_000_000)
        }

    @AfterTest
    fun tearDown() {
        sessions.endSession(machine.id)
        koin.get<CoroutineScope>().cancel()
        stopKoin()
    }

    @Test
    fun theGraphMakesEveryViewModel() {
        assertNotNull(koin.get<MachinesViewModel>())
        val session = koin.get<SessionViewModel> { parametersOf(machine.id) }
        assertSame(sessions.session(machine.id).state, session.sessionState)
    }

    @Test
    fun aPlainWindowStartsOnTheMachinesAndOpensNoSession() {
        val scene = compose(only = null)
        try {
            assertNull(sessions.frontSession)
            assertEquals(listOf<StateFlow<SessionState>?>(null), shown.toList())
            assertEquals(emptyList(), asked.toList())
        } finally {
            scene.close()
        }
    }

    @Test
    fun aMachinesWindowShowsItsSession() {
        val scene = compose(only = machine.id)
        try {
            val session = sessions.frontSession!!
            assertSame(session, sessions.session(machine.id))
            assertEquals(listOf<StateFlow<SessionState>?>(session.state), shown.toList())
            // It connects at once, to the window's machine.
            repeat(50) { if (session.state.value is SessionState.Failed) return@repeat else Thread.sleep(20) }
            assertEquals(listOf("box"), asked.toList())
            assertEquals(SessionError.Connection("nobody there"), (session.state.value as SessionState.Failed).error)
        } finally {
            scene.close()
        }
    }

    @Test
    fun theSessionEndsWithItsWindow() {
        val scene = compose(only = machine.id)
        val session = sessions.frontSession!!
        scene.close()
        assertNull(sessions.frontSession)
        assertEquals(SessionState.Closed, session.state.value)
    }
}
