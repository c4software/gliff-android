package io.github.c4software.gliff.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.view.WindowCompat
import io.github.c4software.gliff.presentation.machines.MachinesRoute
import io.github.c4software.gliff.presentation.theme.GliffTheme

/** The list of machines. Each machine opens in a window of its own. */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContent {
            GliffTheme {
                MachinesRoute(onOpen = { startActivity(SessionActivity.intent(this, it.id)) })
            }
        }
    }
}
