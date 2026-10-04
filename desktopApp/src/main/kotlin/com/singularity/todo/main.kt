package com.singularity.todo

import androidx.compose.material3.Surface
import androidx.compose.ui.window.singleWindowApplication
import com.singularity.todo.core.di.coreLoggingModule
import com.singularity.todo.core.di.domainModule
import com.singularity.todo.core.di.platformModule
import com.singularity.todo.core.log.initLogging
import com.singularity.todo.core.coroutines.createBackgroundScope
import com.singularity.todo.feature.profile.ProfileBootstrapper
import okio.Path
import okio.Path.Companion.toPath
import com.singularity.todo.core.version.appVersion
import com.singularity.todo.feature.gate.gateModule
import kotlinx.coroutines.launch
import org.koin.core.context.GlobalContext
import org.koin.core.context.startKoin
import java.io.File

private const val RELEASES_URL = "https://github.com/singularity-todo/singularity/releases"

fun main() = singleWindowApplication(
    title = "Singularity Todo",
) {
    // Ensure data directory exists
    val dataDir = File("${System.getProperty("user.home")}/.singularity-todo")
    dataDir.mkdirs()
    File("$dataDir/attachments").mkdirs()
    File("$dataDir/backups").mkdirs()

    initLogging(
        isDebug = System.getProperty("singularity.debug") == "true",
        version = appVersion().name,
        logDirectory = System.getProperty("user.home")!!.toPath() / ".singularity-todo" / "logs",
    )
    startKoin {
        modules(
            listOf(platformModule(), coreLoggingModule()) +
                domainModule() +
                listOf(gateModule(RELEASES_URL)),
        )
    }

    // Seed the default 'Personal' profile on first launch (idempotent). The
    // profile switcher and the saved-view copy-to-profile picker list
    // ProfileRepository rows; without a seeded row both start empty on a
    // fresh install because nothing else writes to the profiles table.
    createBackgroundScope().launch {
        ProfileBootstrapper(GlobalContext.get().get()).run()
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
