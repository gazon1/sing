package com.singularity.todo.test.helpers

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import com.singularity.todo.core.platform.HostEnvironmentPort
import com.singularity.todo.core.platform.JvmHostEnvironment
import com.singularity.todo.core.work.BackgroundWorkScheduler
import com.singularity.todo.feature.calendar_sync.work.GoogleSyncPeriodicTrigger
import kotlinx.coroutines.CoroutineScope
import com.singularity.todo.core.auth.AuthRepository
import com.singularity.todo.core.backup.BackupCodec
import com.singularity.todo.core.config.RemoteConfigPort
import com.singularity.todo.core.database.AppDatabase
import com.singularity.todo.core.files.FileOpener
import com.singularity.todo.core.files.FileRevealer
import com.singularity.todo.core.files.FileSharePort
import com.singularity.todo.core.files.FileSourceFactory
import com.singularity.todo.core.files.FileSystem
import com.singularity.todo.core.files.SharePort
import com.singularity.todo.core.log.LogBundleExporter
import com.singularity.todo.core.notifications.Notifier
import com.singularity.todo.core.observability.CrashReportingPort
import com.singularity.todo.core.observability.JvmCrashReportingPort
import com.singularity.todo.core.platform.haptics.Haptic
import com.singularity.todo.core.platform.haptics.createHaptic
import com.singularity.todo.core.security.SecureStoragePort
import com.singularity.todo.core.sync.SyncPeriodicTrigger
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
import com.singularity.todo.test.fakes.FakeUnitOfWork
import com.singularity.todo.test.fakes.FakeAuthRepository
import com.singularity.todo.test.fakes.FakeCalendarAppQueries
import com.singularity.todo.test.fakes.FakeFileOpener
import com.singularity.todo.test.fakes.FakeFileRevealer
import org.koin.core.module.Module
import org.koin.core.qualifier.named
import org.koin.dsl.module
import java.io.File
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import com.singularity.todo.core.database.UnitOfWork

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
    // The repositories open one of these around every write-then-enqueue pair. This has
    // to be the pass-through, and it has to be *this* class specifically: `FakeAppDatabase`
    // extends the generated `AppDatabase`, so it is a `RoomDatabase` by type but was never
    // opened by Room, and Room's `coroutineScope` is a `lateinit` that only Room's own
    // initialisation assigns. Binding `RoomUnitOfWork` here therefore threw
    // `UninitializedPropertyAccessException: lateinit property coroutineScope` on the first
    // write of any test that saved a task — caught as a generic "Save failed" on screen,
    // with the editor left open and nothing in the database.
    //
    // Whether a block actually rolls back is asserted in `UnitOfWorkIsAtomicTest`, against
    // a real database, because that is the only place the property can be observed. See
    // `FakeUnitOfWork` for the same note in Kotlin.
    single<UnitOfWork> { FakeUnitOfWork() }
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
    // Google-sync DAOs. FakeAppDatabase already implements all three; without the bindings
    // the production parity test reports them as missing, and the real graph would throw
    // NoDefinitionFoundException the moment a Google pass resolved its engine.
    single { get<AppDatabase>().calendarSyncStateDao() }
    single { get<AppDatabase>().googleEventShadowDao() }
    single { get<AppDatabase>().calendarImportEventDao() }
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
    // Google sync runs on desktop, and the coordinator resolves its settings from here.
    // Without it, a graph that resolves the coordinator throws instead of skipping a pass.
    single(qualifier = named("calendar_sync")) { testDataStore("calendar_sync") }

    // ─── Ports that would otherwise touch the OS ────────────────────────────
    // coreModule() binds the real SupabaseAuthRepository, whose session never
    // resolves without a network call, so ProfileAwareCurrentUser yields no
    // user id and every user-scoped write fails its ownership check.
    // FakeAuthRepository reports Session.Anonymous, which AuthGuard treats as
    // signed in and which gives the write path a user to scope to.
    single<AuthRepository> { FakeAuthRepository() }
    single<RemoteConfigPort> { FakeRemoteConfigPort() }
    single<SecureStoragePort> { InMemorySecureStorage() }
    single<FileSystem> { InMemoryFileSystem() }
    single<FileRevealer> { FakeFileRevealer() }
    single<FileOpener> { FakeFileOpener() }
    single<FileSourceFactory> { InertFileSourceFactory() }
    single<SharePort> { InertSharePort() }
    single<BackupCodec> { UnusedBackupCodec() }
    single<String> { tempRoot().resolve("backups").absolutePath }

    // ─── Settings screen (SettingsViewModel) ────────────────────────────────
    //
    // Found 2026-10-07, and it is the reason no desktop flow test had ever opened
    // Settings: `SettingsViewModel` takes `LogBundleExporter` and `FileSharePort`, and
    // `platformModule()` — which binds both — is *replaced* by this module in the test
    // harness, so the whole Settings screen died at construction with
    //   InstanceCreationException: Could not create instance for '…SettingsViewModel'
    // and the dialog's failure path swallowed it into a generic "Save failed"-style
    // message. The screen rendered nothing and nothing said why.
    //
    // The cause is the harness's shape, not a missing production binding: replacing the
    // platform module means every definition in it must be re-declared, and two were not.
    // That is the cost of the substitution, and it is paid silently — which is why the
    // gap went unnoticed until a test actually navigated there.
    //
    // `LogBundleExporter` gets the *real* implementation against the in-memory filesystem
    // rather than `FakeLogBundleExporter`: its constructor only reads a directory path, and
    // exercising the real ZIP writer here is what would catch a change to it. The fake
    // remains available for tests that want an inert one.
    single { LogBundleExporter(tempRoot().resolve("logs").absolutePath, get(), get()) }

    // Inert rather than `JvmFileSharePort`, for the same reason as `InertSharePort` above:
    // the real one hands a file to the host desktop's file manager. The log-export row on
    // Settings → Account is the only caller, and a test that presses it wants the failure
    // mode, not a window opening on a CI box with no display.
    single<FileSharePort> { InertFileSharePort() }

    // ─── Background work ──────────────────────────────────────────────────────
    //
    // The next five were found by widening `TestPlatformModuleParityTest` from DAO
    // names to every port type, on 2026-10-07 — the same day the two above were found
    // by hand. Each was a definition `platformModule()` binds and this module did not,
    // so any flow test that reached the code resolving it would have thrown *inside
    // composition*, which Compose retries every frame: an endless redraw loop
    // presenting as a hang, with no exception the test could catch.
    //
    // `HostEnvironmentPort` is the real `JvmHostEnvironment`: it reads two system
    // properties and touches nothing else, so a fake would only be a way to be wrong.
    single<HostEnvironmentPort> { JvmHostEnvironment() }

    // Inert for the rest, because their production forms are exactly what a test must
    // not start: `JvmBackgroundWorkScheduler` and both `DelayLoop*PeriodicTrigger`s
    // resolve a `CoroutineScope` and run a delay loop that outlives its own class and
    // makes every later timing measurement depend on test ordering.
    single<BackgroundWorkScheduler> { InertBackgroundWorkScheduler() }
    single<Notifier> { RecordingNotifier() }
    single<GoogleSyncPeriodicTrigger> { InertGoogleSyncPeriodicTrigger() }

    // The bare `CoroutineScope` the two `DelayLoop*` implementations above resolve
    // through `get()`. Present only so the *shape* of the production graph is complete:
    // nothing in the test graph resolves it, because nothing binds a trigger that would
    // use it. A type never named in a `get<X>()` is invisible to both the Koin compiler
    // and the DAO scan — this binding exists so the next one of those is not a
    // `NoDefinitionFoundException` on a screen no test has reached yet.
    single<CoroutineScope> { alreadyCancelledScope() }
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
    // Inert, not the real `JvmSyncWorkScheduler`: on Desktop the real one hands work to
    // a `BackgroundWorkScheduler`, which starts a daemon loop that would outlive the test.
    // See [InertSyncWorkScheduler] for why this seam has two spellings rather than one.
    //
    // This line pointed at `NoopSyncWorkScheduler` until 276a70e3 deleted that class in
    // favour of the working scheduler, and the file is only compiled by `:desktopApp:test`
    // — step 10 of the gate — so the breakage reached `main` intact.
    single<SyncWorkScheduler> { InertSyncWorkScheduler() }
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
