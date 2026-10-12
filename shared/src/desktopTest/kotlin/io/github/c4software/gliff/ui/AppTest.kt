package io.github.c4software.gliff.ui

import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import io.github.c4software.gliff.client.Connector
import io.github.c4software.gliff.client.FakeDecoder
import io.github.c4software.gliff.client.Identity
import io.github.c4software.gliff.client.Machine
import io.github.c4software.gliff.client.MachineRepository
import io.github.c4software.gliff.client.MemoryStore
import io.github.c4software.gliff.client.SessionController
import io.github.c4software.gliff.client.SessionState
import io.github.c4software.gliff.client.Sessions
import io.github.c4software.gliff.client.SettingsRepository
import io.github.c4software.gliff.transport.TransportException
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/** The one-window root: the machines, or the session of the window's machine. */
class AppTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val store = MemoryStore()
    private val machines = MachineRepository(store)
    private val settings = SettingsRepository(store)
    private val machine = Machine(user = "val", host = "box", hostKey = "k").also { machines.save(it) }
    private val asked = CopyOnWriteArrayList<String>()
    private val connector = Connector { m ->
        asked.add(m.host)
        throw TransportException("nobody there")
    }
    private val sessions = Sessions(machines, settings, connector, scope, nowMs = { 0 }, decoder = ::FakeDecoder)
    private val shown = CopyOnWriteArrayList<SessionController?>()
    private val host = SessionHost(onSession = { shown.add(it) })

    private fun compose(only: String?, onLeave: () -> Unit = {}): ImageComposeScene =
        ImageComposeScene(820, 1700, Density(2f)) {
            GliffTheme { App(machines, settings, Identity { "ssh-ed25519 AAAA" }, sessions, host, only, onLeave = onLeave) }
        }.also {
            it.render(0)
            it.render(100_000_000)
        }

    @AfterTest
    fun tearDown() {
        sessions.endSession(machine.id)
        scope.cancel()
    }

    @Test
    fun aPlainWindowStartsOnTheMachinesAndOpensNoSession() {
        val scene = compose(only = null)
        try {
            assertNull(sessions.frontSession)
            assertEquals(listOf<SessionController?>(null), shown.toList())
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
            assertEquals(listOf<SessionController?>(session), shown.toList())
            // It connects at once, to the window's machine.
            repeat(50) { if (session.state.value is SessionState.Failed) return@repeat else Thread.sleep(20) }
            assertEquals(listOf("box"), asked.toList())
            assertEquals("nobody there", (session.state.value as SessionState.Failed).message)
        } finally {
            scene.close()
        }
    }
}
