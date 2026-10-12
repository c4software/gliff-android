package io.github.c4software.gliff.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import io.github.c4software.gliff.client.Identity
import io.github.c4software.gliff.client.Machine
import io.github.c4software.gliff.client.MachineRepository
import io.github.c4software.gliff.client.SessionController
import io.github.c4software.gliff.client.Sessions
import io.github.c4software.gliff.client.SettingsRepository

/** What the window around a session can do, and wants to be told. */
class SessionHost(
    /** The host can lay the window on its side; it does so while [onLandscape] last said true. */
    val turns: Boolean = false,
    val onLandscape: (Boolean) -> Unit = {},
    /** A session is on screen, or (null) none is any more. */
    val onSession: (SessionController?) -> Unit = {},
)

/**
 * The whole app in one window, for a host that has no window per screen: the
 * machines, then the session of the one that was opened.
 *
 * `only`: this window belongs to one machine and shows nothing else; leaving
 * its session is `onLeave`, the host closing the window.
 * `openElsewhere`: the host opens a machine in a window of its own, and says
 * whether it did.
 */
@Composable
fun App(
    machines: MachineRepository,
    settings: SettingsRepository,
    identity: Identity,
    sessions: Sessions,
    host: SessionHost = SessionHost(),
    only: String? = null,
    openElsewhere: (Machine) -> Boolean = { false },
    onLeave: () -> Unit = {},
    strings: Strings = rememberStrings(),
) {
    var open by rememberSaveable { mutableStateOf(only) }
    val machineId = open
    if (machineId == null) {
        LaunchedEffect(Unit) { host.onSession(null) }
        MachinesScreen(
            machines = machines,
            identity = identity,
            onOpen = { if (!openElsewhere(it)) open = it.id },
            settings = settings,
            strings = strings,
        )
        return
    }
    val controller = remember(machineId) { sessions.session(machineId) }
    LaunchedEffect(controller) {
        sessions.front(machineId)
        host.onSession(controller)
    }
    val current by settings.settings.collectAsState()
    val leave: () -> Unit = {
        sessions.endSession(machineId)
        if (only != null) onLeave() else open = null
    }
    SessionScreen(
        controller,
        onBack = leave,
        onClose = leave,
        turns = host.turns,
        onLandscape = host.onLandscape,
        settings = current,
        strings = strings,
    )
}
