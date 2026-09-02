package com.singularity.todo

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.window.singleWindowApplication
import com.singularity.todo.App
import com.singularity.todo.core.di.sharedModule
import org.koin.core.context.startKoin

fun main() = singleWindowApplication(
    title = "Singularity Todo"
) {
    startKoin {
        // Note: Full DI setup requires platform-specific modules with database and settings
        // For now, this is a minimal startup
    }

    MaterialTheme {
        Surface {
            App()
        }
    }
}
