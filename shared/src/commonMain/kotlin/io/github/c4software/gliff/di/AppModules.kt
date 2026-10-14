package io.github.c4software.gliff.di

import org.koin.core.module.Module

/** The whole graph, around `platform`: what the platform provides, see [dataModule]. */
fun appModules(platform: Module): List<Module> =
    listOf(dispatcherModule, timeModule, coroutineScopeModule, dataModule, presentationModule, platform)
