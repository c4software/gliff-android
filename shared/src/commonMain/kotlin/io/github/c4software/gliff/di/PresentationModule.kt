package io.github.c4software.gliff.di

import io.github.c4software.gliff.presentation.machines.MachinesViewModel
import io.github.c4software.gliff.presentation.session.SessionViewModel
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

val presentationModule = module {
    viewModel { MachinesViewModel(get(), get(), get(), getOrNull(), get()) }
    viewModel { (machineId: String) -> SessionViewModel(machineId, get(), get(), getOrNull()) }
}
