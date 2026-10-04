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
import co.touchlab.kermit.Logger
import com.singularity.todo.core.di.domainModule
import com.singularity.todo.core.observability.CrashReportingPort
import com.singularity.todo.core.observability.JvmCrashReportingPort
import com.singularity.todo.feature.calendar_sync.presentation.CalendarSyncViewModel
import com.singularity.todo.feature.calendar_sync.sync.CalendarSyncOrchestrator
import com.singularity.todo.core.files.FileRevealer
import com.singularity.todo.core.files.FileSharePort
import com.singularity.todo.core.files.FileSystem
import com.singularity.todo.core.files.JvmFileRevealer
import com.singularity.todo.core.files.JvmFileSharePort
import com.singularity.todo.core.files.JvmFileSystem
import com.singularity.todo.core.files.JvmSharePort
import com.singularity.todo.core.files.SharePort
import com.singularity.todo.core.log.LogBundleExporter
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
import com.singularity.todo.feature.calendar_sync.data.NoopCalendarSyncRepositoryImpl
import com.singularity.todo.feature.calendar_sync.domain.port.CalendarAppQueries
import com.singularity.todo.feature.calendar_sync.domain.port.CalendarProviderPort
import com.singularity.todo.feature.calendar_sync.domain.port.CalendarSyncRepository
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
import org.junit.jupiter.api.Tag
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
@Tag("slow")
class KoinGraphValidationTest {

    /**
     * Mirrors [com.singularity.todo.core.di.platformModule] for JVM
     * so this test can run without Android-specific dependencies.
     */
    private fun desktopPlatformModule(): Module = module {
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
        single { get<AppDatabase>().calendarSyncTaskMapDao() }
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

        // ─── Platform Ports ────────────────────────────────────────────
        single<SecureStoragePort> { JvmSecureStorage() }
        single<NotificationPort> { JvmNotificationPort() }
        single<FileSystem> { JvmFileSystem() }
        single<FileRevealer> { JvmFileRevealer() }
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
        single<ReminderScheduler> { JvmReminderScheduler() }

        // ─── Sync (disabled on desktop) ────────────────────────────────
        single<SyncScheduler> { JvmSyncScheduler() }
        single<SyncWorkScheduler> { NoopSyncWorkScheduler() }
        single<CalendarSyncRepository> { NoopCalendarSyncRepositoryImpl() }
        single<CalendarProviderPort> { NoopCalendarProvider() }
        single<CalendarSyncWorkScheduler> { NoopCalendarSyncWorkScheduler() }
        single<CalendarAppQueries> { JvmCalendarAppQueries() }
    }

    @Test
    fun `all singletons resolve without missing bindings`() {
        val app = koinApplication {
            // Composed as a list, not spread as varargs — the shape every production entry
            // point uses. But read the note below before concluding that the composition
            // is what silences KOIN-W003, because it is not: see
            // docs/decisions/2026-10-05-koin-w003-in-a-test-graph.md.
            //
            // This entry point warns and is expected to. The warning means the Koin
            // compiler could not analyse `desktopPlatformModule()` — a ~40-binding
            // test-local mirror of platformModule() — so it skips its checks here. What
            // survives is `checkModules()` below, which resolves the graph for real.
            modules(
                listOf(
                    desktopPlatformModule(),
                    gateModule("https://github.com/singularity-todo/singularity/releases"),
                ) + domainModule(),
            )
        }
        try {
            // Key singletons that are the most failure-prone.
            // If these resolve, the graph is healthy for the desktop app.
            assertNotNull(app.koin.get<RemoteConfigPort>())
            assertNotNull(app.koin.get<SyncScheduler>())
            assertNotNull(app.koin.get<SyncWorkScheduler>())
            assertNotNull(app.koin.get<CalendarSyncRepository>())
            // A Koin definition's lambda body only runs when something *resolves* it, not
            // when the module is defined — so a definition can exist, be correct, and never
            // execute anywhere. That is the "implemented but unwired" shape this repository
            // audits for, and this test is the cheapest place to catch it: resolving is one line.
            //
            // These two were added because the calendar-sync bindings stopped being
            // resolvable-by-accident once they started composing their failure handler from the
            // injected port. The `CrashReportingPort` and `Logger` bindings above are what that
            // change required here, and their absence is exactly the class of defect this test
            // exists to find: a graph that is incomplete in a way nothing else notices.
            assertNotNull(app.koin.get<CalendarSyncOrchestrator>())
            assertNotNull(app.koin.get<CalendarSyncViewModel>())
        } finally {
            app.close()
        }
    }
}
