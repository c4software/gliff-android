package io.github.c4software.gliff.android

import android.app.Application
import io.github.c4software.gliff.di.androidModule
import io.github.c4software.gliff.di.appModules
import io.github.c4software.gliff.domain.machine.MachineRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.koin.core.context.startKoin

class GliffApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // Made with the process, not with the first screen: the web mirror
        // has to answer whenever the app is alive.
        val koin = startKoin { modules(appModules(androidModule(this@GliffApplication))) }.koin
        // The launcher's shortcuts follow the list of machines.
        koin.get<CoroutineScope>().launch {
            koin.get<MachineRepository>().machines.collect { publishShortcuts(this@GliffApplication, it) }
        }
    }
}
