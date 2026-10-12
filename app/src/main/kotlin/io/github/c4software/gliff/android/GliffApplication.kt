package io.github.c4software.gliff.android

import android.app.Application

class GliffApplication : Application() {
    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        // Made with the process, not with the first screen: the web mirror
        // has to answer whenever the app is alive.
        graph = AppGraph(this)
    }
}
