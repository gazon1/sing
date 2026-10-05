package com.singularity.todo.test.helpers

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import com.singularity.todo.core.auth.AuthRepository
import com.singularity.todo.core.backup.BackupCodec
import com.singularity.todo.core.config.RemoteConfigPort
import com.singularity.todo.core.database.AppDatabase
import com.singularity.todo.core.files.FileRevealer
import com.singularity.todo.core.files.FileSourceFactory
import com.singularity.todo.core.files.FileSystem
import com.singularity.todo.core.files.SharePort
import com.singularity.todo.core.notifications.NotificationPort
import com.singularity.todo.core.observability.CrashReportingPort
import com.singularity.todo.core.observability.JvmCrashReportingPort
import com.singularity.todo.core.platform.haptics.Haptic
import com.singularity.todo.core.platform.haptics.createHaptic
import com.singularity.todo.core.security.SecureStoragePort
import com.singularity.todo.core.sync.SyncPeriodicTrigger
import com.singularity.todo.core.sync.work.NoopSyncWorkScheduler
import com.singularity.todo.core.sync.work.SyncWorkScheduler
import com.singularity.todo.feature.calendar_sync.data.NoopCalendarProvider
import com.singularity.todo.feature.calendar_sync.data.NoopCalendarSyncRepositoryImpl
import com.singularity.todo.feature.calendar_sync.domain.port.CalendarAppQueries
import com.singularity.todo.feature.calendar_sync.domain.port.CalendarProviderPort
import com.singularity.todo.feature.calendar_sync.domain.port.CalendarSyncRepository
import com.singularity.todo.feature.calendar_sync.work.CalendarSyncWorkScheduler
import com.singularity.todo.feature.calendar_sync.work.NoopCalendarSyncWorkScheduler
import com.singularity.todo.feature.pomodoro.PomodoroConfig
import com.singularity.todo.feature.pomodoro.PomodoroTaskListProvider
import com.singularity.todo.feature.pomodoro.PomodoroTimer
import com.singularity.todo.feature.reminders.ReminderScheduler
import com.singularity.todo.test.fakes.FakeAppDatabase
import com.singularity.todo.test.fakes.FakeAuthRepository
import com.singularity.todo.test.fakes.FakeCalendarAppQueries
import com.singularity.todo.test.fakes.FakeFileRevealer
import org.koin.core.module.Module
import org.koin.core.qualifier.named
import org.koin.dsl.module
import java.io.File
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger

/**
 * The JVM platform bindings a desktop flow test needs, rebuilt without touching
 * the developer's machine.
 *
 * This deliberately does **not** call `platformModule()`. That module resolves the
 * SQLite path, three DataStore paths and the backups directory from
 * `System.getProperty("user.home")`, runs a settings migration against them while
 * its body is being constructed, and hands out OS-backed ports that shell out to
 * `secret-tool`, `notify-send` and `at`. `user.home` is process-global, so two
 * tests that redirect it race: one test's temp directory is deleted while the
 * other is still opening it, which surfaces as `SQLiteException` code 14
 * (`SQLITE_CANTOPEN`) in whatever test happened to lose.
 *
 * Instead the data layer is [FakeAppDatabase] — an in-memory implementation of the
 * same [AppDatabase] contract, same DAO interfaces, reactive through
 * `MutableStateFlow`, no Room runtime and no files — and every OS-touching port
 * gets an inert implementation. `PreferenceDataStoreFactory` is the one thing that
 * genuinely needs a file, so each test gets a uniquely named one under a
 * single JVM-wide temp directory removed at exit, never mid-run.
 */
fun testPlatformModule(): Module = module {
    // ─── Data layer ────────────────────────────────────────────────────────
    single<AppDatabase> { FakeAppDatabase() }
    single { get<AppDatabase>().taskDao() }
    single { get<AppDatabase>().noteDao() }
    single { get<AppDatabase>().projectDao() }
    single { get<AppDatabase>().tagDao() }
    single { get<AppDatabase>().tagGroupDao() }
    single { get<AppDatabase>().projectInheritedTagGroupDao() }
    single { get<AppDatabase>().syncOutboxDao() }
    // Added with phases 2.4 and 2.5 and missed here, so the desktop flow tests
    // could not build a graph. `TestPlatformModuleParityTest` is what says so —
    // and it only says so once the graph is actually built by the tests that
    // resolve these, which is why a mirror can drift for a long time unnoticed.
    single { get<AppDatabase>().syncStateDao() }
    single { get<AppDatabase>().syncShadowDao() }
    single { get<AppDatabase>().syncDeadLetterDao() }
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
    single { get<AppDatabase>().calendarSyncTaskMapDao() }
    single { get<AppDatabase>().timeEntryDao() }
    single { get<AppDatabase>().proposalDao() }
    single { get<AppDatabase>().proposalItemDao() }

    // ─── Settings storage ───────────────────────────────────────────────────
    // The three named bindings mirror platformModule(); domainModule()'s
    // appearance and settings repositories resolve the primary one.
    single(qualifier = named("user_settings")) { testDataStore("user_settings") }
    single(qualifier = named("state")) { testDataStore("state") }
    single(qualifier = named("settings")) { testDataStore("settings_legacy") }
    single<DataStore<Preferences>> { testDataStore("user_settings_primary") }

    // ─── Ports that would otherwise touch the OS ────────────────────────────
    // coreModule() binds the real SupabaseAuthRepository, whose session never
    // resolves without a network call, so ProfileAwareCurrentUser yields no
    // user id and every user-scoped write fails its ownership check.
    // FakeAuthRepository reports Session.Anonymous, which AuthGuard treats as
    // signed in and which gives the write path a user to scope to.
    single<AuthRepository> { FakeAuthRepository() }
    single<RemoteConfigPort> { FakeRemoteConfigPort() }
    single<SecureStoragePort> { InMemorySecureStorage() }
    single<NotificationPort> { InertNotificationPort() }
    single<FileSystem> { InMemoryFileSystem() }
    single<FileRevealer> { FakeFileRevealer() }
    single<FileSourceFactory> { InertFileSourceFactory() }
    single<SharePort> { InertSharePort() }
    single<BackupCodec> { UnusedBackupCodec() }
    single<String> { tempRoot().resolve("backups").absolutePath }
    // Mirrors PlatformModule.jvm.kt. Note the *reason* has changed: when TaskTitleRow and
    // ChecklistItemRow resolved this through koinInject they needed it bound here, because
    // koinInject throws in a preview. They now read LocalHaptic, whose default is NoOpHaptic,
    // so nothing in the UI injects this any more — but the App root does, and a missing
    // definition still hangs the flow in failure capture.
    single<Haptic> { createHaptic() }

    // Same reason, same failure mode. AppTracer is Android-only, so the JVM port is inert;
    // the definition still has to exist because the gate ViewModel resolves it in a binding
    // that a flow test instantiates.
    single<CrashReportingPort> { JvmCrashReportingPort() }

    // ─── Schedulers ─────────────────────────────────────────────────────────
    // NoopCalendarSyncRepository / NoopCalendarProvider are JVM-ready production
    // classes, so they are reused rather than re-faked.
    single<ReminderScheduler> { InertReminderScheduler() }
    // `SyncScheduler` was removed in phase 2.6 along with the alarm-based
    // driver; `SyncWorkScheduler` is the surviving seam and already has its
    // no-op above. The old binding stayed here and did not compile — this
    // file is only built by `:desktopApp:test`, which is step 10 of the gate.
    single<SyncWorkScheduler> { NoopSyncWorkScheduler() }
    // Never fires. The desktop production binding starts a daemon coroutine loop,
    // and a test that started one would keep a thread alive for the rest of the run
    // and make the suite's timing depend on how many tests ran before it.
    single<SyncPeriodicTrigger> { InertSyncPeriodicTrigger() }
    single<CalendarSyncRepository> { NoopCalendarSyncRepositoryImpl() }
    single<CalendarProviderPort> { NoopCalendarProvider() }
    single<CalendarSyncWorkScheduler> { NoopCalendarSyncWorkScheduler() }
    single<CalendarAppQueries> { FakeCalendarAppQueries(emptyList()) }

    // ─── Pomodoro ───────────────────────────────────────────────────────────
    single<PomodoroConfig> { PomodoroConfig() }
    single<PomodoroTaskListProvider> { EmptyPomodoroTaskListProvider() }
    factory<PomodoroTimer> { TestPomodoroTimer(taskListProvider = get(), config = get()) }
}

/**
 * One temp directory per JVM, removed on exit.
 *
 * It has to outlive individual tests: `PreferenceDataStoreFactory` takes a
 * `FileLock` on its file, and deleting a locked file mid-suite produces failures
 * that look like flaky assertions rather than the infrastructure bug they are.
 */
private fun tempRoot(): File = TempDirHolder.root

private object TempDirHolder {
    val root: File by lazy {
        val dir = Files.createTempDirectory("singularity-desktop-ui-test").toFile()
        Runtime.getRuntime().addShutdownHook(Thread { dir.deleteRecursively() })
        dir
    }
}

private val dataStoreCounter = AtomicInteger()

private fun testDataStore(name: String): DataStore<Preferences> {
    val file = tempRoot().resolve("$name-${dataStoreCounter.incrementAndGet()}.preferences_pb")
    return PreferenceDataStoreFactory.create { file }
}

/**
 * A periodic trigger that never fires.
 *
 * The desktop production binding starts a daemon coroutine loop; a test that
 * started one would keep a thread alive for the rest of the run and make the
 * suite's timing depend on how many tests ran before it.
 */
class InertSyncPeriodicTrigger : SyncPeriodicTrigger {
    override fun start(interval: kotlin.time.Duration) = Unit

    override fun stop() = Unit
}
