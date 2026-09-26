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
import com.singularity.todo.core.files.FileSystem
import com.singularity.todo.core.files.JvmFileRevealer
import com.singularity.todo.core.files.JvmFileSystem
import com.singularity.todo.core.notifications.JvmNotificationPort
import com.singularity.todo.core.notifications.NotificationPort
import com.singularity.todo.core.security.JvmSecureStorage
import com.singularity.todo.core.security.SecureStoragePort
import com.singularity.todo.core.settings.SettingsDataStoreMigration
import com.singularity.todo.core.sync.JvmSyncScheduler
import com.singularity.todo.core.sync.SyncScheduler
import com.singularity.todo.core.sync.work.NoopSyncWorkScheduler
import com.singularity.todo.core.sync.work.SyncWorkScheduler
import com.singularity.todo.feature.calendar_sync.data.JvmCalendarAppQueries
import com.singularity.todo.feature.calendar_sync.data.NoopCalendarProvider
import com.singularity.todo.feature.calendar_sync.data.NoopCalendarSyncRepository
import com.singularity.todo.feature.calendar_sync.domain.port.CalendarProviderPort
import com.singularity.todo.feature.calendar_sync.domain.repository.CalendarSyncRepository
import com.singularity.todo.feature.calendar_sync.work.CalendarSyncWorkScheduler
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

    single { get<AppDatabase>().taskDao() }
    single { get<AppDatabase>().noteDao() }
    single { get<AppDatabase>().projectDao() }
    single { get<AppDatabase>().tagDao() }
    single { get<AppDatabase>().syncOutboxDao() }
    single { get<AppDatabase>().remoteConfigDao() }
    single { get<AppDatabase>().remoteConfigCacheDao() }
    single { get<AppDatabase>().attachmentDao() }
    single { get<AppDatabase>().reminderDao() }
    single { get<AppDatabase>().checklistDao() }
    single { get<AppDatabase>().llmUsageDao() }
    single { get<AppDatabase>().profileDao() }
    single { get<AppDatabase>().agendaViewDao() }

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
<<<<<<< HEAD
    koinBridge {
        SettingsDataStoreMigration(settingsLegacyDs, userSettingsDs, stateDs).run()
    }
=======
    koinBridge { SettingsDataStoreMigration(settingsLegacyDs, userSettingsDs, stateDs).run() }
>>>>>>> 3c08f497 (chore(preflight): quick wins — activate detekt rules, koinBridge refactor, dead code, clock injection)

    // Named DataStore bindings — used by SettingsRepository and migration.
    single(qualifier = named("user_settings")) { userSettingsDs }
    single(qualifier = named("state")) { stateDs }
    single(qualifier = named("settings")) { settingsLegacyDs }

    // Primary DataStore<Preferences> binding — what SettingsRepository consumes.
    single<DataStore<Preferences>> { userSettingsDs }

    // ─── Platform Ports ────────────────────────────────────────────────

    single<SecureStoragePort> { JvmSecureStorage() }

    single<NotificationPort> { JvmNotificationPort() }

    single<FileSystem> { JvmFileSystem() }

    single<FileRevealer> { JvmFileRevealer() }

    single<BackupCodec> { JvmBackupCodec() }

    single<String> { userHome + "/.singularity-todo/backups" }

    // ─── Pomodoro Timer ─────────────────────────────────────────────────

    single<PomodoroTaskListProvider> { JvmPomodoroTaskListProvider() }
    factory<PomodoroTimer> { JvmPomodoroTimer() }

    // ─── Reminder Scheduler ────────────────────────────────────────────

    single<ReminderScheduler> { JvmReminderScheduler() }

    // ─── Sync Scheduler ─────────────────────────────────────────────────

    // Legacy scheduler (used by SyncRunner push loop)
    single<SyncScheduler> { JvmSyncScheduler() }

    // WorkManager scheduler — JVM no-op stub (sync is not supported on desktop).
    single<SyncWorkScheduler> { NoopSyncWorkScheduler() }

    // ─── Calendar Sync ────────────────────────────────────────────────

    // Calendar sync is Android-only; JVM provides no-op stubs.
    single<CalendarSyncRepository> {
        NoopCalendarSyncRepository()
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
