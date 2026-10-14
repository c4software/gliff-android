package io.github.c4software.gliff.di

import io.github.c4software.gliff.domain.time.Clock
import kotlin.time.ExperimentalTime
import org.koin.dsl.module

/** The wall clock, read here and nowhere else. */
@OptIn(ExperimentalTime::class)
val timeModule = module {
    single<Clock> { Clock { kotlin.time.Clock.System.now().toEpochMilliseconds() } }
}
