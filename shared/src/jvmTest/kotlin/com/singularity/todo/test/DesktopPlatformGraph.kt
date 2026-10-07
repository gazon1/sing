package com.singularity.todo.test

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import kotlin.time.Clock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import com.singularity.todo.core.files.FileSourceFactory
import com.singularity.todo.core.files.JvmFileSourceFactory
import com.singularity.todo.core.platform.HostEnvironmentPort
import com.singularity.todo.core.platform.JvmHostEnvironment
import com.singularity.todo.core.platform.haptics.Haptic
import com.singularity.todo.core.platform.haptics.createHaptic
import com.singularity.todo.core.backup.BackupCodec
import com.singularity.todo.core.backup.JvmBackupCodec
import com.singularity.todo.core.database.AppDatabase
import com.singularity.todo.core.database.AppDatabaseFactory
import com.singularity.todo.core.database.RoomUnitOfWork
import com.singularity.todo.core.database.UnitOfWork
import com.singularity.todo.core.database.contract.createSqlDriver
import com.singularity.todo.core.database.contract.wipeIfNotRoomManaged
import co.touchlab.kermit.Logger
import com.singularity.todo.core.observability.CrashReportingPort
import com.singularity.todo.core.observability.JvmCrashReportingPort
import com.singularity.todo.core.files.FileOpener
import com.singularity.todo.core.files.FileRevealer
import com.singularity.todo.core.files.JvmFileOpener
import com.singularity.todo.core.files.FileSharePort
import com.singularity.todo.core.files.FileSystem
import com.singularity.todo.core.files.JvmFileRevealer
import com.singularity.todo.core.files.JvmFileSharePort
import com.singularity.todo.core.files.JvmFileSystem
import com.singularity.todo.core.files.JvmSharePort
import com.singularity.todo.core.files.SharePort
import com.singularity.todo.core.log.LogBundleExporter
import com.singularity.todo.core.security.JvmSecureStorage
import com.singularity.todo.core.security.SecureStoragePort
import com.singularity.todo.core.sync.DelayLoopSyncPeriodicTrigger
import com.singularity.todo.core.sync.SyncPeriodicTrigger
import com.singularity.todo.core.work.BackgroundWorkScheduler
import com.singularity.todo.core.work.BackgroundJobCatalog
import com.singularity.todo.core.work.JvmBackgroundWorkScheduler
import com.singularity.todo.core.sync.work.JvmSyncWorkScheduler
import com.singularity.todo.core.sync.work.SyncWorkScheduler
import com.singularity.todo.feature.calendar_sync.data.JvmCalendarAppQueries
import com.singularity.todo.feature.calendar_sync.data.NoopCalendarProvider
import com.singularity.todo.feature.calendar_sync.data.NoopCalendarSyncRepositoryImpl
import com.singularity.todo.feature.calendar_sync.domain.port.CalendarAppQueries
import com.singularity.todo.feature.calendar_sync.domain.port.CalendarProviderPort
import com.singularity.todo.feature.calendar_sync.domain.port.CalendarSyncRepository
import com.singularity.todo.feature.calendar_sync.sync.GoogleSyncCoordinator
import com.singularity.todo.feature.calendar_sync.work.CalendarSyncWorkScheduler
import com.singularity.todo.feature.calendar_sync.work.DelayLoopGoogleSyncPeriodicTrigger
import com.singularity.todo.feature.calendar_sync.work.GoogleSyncPeriodicTrigger
import com.singularity.todo.feature.calendar_sync.work.NoopCalendarSyncWorkScheduler
import com.singularity.todo.feature.pomodoro.JvmPomodoroTaskListProvider
import com.singularity.todo.feature.pomodoro.JvmPomodoroTimer
import com.singularity.todo.feature.pomodoro.PomodoroTaskListProvider
import com.singularity.todo.feature.pomodoro.PomodoroTimer
import com.singularity.todo.core.notifications.JvmNotifier
import com.singularity.todo.core.notifications.Notifier
import com.singularity.todo.feature.reminders.JvmReminderScheduler
import com.singularity.todo.feature.reminders.ReminderScheduler
import org.koin.core.module.Module
import org.koin.core.qualifier.named
import org.koin.dsl.module
import java.io.File

/**
 * A test-local mirror of `platformModule()` for the JVM.
 *
 * Not a copy for its own sake: the real one opens the user's database and DataStore, which cannot
 * be done twice in a process — the second graph throws "multiple DataStores active for the same
 * file". A graph test needs the bindings, not the storage, so it supplies its own.
 *
 * Shared rather than duplicated because a mirror is exactly the thing that drifts: one test's copy
 * gains a binding the other never sees, and the graph that then looks healthy is one nobody runs.
 * When the real platform module gains a binding a graph depends on, add it here.
 */
// A flat, greppable list is the point: `PlatformModuleMirrorTest` reads this file's
// text to prove the mirror has not drifted from the real module, and a mirror split
// across helper functions is a mirror nobody reads as a whole. The same shape is
// baselined for `aiToolsModule`. Splitting it would trade a two-line rule violation
// for a graph whose completeness is harder to see.
@Suppress("LongMethod")
internal fun desktopPlatformModule(): Module = module {
    // Mirrors `single<CrashReportingPort> { JvmCrashReportingPort() }` from the real
    // PlatformModule.jvm.kt. It became load-bearing when the calendar-sync bindings started
    // composing their own failure handler from the injected port rather than reading a
    // process-wide one: a definition that needs `get<CrashReportingPort>()` is unresolvable
    // in a graph that does not bind one, and this mirror is a graph.
    single<CrashReportingPort> { JvmCrashReportingPort() }
    // Mirrors `coreLoggingModule()`'s Logger binding, for the same reason.
    single { Logger.withTag("App") }

    // ─── Room Database ──────────────────────────────────────────────
    single<AppDatabase> {
        val dbPath = System.getProperty("user.home") +
            "/.singularity-todo/singularity-todo.db"
        File(dbPath).parentFile?.mkdirs()
        wipeIfNotRoomManaged(dbPath)
        AppDatabaseFactory.build(createSqlDriver(), dbPath)
    }

    // `domainModule()` binds TaskRepository over a UnitOfWork, so without this every
    // repository in the graph is unresolvable and the failure surfaces one level away —
    // as "could not create TaskRepository", naming the wrong thing entirely. It went
    // missing when this mirror was moved out of KoinGraphValidationTest.kt.
    single<UnitOfWork> { RoomUnitOfWork(get()) }

    single { get<AppDatabase>().taskDao() }
    single { get<AppDatabase>().noteDao() }
    single { get<AppDatabase>().projectDao() }
    single { get<AppDatabase>().tagDao() }
    single { get<AppDatabase>().syncOutboxDao() }
    single { get<AppDatabase>().syncDeadLetterDao() }
    single { get<AppDatabase>().syncStateDao() }
    single { get<AppDatabase>().syncShadowDao() }
    single { get<AppDatabase>().remoteConfigDao() }
    single { get<AppDatabase>().remoteConfigCacheDao() }
    single { get<AppDatabase>().attachmentDao() }
    single { get<AppDatabase>().annotationDao() }
    single { get<AppDatabase>().reminderDao() }
    single { get<AppDatabase>().projectReminderDao() }
    single { get<AppDatabase>().checklistDao() }
    single { get<AppDatabase>().llmUsageDao() }
    single { get<AppDatabase>().profileDao() }
    single { get<AppDatabase>().agendaViewDao() }
    single { get<AppDatabase>().calendarSyncTaskMapDao() }
    // The three Google-sync DAOs, added to the mirror with the desktop platform module's
    // bindings. PlatformModuleMirrorTest checks this direction: a DAO bound on desktop and
    // absent here means the graph test resolves a graph the app cannot build.
    single { get<AppDatabase>().calendarSyncStateDao() }
    single { get<AppDatabase>().googleEventShadowDao() }
    single { get<AppDatabase>().calendarImportEventDao() }
    single { get<AppDatabase>().savedSearchDao() }
    single { get<AppDatabase>().timeEntryDao() }
    single { get<AppDatabase>().proposalDao() }
    single { get<AppDatabase>().proposalItemDao() }
    single { get<AppDatabase>().tagGroupDao() }
    single { get<AppDatabase>().projectInheritedTagGroupDao() }

    // ─── DataStore ─────────────────────────────────────────────────
    val userHome = System.getProperty("user.home")
    val userSettingsDs: DataStore<Preferences> =
        androidx.datastore.preferences.core.PreferenceDataStoreFactory.create {
            File(userHome, ".singularity-todo/user_settings.preferences_pb")
        }
    single<DataStore<Preferences>> { userSettingsDs }

    // Mirrors the named `calendar_sync` DataStore in PlatformModule.jvm.kt. Google sync
    // runs on desktop, so the coordinator's settings dependency has to resolve here too
    // — and the mirror test is what notices when a platform module gains a binding the
    // mirror lacks.
    single<DataStore<Preferences>>(qualifier = named("calendar_sync")) {
        androidx.datastore.preferences.core.PreferenceDataStoreFactory.create {
            File(userHome, ".singularity-todo/calendar_sync.preferences_pb")
        }
    }

    // ─── Platform Ports ────────────────────────────────────────────
    //
    // The four below were bound in `PlatformModule.jvm.kt` and absent here, and nothing
    // complained for the whole time they were missing: the mirror was extracted from a
    // copy predating them, and a mirror drift is invisible to the source-scanning checks
    // because the missing entry is exactly the entry nothing refers to. They are here so
    // that a test which does resolve one of them gets the real JVM implementation rather
    // than a failure that looks like a defect in the code under test.
    single<SecureStoragePort> { JvmSecureStorage() }
    single<Haptic> { createHaptic() }
    single<FileSystem> { JvmFileSystem() }
    single<HostEnvironmentPort> { JvmHostEnvironment() }
    single<FileRevealer> { JvmFileRevealer() }
    single<FileOpener> { JvmFileOpener() }
    single<FileSourceFactory> { JvmFileSourceFactory() }
    single<SharePort> { JvmSharePort() }
    single<FileSharePort> { JvmFileSharePort() }
    single<BackupCodec> { JvmBackupCodec() }
    single<String> { "$userHome/.singularity-todo/backups" }
    single<String> { "$userHome/.singularity-todo/logs" }
    single { LogBundleExporter(get(), get(), get()) }

    // ─── Pomodoro ──────────────────────────────────────────────────
    single<PomodoroTaskListProvider> { JvmPomodoroTaskListProvider() }
    single { com.singularity.todo.feature.pomodoro.PomodoroConfig() }
    factory<PomodoroTimer> { JvmPomodoroTimer(get(), get(), get(), get(), get(), get()) }

    // ─── Reminders ─────────────────────────────────────────────────
    // Real collaborators, not stubs. `isSupported` is the conjunction of a systemd probe
    // and `notifier.isSupported`; handing this graph fakes would let a desktop harness
    // report reminder support it never actually has, which is the defect this file's
    // other bindings exist to prevent.
    single<Notifier> { JvmNotifier(get()) }
    single<ReminderScheduler> { JvmReminderScheduler(get(), get(), get()) }

    // A background scope owned by the graph, matching the desktop module's. It was
    // missing here as well, and it is invisible to a dependency scan for the same reason
    // `UnitOfWork` was: `DelayLoopSyncPeriodicTrigger` resolves it through a bare
    // `get()`, so the type is never named anywhere a `get<X>()` scan can see it.
    single<CoroutineScope> { CoroutineScope(Dispatchers.Unconfined) }

    // ─── Sync (disabled on desktop) ────────────────────────────────
    //
    // `UnitOfWork` is here because a repository's `unitOfWork.write { … }` is resolved
    // the moment the repository is constructed, so a graph without it cannot build a
    // `TaskRepository` at all. It was in the mirror, and `dc7f1d5d` extracted this
    // helper from a copy that predated the unit-of-work work — silently dropping it.
    // `PlatformModuleMirrorTest` did not catch it because the mirror-vs-source check
    // cannot see a binding declared in the source and absent here when nothing in the
    // mirror references it; the DAO scan sees DAOs, and `UnitOfWork` is not a DAO.
    single<UnitOfWork> { RoomUnitOfWork(get()) }
    single<SyncPeriodicTrigger> {
        DelayLoopSyncPeriodicTrigger(request = { }, scope = CoroutineScope(Dispatchers.Unconfined))
    }
    single<SyncWorkScheduler> { JvmSyncWorkScheduler(get()) }
    single<BackgroundWorkScheduler> { testBackgroundScheduler(get()) }
    single<CalendarSyncRepository> { NoopCalendarSyncRepositoryImpl() }
    single<CalendarProviderPort> { NoopCalendarProvider() }
    single<CalendarSyncWorkScheduler> { NoopCalendarSyncWorkScheduler() }
    single<CalendarAppQueries> { JvmCalendarAppQueries() }

    // Mirrors PlatformModule.jvm.kt. Google sync is supported on desktop, so this is a
    // real binding and not a no-op — a mirror that quietly omitted it would leave the
    // desktop graph untested exactly where it is now needed.
    single<GoogleSyncPeriodicTrigger> {
        DelayLoopGoogleSyncPeriodicTrigger(
            coordinatorProvider = { get<GoogleSyncCoordinator>() },
            scope = CoroutineScope(Dispatchers.Unconfined),
        )
    }
}

/**
 * A scheduler for the graph test that runs on a scope nothing will ever cancel.
 *
 * The graph is built to prove it resolves; these jobs are never scheduled in this test, and
 * a scope tied to the test body would either leak the loop or need a cleanup path that the
 * assertion does not need.
 */
@Suppress("NoDirectClockSystem") // a graph test has nothing to inject from
private fun testBackgroundScheduler(catalog: BackgroundJobCatalog) = JvmBackgroundWorkScheduler(
    catalog = catalog,
    clock = Clock.System,
    scope = CoroutineScope(Dispatchers.Unconfined),
    crashReporter = com.singularity.todo.core.observability.NoOpCrashReportingPort(),
)
