package com.singularity.todo

import androidx.compose.material3.Surface
import androidx.compose.ui.window.singleWindowApplication
import com.singularity.todo.core.di.coreLoggingModule
import com.singularity.todo.core.di.domainModule
import com.singularity.todo.core.di.platformModule
import com.singularity.todo.core.log.initLogging
import com.singularity.todo.core.coroutines.createBackgroundScope
import com.singularity.todo.core.coroutines.loggingBackgroundFailureHandler
import com.singularity.todo.feature.profile.ProfileBootstrapper
import okio.Path
import okio.Path.Companion.toPath
import com.singularity.todo.core.version.appVersion
import com.singularity.todo.feature.calendar_sync.work.GOOGLE_SYNC_INTERVAL_MINUTES
import com.singularity.todo.feature.calendar_sync.work.GoogleSyncPeriodicTrigger
import com.singularity.todo.feature.gate.gateModule
import kotlinx.coroutines.launch
import org.koin.core.context.GlobalContext
import org.koin.core.context.startKoin
import java.io.File
import kotlin.time.Duration.Companion.minutes

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
    // Desktop has no crash-reporting backend (JvmCrashReportingPort is a deliberate
    // no-op), so the only sink for an unhandled background failure is the Kermit file
    // log. The policy is still chosen here rather than inherited: a scope with no
    // handler escalates to the platform's uncaught-exception handler.
    createBackgroundScope(loggingBackgroundFailureHandler()).launch {
        ProfileBootstrapper(GlobalContext.get().get()).run()
    }

    // Google Calendar sync runs on the desktop too — it is network plus Room, with no
    // platform API in it — and this is where the desktop entry point arms it. Unlike the
    // app's own sync, which is started by SyncRunner when the user's auto-sync setting is
    // on, there is no setting behind this one yet, so nothing else would ever arm it.
    // `start` is idempotent and the loop re-reads `isConfigured` each cycle, so a user who
    // connects an account later gets background sync without restarting the app.
    GlobalContext.get().get<GoogleSyncPeriodicTrigger>()
        .start(GOOGLE_SYNC_INTERVAL_MINUTES.minutes)

    // SingularityTheme (inside App()) already wraps MaterialTheme.
    // Surface is the root visual container for the window content.
    // LocalDesktopImageLoader() sets Coil bitmap cache to 10% of heap
    // (default is 25%), saving ~80 MB on a 512 MB heap.
    Surface {
        LocalDesktopImageLoader()
        App(deeplinkViewId = null, deeplinkTaskId = null)
    }
}
