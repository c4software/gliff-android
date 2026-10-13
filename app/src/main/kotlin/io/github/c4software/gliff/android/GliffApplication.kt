package io.github.c4software.gliff.android

import android.app.Application
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch

class GliffApplication : Application() {
    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        // Made with the process, not with the first screen: the web mirror
        // has to answer whenever the app is alive.
        graph = AppGraph(this)
        // The launcher's shortcuts follow the list of machines.
        MainScope().launch { graph.machines.machines.collect { publishShortcuts(this@GliffApplication, it) } }
    }
}
