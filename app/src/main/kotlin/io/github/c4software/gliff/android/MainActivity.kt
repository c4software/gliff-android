package io.github.c4software.gliff.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import io.github.c4software.gliff.ui.GliffTheme
import io.github.c4software.gliff.ui.MachinesScreen

/** The list of machines. Each machine opens in a window of its own. */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val graph = (application as GliffApplication).graph
        setContent {
            GliffTheme {
                MachinesScreen(
                    machines = graph.machines,
                    identity = graph.identity,
                    onOpen = { startActivity(SessionActivity.intent(this, it.id)) },
                    settings = graph.settings,
                    mirrorUrls = graph::mirrorUrls,
                )
            }
        }
    }
}
