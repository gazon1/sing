package com.singularity.todo

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.window.singleWindowApplication
import com.singularity.todo.App
import org.koin.core.context.startKoin

fun main() = singleWindowApplication(
    title = "Singularity Todo"
) {
    // Desktop: Full DI wiring deferred until Supabase SDK integration
    // For now, app starts without sync/auth (anonymous mode)
    startKoin {
        // Minimal setup - sync/auth will be stubs until SDK integrated
    }

    MaterialTheme {
        Surface {
            App()
        }
    }
}
