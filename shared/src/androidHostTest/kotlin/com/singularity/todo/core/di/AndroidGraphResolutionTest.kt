package com.singularity.todo.core.di

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.singularity.todo.core.backup.BackupCodec
import com.singularity.todo.core.backup.BackupRepository
import com.singularity.todo.core.attachments.AttachmentStorage
import com.singularity.todo.core.database.AppDatabase
import com.singularity.todo.core.database.UnitOfWork
import com.singularity.todo.core.files.FileOpener
import com.singularity.todo.core.files.FileRevealer
import com.singularity.todo.core.files.FileSharePort
import com.singularity.todo.core.files.FileSourceFactory
import com.singularity.todo.core.files.FileSystem
import com.singularity.todo.core.files.SharePort
import com.singularity.todo.core.notifications.Notifier
import com.singularity.todo.core.observability.CrashReportingPort
import com.singularity.todo.core.platform.HostEnvironmentPort
import com.singularity.todo.core.platform.haptics.Haptic
import com.singularity.todo.core.security.SecureStoragePort
import com.singularity.todo.core.sync.SyncDocumentWriter
import com.singularity.todo.core.sync.SyncEngine
import com.singularity.todo.core.sync.SyncPeriodicTrigger
import com.singularity.todo.core.sync.SyncRepository
import com.singularity.todo.core.sync.work.SyncWorkScheduler
import com.singularity.todo.feature.calendarSync.domain.port.CalendarAppQueries
import com.singularity.todo.feature.calendarSync.domain.port.CalendarProviderPort
import com.singularity.todo.feature.calendarSync.domain.port.CalendarSyncRepository
import com.singularity.todo.feature.calendarSync.sync.GoogleSyncCoordinator
import com.singularity.todo.feature.calendarSync.sync.GoogleSyncEngine
import com.singularity.todo.feature.calendarSync.work.CalendarSyncWorkScheduler
import com.singularity.todo.feature.calendarSync.work.GoogleSyncPeriodicTrigger
import com.singularity.todo.feature.gate.gateModule
import com.singularity.todo.feature.pomodoro.PomodoroScheduler
import org.junit.Test
import org.junit.jupiter.api.Tag
import org.junit.runner.RunWith
import org.koin.android.ext.koin.androidContext
import org.koin.core.KoinApplication
import org.koin.core.annotation.KoinInternalApi
import org.koin.dsl.koinApplication
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The Android Koin graph resolves for real, under a real [Context].
 *
 * ## Why this exists
 *
 * `DomainGraphResolutionTest` covers the *JVM* graph, and it builds it from
 * `testPlatformModule()` — a set of fakes that stands in for the platform. So every
 * Android binding in `PlatformModule.android.kt` was, until this test, resolved by
 * **no test at all**. The compile-time `koin-compiler-plugin` cannot close that gap:
 * it sees the *types* a definition asks for, not the ones it would get from the
 * Android platform module, and it cannot see a cycle — a cycle is not a compile error,
 * it is a runtime stack overflow.
 *
 * ADR `2026-10-06-the-sync-engine-needs-the-repositories-and-the-repositories-need-the-engine`
 * records such a cycle shipping as a `StackOverflowError` at app start. It happened on
 * the platform the app ships on, and the platform half of the graph had no test.
 *
 * This file was written once, deleted because the *database* could not open under
 * Robolectric (the bundled SQLite native was not on the library path), and rewritten
 * after the build grew the native extraction that fixed it. The gap was real, and the
 * reason it stayed open for a month was a build problem that had nothing to do with
 * DI.
 *
 * ## What is deliberately NOT asserted
 *
 * This resolves definitions. It does not exercise them: no network call is made, no
 * alarm is scheduled, no notification is posted. A `single` is lazy, so *resolving* it
 * runs its factory body — which is where a cycle, a missing binding and a
 * wrong-context `databaseBuilder` all fail — while the code those objects wrap is
 * untouched.
 *
 * ## What is deliberately NOT resolved
 *
 * Three Android bindings cannot be built outside a device, and pretending otherwise
 * would make this test lie:
 *
 * - [SecureStoragePort] — `EncryptedSharedPreferences` needs the `AndroidKeyStore`
 *   provider, which does not exist off-device. The binding is *present* in the graph;
 *   its factory body cannot run here.
 * - [Haptic] — `VibratorManager` is an API 31 hardware service; Robolectric has no
 *   vibrator.
 * - [Notifier] — `AndroidNotifier` creates a notification channel in its `init` and
 *   reads the notification settings, which needs a real `DataStore` and a real
 *   `NotificationManager`.
 *
 * They are listed in [deviceOnlyBindings] so that the exclusion is a *decision on
 * record* rather than a silent hole: a reader can see exactly which bindings this test
 * does not create, and a new Android binding is expected to be either resolvable here
 * or listed.
 */
@Tag("slow")
@Tag("slow:data")
@RunWith(RobolectricTestRunner::class)
class AndroidGraphResolutionTest {

    @Test
    fun `the android platform bindings resolve from the composed graph`() {
        val app = androidKoin()
        try {
            val koin = app.koin

            // The database and its transaction. This is the definition that could not
            // be built under Robolectric for a month: the bundled SQLite native lives
            // in the *Android* artifact, which ships ARM binaries only, and the JVM
            // artifact — the one Robolectric can load — is now extracted onto the test
            // JVM's library path by the build.
            assertNotNull(koin.get<AppDatabase>())
            assertNotNull(koin.get<UnitOfWork>())

            // The file and platform ports. Every one of these is an Android
            // implementation of a port the JVM graph fakes; resolving them is what
            // proves the Android binding is not merely *named* like the JVM one.
            assertNotNull(koin.get<FileSystem>())
            assertNotNull(koin.get<HostEnvironmentPort>())
            assertNotNull(koin.get<FileRevealer>())
            assertNotNull(koin.get<FileOpener>())
            assertNotNull(koin.get<SharePort>())
            assertNotNull(koin.get<FileSharePort>())
            assertNotNull(koin.get<FileSourceFactory>())
            assertNotNull(koin.get<BackupCodec>())
            assertNotNull(koin.get<CrashReportingPort>())

            // The backup half. `AttachmentStorage` takes its directory as a plain
            // `String`, so the graph has to *say* which directory that is: a `String`
            // resolved by type used to be the log directory, and the attachments were
            // written into it. `BackupRepository` sits behind that storage, so
            // resolving it walks the whole chain — the importer, the exporter, the
            // remote service and the storage under them.
            assertNotNull(koin.get<AttachmentStorage>())
            assertNotNull(koin.get<BackupRepository>())

            // The sync half. `SyncEngine` takes `() -> SyncDocumentWriter`, so the
            // cycle closes only when the writer is asked for — leaf first, then the
            // chain upward, in the same order the JVM twin uses.
            assertNotNull(koin.get<SyncDocumentWriter>())
            assertNotNull(koin.get<SyncRepository>())
            assertNotNull(koin.get<SyncEngine>())
            assertNotNull(koin.get<SyncWorkScheduler>())
            assertNotNull(koin.get<SyncPeriodicTrigger>())

            // The calendar-sync half, because that is the graph the cycle shipped in
            // and because these bindings only exist on the Android side:
            // `CalendarProviderPort` is bound to `AndroidCalendarProvider` (a
            // ContentResolver), whose JVM counterpart is a no-op.
            assertNotNull(koin.get<CalendarProviderPort>())
            assertNotNull(koin.get<CalendarAppQueries>())
            assertNotNull(koin.get<CalendarSyncRepository>())
            assertNotNull(koin.get<CalendarSyncWorkScheduler>())
            assertNotNull(koin.get<GoogleSyncPeriodicTrigger>())

            // `GoogleSyncEngine` is the definition that could not be built without
            // four DAOs and a client, and `GoogleSyncCoordinator` is a `factory`
            // wrapping it — the pair the JVM test cannot reach.
            assertNotNull(koin.get<GoogleSyncEngine>())
            assertNotNull(koin.get<GoogleSyncCoordinator>())

            // The alarm-backed scheduler: an Android `AlarmManager` binding, and the
            // JVM graph's counterpart is a no-op.
            assertNotNull(koin.get<PomodoroScheduler>())
        } finally {
            app.close()
        }
    }

    @Test
    fun `the bindings that need a device are registered`() {
        val app = androidKoin()
        try {
            val registered = app.koin.registeredTypes()
            val missing = deviceOnlyBindings.filterNot { it in registered }
            assertTrue(
                missing.isEmpty(),
                "These Android bindings are not in the graph at all:\n" +
                    missing.joinToString("\n") { "  - ${it.simpleName}" } +
                    "\n\nA port that no longer has an Android binding resolves on the " +
                    "JVM and crashes on the device that ships the app.",
            )
        } finally {
            app.close()
        }
    }

    /**
     * The Android bindings that cannot be *created* off a device.
     *
     * The test above checks that they are registered — a binding that vanished from
     * the module would still let the rest of the graph resolve, and the port would be
     * unresolvable on the platform that needs it.
     */
    private val deviceOnlyBindings: List<kotlin.reflect.KClass<*>> = listOf(
        SecureStoragePort::class,
        Haptic::class,
        Notifier::class,
    )

    /**
     * The graph, assembled the way `SingularityApp.onCreate` assembles it.
     *
     * `androidContext()` comes first because the platform module's `single` bodies
     * call `get<Context>()`; Koin resolves lazily, so the order only matters at the
     * first `get` — but registering the context afterwards would make the first test
     * to touch a database fail for a reason that has nothing to do with DI.
     */
    private fun androidKoin(): KoinApplication = koinApplication {
        androidContext(ApplicationProvider.getApplicationContext<Context>())
        @Suppress("KoinCompilationForInternal")
        modules(
            // The same set `SingularityApp.onCreate` loads, minus the app-update module
            // (a Play Store client, and nothing here needs it). The logger module is
            // part of the set: without it `co.touchlab.kermit.Logger` is unbound, and
            // the first definition that wants a logger fails to be created.
            listOf(platformModule(), coreLoggingModule()) +
                domainModule() +
                listOf(gateModule("https://github.com/singularity-todo/singularity/releases")),
        )
    }
}

/**
 * The types the graph holds.
 *
 * The instance registry is internal API: it is the only way to enumerate what a graph
 * holds without creating anything, and "is this binding registered?" is exactly the
 * question the device-only bindings need answered.
 */
@OptIn(KoinInternalApi::class)
private fun org.koin.core.Koin.registeredTypes(): Set<kotlin.reflect.KClass<*>> =
    instanceRegistry.instances.values.map { it.beanDefinition.primaryType }.toSet()
