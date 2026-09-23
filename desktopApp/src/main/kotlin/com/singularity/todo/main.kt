package com.singularity.todo

import androidx.compose.material3.Surface
import androidx.compose.ui.window.singleWindowApplication
import com.singularity.todo.core.di.coreLoggingModule
import com.singularity.todo.core.di.domainModule
import com.singularity.todo.core.di.platformModule
import com.singularity.todo.core.log.initLogging
import com.singularity.todo.core.version.appVersion
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

    initLogging(System.getProperty("singularity.debug") == "true", version = appVersion().name)
    startKoin {
            modules(
                platformModule(),
                coreLoggingModule(),
                *domainModule().toTypedArray(),
            )
    }

    // SingularityTheme (inside App()) already wraps MaterialTheme.
    // Surface is the root visual container for the window content.
    // LocalDesktopImageLoader() sets Coil bitmap cache to 10% of heap
    // (default is 25%), saving ~80 MB on a 512 MB heap.
    Surface {
        LocalDesktopImageLoader()
        App(deeplinkViewId = null, deeplinkTaskId = null)
    }
}
