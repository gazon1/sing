package com.singularity.todo

import androidx.compose.material3.Surface
import androidx.compose.ui.window.singleWindowApplication
import com.singularity.todo.core.di.coreLoggingModule
import com.singularity.todo.core.di.domainModule
import com.singularity.todo.core.di.platformModule
import com.singularity.todo.core.error.runCatchingCancellable
import com.singularity.todo.core.log.initLogging
import com.singularity.todo.core.coroutines.createBackgroundScope
import com.singularity.todo.core.coroutines.loggingBackgroundFailureHandler
import com.singularity.todo.core.work.BackgroundWorkBootstrapper
import com.singularity.todo.feature.profile.ProfileBootstrapper
import okio.Path
import okio.Path.Companion.toPath
import com.singularity.todo.core.version.appVersion
import com.singularity.todo.feature.calendar_sync.work.GOOGLE_SYNC_INTERVAL_MINUTES
import com.singularity.todo.feature.calendar_sync.work.GoogleSyncPeriodicTrigger
import com.singularity.todo.feature.gate.gateModule
import co.touchlab.kermit.Logger
import com.singularity.todo.feature.reminders.JvmReminderFire
import com.singularity.todo.feature.reminders.JvmReminderFireCommand
import com.singularity.todo.feature.reminders.ReminderScheduler
import com.singularity.todo.feature.reminders.domain.port.ReminderRepository
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.koin.core.Koin
import org.koin.core.context.GlobalContext
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import java.io.File
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes

private const val RELEASES_URL = "https://github.com/singularity-todo/singularity/releases"

/**
 * Desktop entry point, and the fire executor a `systemd --user` reminder unit invokes.
 *
 * ## Why this file dispatches on argv
 *
 * A transient systemd unit cannot reach the database, and a reminder's text has to be
 * computed from a fresh read of the task title at fire time. So the unit runs
 * `singularity-todo fire-reminder <id> <userId>` — this binary — and returns from here
 * long before `singleWindowApplication` would initialise AWT or open a window.
 *
 * Reusing the packaged launcher rather than adding a second helper artifact is deliberate:
 * a helper binary is a reminder that silently does not fire on every machine where nobody
 * remembered to install it.
 *
 * Every ordinary launch arrives here with no arguments, so the dispatch is a single
 * `parse` returning null, and the GUI path is unchanged.
 */
fun main(args: Array<String>) {
    val fireRequest = JvmReminderFireCommand.parse(args)
    if (fireRequest != null) {
        fireReminder(fireRequest)
        return
    }

    singleWindowApplication(title = "Singularity Todo") {
        // Ensure data directory exists
        prepareDataDirectory()

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
            // Arm the maintenance jobs (llm_usage retention). This is the same call the
            // Android entry point makes, against the same common class, so both platforms
            // prune on the same schedule instead of drifting apart.
            GlobalContext.get().get<BackgroundWorkBootstrapper>().run()
            // After the profile is active: the reminder rows are read through the active
            // user. This is the Desktop counterpart to Android's BOOT_COMPLETED catch-up,
            // and without it a transient systemd unit is a one-shot reminder that works
            // exactly once.
            rearmReminders(GlobalContext.get())
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
}

/**
 * Re-arm every future reminder at launch.
 *
 * A `systemd --user` **transient** unit dies with the user manager: reboot, logout, or
 * `daemon-reload` all leave the reminder rows in the database with no timer behind them.
 * This is the Desktop counterpart to Android's `ACTION_BOOT_COMPLETED` catch-up, and it is
 * the reason a Desktop reminder survives a restart at all.
 *
 * Enumerates through the repository and re-arms through the port — the same shape as
 * `AlarmReceiver.rescheduleAll`, deliberately including its limit: `observeAll` is scoped
 * to the active profile, so reminders belonging to another profile are re-armed when that
 * profile becomes active, not now. Making them cross-profile would need a
 * profile-independent query that nothing else in the codebase has a reason to have.
 *
 * `schedule` is idempotent while a run is in flight and `systemd-run` refuses to redefine
 * a live unit, so calling this on every launch is safe and does not stack timers.
 */
private suspend fun rearmReminders(koin: Koin) {
    val scheduler = koin.get<ReminderScheduler>()
    if (!scheduler.isSupported) return
    // The graph's clock, not `Clock.System`: every other time decision in this codebase
    // reads it from DI so a test can drive it. This one is not under test, but a second
    // time source in the same file is how the two drift.
    val now = koin.get<Clock>().now().toEpochMilliseconds()
    val log = Logger.withTag("rearm-reminders")
    koin.get<ReminderRepository>().observeAll().first()
        .filter { it.fireAt > now }
        .forEach { reminder ->
            // `schedule` throws when `systemd-run` fails, and that is the correct behaviour
            // for a single arming. In a bulk loop it would abandon every reminder after the
            // first failure — turning one bad unit into reminders that silently never fire,
            // which is the defect this whole feature exists to end. So each arm is caught,
            // logged loudly, and the loop continues.
            //
            // `runCatchingCancellable`, not `runCatching`: the latter also swallows
            // `CancellationException`, which here would mean the app cannot be shut down
            // while it is re-arming.
            runCatchingCancellable { scheduler.schedule(reminder) }.onFailure {
                log.e(it) { "Failed to re-arm reminder ${reminder.id.value}" }
            }
        }
}

/**
 * Runs one `fire-reminder` request to completion, then exits.
 *
 * ## Why `runBlocking` is correct here and nowhere else in this file
 *
 * A `main` that returns before its work finishes exits anyway: the JVM tears down once the
 * entry point returns, taking an in-flight coroutine with it. So the process whose entire
 * purpose is one notification *must* block until that notification is posted or lost. The
 * rule this suppresses exists to stop a long-lived application parking a thread it also
 * needs — this process has no other work, no UI, and no reason to stay alive afterwards,
 * so there is no thread to starve and nothing to deadlock against.
 *
 * The GUI path below is where a background scope belongs, and it does not use this.
 *
 * The profile bootstrap runs first and is not optional — `ReminderRepository.get` filters
 * by the active user, so a unit that fires before the profile resolves finds nothing and
 * reports `NotFound` for a reminder that exists.
 */
@Suppress("NoRunBlocking") // see KDoc: this process exists only to run the block below
private fun fireReminder(request: JvmReminderFireCommand.Request) = runBlocking {
    prepareDataDirectory()
    initLogging(
        isDebug = System.getProperty("singularity.debug") == "true",
        version = appVersion().name,
        logDirectory = System.getProperty("user.home")!!.toPath() / ".singularity-todo" / "logs",
    )
    // Deliberately the same module list the GUI uses, minus `gateModule`: a fire is a
    // database read and one notification, and reaching the release gate to do it would add
    // a network call to the path a user is waiting on for an alarm.
    startKoin {
        modules(listOf(platformModule(), coreLoggingModule()) + domainModule())
    }

    val koin = GlobalContext.get()
    val scope = createBackgroundScope(loggingBackgroundFailureHandler())
    ProfileBootstrapper(koin.get()).run()
    val outcome = JvmReminderFire.fire(
        reminderRepo = koin.get(),
        taskRepo = koin.get(),
        notifier = koin.get(),
        reminderId = request.reminderId,
        userId = request.userId,
    )
    Logger.withTag("fire-reminder").i { "Reminder ${request.reminderId.value}: $outcome" }
    scope.cancel()
    stopKoin()
}

/** Created before anything opens the database; both entry paths need the same three. */
private fun prepareDataDirectory() {
    val dataDir = File("${System.getProperty("user.home")}/.singularity-todo")
    dataDir.mkdirs()
    File("$dataDir/attachments").mkdirs()
    File("$dataDir/backups").mkdirs()
}
