package com.singularity.todo.core.di

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import com.singularity.todo.core.backup.BackupCodec
import com.singularity.todo.core.backup.JvmBackupCodec
import com.singularity.todo.core.database.AppDatabase
import com.singularity.todo.core.database.AppDatabaseFactory
import com.singularity.todo.core.database.contract.createSqlDriver
import com.singularity.todo.core.database.contract.wipeIfNotRoomManaged
import com.singularity.todo.core.files.FileRevealer
import com.singularity.todo.core.files.FileSharePort
import com.singularity.todo.core.files.FileSourceFactory
import com.singularity.todo.core.files.FileSystem
import com.singularity.todo.core.files.JvmFileRevealer
import com.singularity.todo.core.files.JvmFileSourceFactory
import com.singularity.todo.core.files.JvmFileSharePort
import com.singularity.todo.core.files.JvmFileSystem
import com.singularity.todo.core.files.JvmSharePort
import com.singularity.todo.core.files.SharePort
import com.singularity.todo.core.log.LogBundleExporter
import com.singularity.todo.core.observability.CrashReportingPort
import com.singularity.todo.core.observability.JvmCrashReportingPort
import com.singularity.todo.core.observability.crashReportingFailureHandler
import com.singularity.todo.core.platform.HostEnvironmentPort
import com.singularity.todo.core.platform.JvmHostEnvironment
import com.singularity.todo.core.platform.haptics.Haptic
import com.singularity.todo.core.platform.haptics.createHaptic
import com.singularity.todo.core.security.JvmSecureStorage
import com.singularity.todo.core.security.SecureStoragePort
import com.singularity.todo.core.settings.SettingsDataStoreMigration
import com.singularity.todo.core.sync.DelayLoopSyncPeriodicTrigger
import com.singularity.todo.core.sync.SyncCoordinator
import com.singularity.todo.core.sync.SyncPeriodicTrigger
import com.singularity.todo.core.sync.work.JvmSyncWorkScheduler
import com.singularity.todo.core.work.BackgroundWorkScheduler
import com.singularity.todo.core.work.JvmBackgroundWorkScheduler
import com.singularity.todo.core.sync.work.SyncWorkScheduler
import com.singularity.todo.feature.calendar_sync.data.JvmCalendarAppQueries
import com.singularity.todo.feature.calendar_sync.data.NoopCalendarProvider
import com.singularity.todo.feature.calendar_sync.data.NoopCalendarSyncRepositoryImpl
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
import com.singularity.todo.feature.reminders.JvmReminderScheduler
import com.singularity.todo.feature.reminders.ReminderScheduler
import org.koin.core.module.Module
import org.koin.core.qualifier.named
import org.koin.dsl.module
import java.io.File
import kotlinx.coroutines.CoroutineScope
import com.singularity.todo.core.coroutines.createBackgroundScope
import com.singularity.todo.core.database.RoomUnitOfWork
import com.singularity.todo.core.database.UnitOfWork

/**
 * JVM/desktop platform bindings — Room 3 (same stack as Android, no extra native deps).
 *
 * Database layout mirrors `PlatformModule.android.kt`: one [AppDatabase] singleton, all
 * DAOs wired through Koin.
 */
actual fun platformModule(): Module = module {
    // ─── Room Database ────────────────────────────────────────────────────

    single<AppDatabase> {
        val dbPath = System.getProperty("user.home") + "/.singularity-todo/singularity-todo.db"
        java.io.File(dbPath).parentFile?.mkdirs()
        // On the JVM the path may contain a pre-Room hand-rolled SQLite file
        // (user_version=0, wrong column set). Wipe it before Room opens the connection
        // so fallbackToDestructiveMigration can recreate the schema cleanly.
        wipeIfNotRoomManaged(dbPath)
        AppDatabaseFactory.build(createSqlDriver(), dbPath)
    }

    // One write transaction for the repositories that write a synced row and the
    // patch describing it. See ADR
    // 2026-10-05-who-owns-a-row-and-the-patch-that-describes-it.
    single<UnitOfWork> { RoomUnitOfWork(get()) }

    single { get<AppDatabase>().taskDao() }
    single { get<AppDatabase>().noteDao() }
    single { get<AppDatabase>().projectDao() }
    single { get<AppDatabase>().tagDao() }
    single { get<AppDatabase>().tagGroupDao() }
    single { get<AppDatabase>().projectInheritedTagGroupDao() }
    single { get<AppDatabase>().syncOutboxDao() }
    single { get<AppDatabase>().syncDeadLetterDao() }
    single { get<AppDatabase>().syncStateDao() }
    single { get<AppDatabase>().syncShadowDao() }
    single { get<AppDatabase>().remoteConfigDao() }
    single { get<AppDatabase>().remoteConfigCacheDao() }
    single { get<AppDatabase>().attachmentDao() }
    single { get<AppDatabase>().reminderDao() }
    single { get<AppDatabase>().projectReminderDao() }
    single { get<AppDatabase>().checklistDao() }
    single { get<AppDatabase>().llmUsageDao() }
    single { get<AppDatabase>().profileDao() }
    single { get<AppDatabase>().agendaViewDao() }
    single { get<AppDatabase>().savedSearchDao() }
    // The three Google-sync DAOs. Bound on desktop for the same reason they are bound on
    // Android: the engine resolves them by type, so a desktop graph without them throws
    // NoDefinitionFoundException on the first pass instead of skipping one.
    single { get<AppDatabase>().calendarSyncStateDao() }
    single { get<AppDatabase>().googleEventShadowDao() }
    single { get<AppDatabase>().calendarImportEventDao() }
    single { get<AppDatabase>().timeEntryDao() }
    single { get<AppDatabase>().proposalDao() }
    single { get<AppDatabase>().proposalItemDao() }

    // ─── DataStore (split: user settings + state) ─────────────────────────
    // File-level caching: the SAME DataStore instance is returned for the same
    // file path regardless of how many times PreferenceDataStoreFactory.create {}
    // is called. This prevents "multiple DataStores active for the same file"
    // errors in tests where platformModule() may be called more than once.
    val userHome = System.getProperty("user.home")
    val userSettingsDs: DataStore<Preferences> =
        cachedJvmDataStore(File(userHome, ".singularity-todo/user_settings.preferences_pb"))
    val stateDs: DataStore<Preferences> =
        cachedJvmDataStore(File(userHome, ".singularity-todo/state.preferences_pb"))
    val settingsLegacyDs: DataStore<Preferences> =
        cachedJvmDataStore(File(userHome, ".singularity-todo/settings.preferences_pb"))

    // One-shot migration: v0 flat-key settings → v1 split + namespaced.
    // Idempotent: skips if state.preferences_pb already has settings_schema_version.
    koinBridge {
        SettingsDataStoreMigration(settingsLegacyDs, userSettingsDs, stateDs).run()
    }

    // Named DataStore bindings — used by SettingsRepository and migration.
    single(qualifier = named("user_settings")) { userSettingsDs }
    single(qualifier = named("state")) { stateDs }
    single(qualifier = named("settings")) { settingsLegacyDs }

    // Primary DataStore<Preferences> binding — what SettingsRepository consumes.
    single<DataStore<Preferences>> { userSettingsDs }

    // Separate file for the calendar-sync settings, mirroring the Android binding.
    // Google's chosen calendar lives here, and without it the desktop trigger's first
    // cycle cannot resolve GoogleSyncCoordinator — a missing DataStore that surfaces as
    // "background sync silently does nothing" rather than as a startup crash.
    single(qualifier = named("calendar_sync")) {
        cachedJvmDataStore(File(userHome, ".singularity-todo/calendar_sync.preferences_pb"))
    }

    // ─── Platform Ports ────────────────────────────────────────────────

    single<SecureStoragePort> { JvmSecureStorage() }

    // NoOp on JVM — TaskTitleRow / ChecklistItemRow inject Haptic unconditionally,
    // so the definition must exist or task detail composition fails.
    single<Haptic> { createHaptic() }

    single<FileSystem> { JvmFileSystem() }

    single<HostEnvironmentPort> { JvmHostEnvironment() }

    single<FileRevealer> { JvmFileRevealer() }

    single<FileSourceFactory> { JvmFileSourceFactory() }

    single<SharePort> { JvmSharePort() }

    single<FileSharePort> { JvmFileSharePort() }

    single<BackupCodec> { JvmBackupCodec() }

    // NoOp on JVM — the definition must exist even though it is inert, or
    // injection throws inside composition and Compose retries every frame.
    single<CrashReportingPort> { JvmCrashReportingPort() }

    single<String> { userHome + "/.singularity-todo/backups" }

    single<String> { userHome + "/.singularity-todo/logs" }

    single { LogBundleExporter(get(), get(), get()) }

    // ─── Pomodoro Timer ─────────────────────────────────────────────────

    single<PomodoroTaskListProvider> { JvmPomodoroTaskListProvider() }

    // Present on Android and missing here, which is why the desktop graph could
    // not be built: `DelayLoopSyncPeriodicTrigger` below resolves a CoroutineScope
    // through a bare `get()`, so neither the Koin compiler's diagnostic nor the
    // DAO scan in PlatformModuleMirrorTest could see the dependency — a type that
    // is never named in a `get<X>()` is invisible to both. The binding itself is
    // a background scope owned by the graph, identical to the Android one.
    single<CoroutineScope> { createBackgroundScope(crashReportingFailureHandler(get())) }
    single { com.singularity.todo.feature.pomodoro.PomodoroConfig() }
    factory<PomodoroTimer> {
        JvmPomodoroTimer(
            get(),
            get(),
            get(),
            com.singularity.todo.core.coroutines.createBackgroundScope(crashReportingFailureHandler(get())),
            get(),
            get(),
        )
    }

    // ─── Reminder Scheduler ────────────────────────────────────────────

    single<ReminderScheduler> { JvmReminderScheduler() }

    // ─── Sync Scheduler ─────────────────────────────────────────────────

    // Periodic sync on the JVM: a delay loop. The desktop has no background job
    // scheduler, and this is the trigger that actually runs — the previous type test
    // (`scheduler is NoOpSyncScheduler`) never selected it on the JVM binding.
    single<SyncPeriodicTrigger> {
        DelayLoopSyncPeriodicTrigger(
            request = { get<SyncCoordinator>().request() },
            scope = get(),
        )
    }

    // WorkManager scheduler — JVM no-op stub (sync is not supported on desktop).
    // Real, not a no-op: `SyncEngine` calls enqueuePush() on every sign-in, and this used
    // to discard it silently. Registry row `SyncWorkScheduler` records why.
    single<SyncWorkScheduler> { JvmSyncWorkScheduler(get()) }
    single<BackgroundWorkScheduler> { JvmBackgroundWorkScheduler(get(), get(), get(), crashReporter = get()) }

    // ─── Calendar Sync ────────────────────────────────────────────────

    // Calendar sync is Android-only; JVM provides no-op stubs.
    single<CalendarSyncRepository> {
        NoopCalendarSyncRepositoryImpl()
    }

    single<CalendarProviderPort> {
        NoopCalendarProvider()
    }

    single<CalendarSyncWorkScheduler> {
        NoopCalendarSyncWorkScheduler()
    }

    single<com.singularity.todo.feature.calendar_sync.domain.port.CalendarAppQueries> {
        JvmCalendarAppQueries()
    }

    // Google sync on the desktop: the same delay loop the app's own sync uses, because
    // Google sync is network plus Room and needs no platform API. The system calendar sync
    // above is a no-op here for the opposite reason — it is a ContentResolver projection.
    //
    // The coordinator is resolved per cycle rather than captured, so a profile switch is
    // picked up by the next pass instead of being frozen at startup.
    single<GoogleSyncPeriodicTrigger> {
        DelayLoopGoogleSyncPeriodicTrigger(
            coordinatorProvider = { get<GoogleSyncCoordinator>() },
            scope = get(),
        )
    }
}

/**
 * Process-wide cache: ensures the same [DataStore] instance is returned for the
 * same file path. [PreferenceDataStoreFactory.create] uses FileLock so only one
 * DataStore can be open per file — caching prevents the "multiple DataStores active"
 * error when [platformModule] is called multiple times (e.g. in tests).
 */
private val dataStoreCache = mutableMapOf<String, DataStore<Preferences>>()

private fun cachedJvmDataStore(file: File): DataStore<Preferences> = dataStoreCache.getOrPut(file.absolutePath) {
    PreferenceDataStoreFactory.create { file }
}
