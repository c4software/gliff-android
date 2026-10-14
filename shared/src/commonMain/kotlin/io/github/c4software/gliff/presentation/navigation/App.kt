package io.github.c4software.gliff.presentation.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import io.github.c4software.gliff.domain.machine.Machine
import io.github.c4software.gliff.domain.session.SessionState
import io.github.c4software.gliff.presentation.machines.MachinesRoute
import io.github.c4software.gliff.presentation.session.SessionRoute
import io.github.c4software.gliff.presentation.session.SessionViewModel
import io.github.c4software.gliff.presentation.theme.Strings
import io.github.c4software.gliff.presentation.theme.rememberStrings
import kotlinx.coroutines.flow.StateFlow
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

/** What the window around a session can do, and wants to be told. */
class SessionHost(
    /** The host can lay the window on its side; it does so while [onLandscape] last said true. */
    val turns: Boolean = false,
    val onLandscape: (Boolean) -> Unit = {},
    /** A session is on screen, with this state, or (null) none is any more. */
    val onSession: (StateFlow<SessionState>?) -> Unit = {},
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
        MachinesRoute(onOpen = { if (!openElsewhere(it)) open = it.id }, strings = strings)
        return
    }
    // The session's ViewModel lives as long as it is on screen, and ends it when it goes.
    key(machineId) {
        ViewModelScope {
            val viewModel = koinViewModel<SessionViewModel> { parametersOf(machineId) }
            LaunchedEffect(viewModel) {
                viewModel.onShown()
                host.onSession(viewModel.sessionState)
            }
            val leave: () -> Unit = {
                viewModel.end()
                if (only != null) onLeave() else open = null
            }
            SessionRoute(
                viewModel,
                onBack = leave,
                onClose = leave,
                turns = host.turns,
                onLandscape = host.onLandscape,
                strings = strings,
            )
        }
    }
}

/** ViewModels for `content` alone, cleared when it leaves the composition. */
@Composable
fun ViewModelScope(content: @Composable () -> Unit) {
    val owner = remember {
        object : ViewModelStoreOwner {
            override val viewModelStore = ViewModelStore()
        }
    }
    DisposableEffect(owner) { onDispose { owner.viewModelStore.clear() } }
    CompositionLocalProvider(LocalViewModelStoreOwner provides owner, content = content)
}
