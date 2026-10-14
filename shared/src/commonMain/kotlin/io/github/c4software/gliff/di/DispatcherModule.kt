package io.github.c4software.gliff.di

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import org.koin.dsl.module

/**
 * The dispatchers, injected everywhere and named only here: a test gives its
 * own, and controls what runs when. One type with a field each, rather than
 * three qualified ones the compiler could not tell apart.
 */
data class AppDispatchers(
    /** The main thread, running what is posted from it at once. */
    val main: CoroutineDispatcher,
    /** Blocking work: ssh, the key, the files. */
    val io: CoroutineDispatcher,
)

val dispatcherModule = module {
    single { AppDispatchers(main = Dispatchers.Main.immediate, io = Dispatchers.IO) }
}
