package io.github.c4software.gliff.di

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import org.koin.dsl.module

/** The application's scope, the only one: the sessions and the web mirror live as long as the process. */
val coroutineScopeModule = module {
    single { CoroutineScope(SupervisorJob() + get<AppDispatchers>().main) }
}
