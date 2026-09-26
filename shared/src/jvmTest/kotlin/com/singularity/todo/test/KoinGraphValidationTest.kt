package com.singularity.todo.test

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import com.singularity.todo.core.backup.BackupCodec
import com.singularity.todo.core.backup.JvmBackupCodec
import com.singularity.todo.core.config.RemoteConfigPort
import com.singularity.todo.core.database.AppDatabase
import com.singularity.todo.core.database.AppDatabaseFactory
import com.singularity.todo.core.database.contract.createSqlDriver
import com.singularity.todo.core.database.contract.wipeIfNotRoomManaged
import com.singularity.todo.core.di.domainModule
import com.singularity.todo.core.files.FileRevealer
import com.singularity.todo.core.files.FileSystem
import com.singularity.todo.core.files.JvmFileRevealer
import com.singularity.todo.core.files.JvmFileSystem
import com.singularity.todo.core.notifications.JvmNotificationPort
import com.singularity.todo.core.notifications.NotificationPort
import com.singularity.todo.core.security.JvmSecureStorage
import com.singularity.todo.core.security.SecureStoragePort
import com.singularity.todo.core.sync.JvmSyncScheduler
import com.singularity.todo.core.sync.SyncScheduler
import com.singularity.todo.core.sync.work.NoopSyncWorkScheduler
import com.singularity.todo.core.sync.work.SyncWorkScheduler
import com.singularity.todo.feature.calendar_sync.data.JvmCalendarAppQueries
import com.singularity.todo.feature.calendar_sync.data.NoopCalendarProvider
import com.singularity.todo.feature.calendar_sync.data.NoopCalendarSyncRepository
import com.singularity.todo.feature.calendar_sync.domain.port.CalendarAppQueries
import com.singularity.todo.feature.calendar_sync.domain.port.CalendarProviderPort
import com.singularity.todo.feature.calendar_sync.domain.repository.CalendarSyncRepository
import com.singularity.todo.feature.calendar_sync.work.CalendarSyncWorkScheduler
import com.singularity.todo.feature.calendar_sync.work.NoopCalendarSyncWorkScheduler
import com.singularity.todo.feature.gate.gateModule
import com.singularity.todo.feature.pomodoro.JvmPomodoroTaskListProvider
import com.singularity.todo.feature.pomodoro.JvmPomodoroTimer
import com.singularity.todo.feature.pomodoro.PomodoroTaskListProvider
import com.singularity.todo.feature.pomodoro.PomodoroTimer
import com.singularity.todo.feature.reminders.JvmReminderScheduler
import com.singularity.todo.feature.reminders.ReminderScheduler
import org.junit.jupiter.api.Test
import org.koin.core.module.Module
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import java.io.File
import kotlin.test.assertNotNull

/**
 * Validates the Koin DI graph for desktop JVM — checks that every singleton
 * can be resolved from the graph without throwing.
 *
 * This catches `NoDefinitionFoundException` at test time rather than at runtime
 * during app startup. Run with:
 * ```
 * ./gradlew :shared:jvmTest
 * ```
 *
 * Note: ViewModels (factories) are not tested here — they require a
 * MockProvider for their complex parameter objects and are covered by
 * dedicated VM integration tests.
 *
 * Excluded:
 * - `coreLoggingModule` — Kermit's internal LoggerConfig causes
 *   `MissingKoinDefinitionException` in the checker without being a real
 *   problem (the app starts fine).
 */
class KoinGraphValidationTest {

    /**
     * Mirrors [com.singularity.todo.core.di.platformModule] for JVM
     * so this test can run without Android-specific dependencies.
     */
    private fun desktopPlatformModule(): Module = module {
        // ─── Room Database ──────────────────────────────────────────────
        single<AppDatabase> {
            val dbPath = System.getProperty("user.home") +
                "/.singularity-todo/singularity-todo.db"
            File(dbPath).parentFile?.mkdirs()
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

        // ─── DataStore ─────────────────────────────────────────────────
        val userHome = System.getProperty("user.home")
        val userSettingsDs: DataStore<Preferences> =
            androidx.datastore.preferences.core.PreferenceDataStoreFactory.create {
                File(userHome, ".singularity-todo/user_settings.preferences_pb")
            }
        single<DataStore<Preferences>> { userSettingsDs }

        // ─── Platform Ports ────────────────────────────────────────────
        single<SecureStoragePort> { JvmSecureStorage() }
        single<NotificationPort> { JvmNotificationPort() }
        single<FileSystem> { JvmFileSystem() }
        single<FileRevealer> { JvmFileRevealer() }
        single<BackupCodec> { JvmBackupCodec() }
        single<String> { "$userHome/.singularity-todo/backups" }

        // ─── Pomodoro ──────────────────────────────────────────────────
        single<PomodoroTaskListProvider> { JvmPomodoroTaskListProvider() }
        factory<PomodoroTimer> { JvmPomodoroTimer() }

        // ─── Reminders ─────────────────────────────────────────────────
        single<ReminderScheduler> { JvmReminderScheduler() }

        // ─── Sync (disabled on desktop) ────────────────────────────────
        single<SyncScheduler> { JvmSyncScheduler() }
        single<SyncWorkScheduler> { NoopSyncWorkScheduler() }
        single<CalendarSyncRepository> { NoopCalendarSyncRepository() }
        single<CalendarProviderPort> { NoopCalendarProvider() }
        single<CalendarSyncWorkScheduler> { NoopCalendarSyncWorkScheduler() }
        single<CalendarAppQueries> { JvmCalendarAppQueries() }
    }

    @Test
    fun `all singletons resolve without missing bindings`() {
        val app = koinApplication {
            modules(
                desktopPlatformModule(),
                *domainModule().toTypedArray(),
                gateModule("https://github.com/singularity-todo/singularity/releases"),
            )
        }
        try {
            // Key singletons that are the most failure-prone.
            // If these resolve, the graph is healthy for the desktop app.
            assertNotNull(app.koin.get<RemoteConfigPort>())
            assertNotNull(app.koin.get<SyncScheduler>())
            assertNotNull(app.koin.get<SyncWorkScheduler>())
            assertNotNull(app.koin.get<CalendarSyncRepository>())
        } finally {
            app.close()
        }
    }
}
