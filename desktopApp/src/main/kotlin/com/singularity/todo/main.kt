package com.singularity.todo

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.window.singleWindowApplication
import com.singularity.todo.core.di.domainModule
import com.singularity.todo.core.di.platformModule
import org.koin.core.context.startKoin
import java.io.File

fun main() = singleWindowApplication(
    title = "Singularity Todo"
) {
    // Ensure data directory exists
    val dataDir = File("${System.getProperty("user.home")}/.singularity-todo")
    dataDir.mkdirs()
    File("$dataDir/attachments").mkdirs()
    File("$dataDir/backups").mkdirs()

    startKoin {
        modules(
            platformModule(),
            domainModule()
        )
    }

    MaterialTheme {
        Surface {
            App()
        }
    }
}
