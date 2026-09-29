package com.singularity.todo.test

import android.app.Application
import android.content.Context
import com.singularity.todo.core.attachments.AttachmentDao
import com.singularity.todo.core.config.RemoteConfigCacheDao
import com.singularity.todo.core.database.AgendaViewDao
import com.singularity.todo.core.database.ChecklistDao
import com.singularity.todo.core.database.LlmUsageDao
import com.singularity.todo.core.database.NoteDao
import com.singularity.todo.core.database.ProfileDao
import com.singularity.todo.core.database.ProjectDao
import com.singularity.todo.core.database.ReminderDao
import com.singularity.todo.core.database.TagDao
import com.singularity.todo.core.database.TagGroupDao
import com.singularity.todo.core.database.AppDatabase
import com.singularity.todo.core.database.TaskDao
import com.singularity.todo.core.di.domainModule
import com.singularity.todo.core.di.platformModule
import com.singularity.todo.core.notifications.NotificationPort
import com.singularity.todo.core.security.SecureStoragePort
import com.singularity.todo.core.sync.RemoteConfigDao
import com.singularity.todo.core.sync.SyncOutboxDao
import com.singularity.todo.feature.calendar_sync.data.CalendarSyncTaskMapDao
import com.singularity.todo.feature.calendar_sync.domain.repository.CalendarSyncRepository
import com.singularity.todo.feature.pomodoro.PomodoroTaskListProvider
import com.singularity.todo.feature.pomodoro.PomodoroTimer
import com.singularity.todo.feature.profile.ProfileRepository
import com.singularity.todo.test.fakes.FakeAppDatabase
import kotlin.reflect.KClass
import kotlin.test.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.android.ext.koin.androidContext
import org.koin.core.Koin
import org.koin.core.annotation.KoinExperimentalAPI
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import org.koin.test.KoinTest
import org.koin.test.verify.verify
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Validates the Android Koin DI graph on the JVM (Robolectric, no emulator).
 *
 * The desktop-only twin `KoinGraphValidationTest` (jvmTest) covers `platformModule()`
 * from jvmMain. This one covers the Android actual — which is exactly where the Pomodoro
 * crash lived: `AndroidPomodoroTaskListProvider` was registered by its concrete class,
 * every injection point asked for the interface, and no test ever built the Android
 * graph, so the tab crashed at runtime.
 *
 * Two layers of validation:
 * - static `verify()` per module — catches shape errors without instantiating anything;
 * - full-graph resolution — catches what static analysis cannot see across module
 *   boundaries (binding by concrete type instead of interface is invisible to `verify()`
 *   when the definition and the injection site live in different modules).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class AndroidKoinGraphValidationTest : KoinTest {

    /**
     * Types supplied from outside the module under verification. `Context` arrives via
     * `androidContext()` in `SingularityApp` (and in [buildKoin] below); the DAOs are
     * provided by the `AppDatabase` definition inside `platformModule()`, which a
     * per-module `verify()` cannot observe.
     */
    private val externalTypes: List<KClass<*>> = listOf(
        Context::class,
        TaskDao::class,
        NoteDao::class,
        ProjectDao::class,
        TagDao::class,
        TagGroupDao::class,
        ReminderDao::class,
        ChecklistDao::class,
        LlmUsageDao::class,
        ProfileDao::class,
        AgendaViewDao::class,
        AttachmentDao::class,
        RemoteConfigDao::class,
        RemoteConfigCacheDao::class,
        SyncOutboxDao::class,
        CalendarSyncTaskMapDao::class,
    )

    @OptIn(KoinExperimentalAPI::class)
    @Test
    fun `android platform module verifies statically`() {
        platformModule().verify(extraTypes = externalTypes)
    }

    @Test
    fun `key android singletons resolve without missing bindings`() {
        val koin = buildKoin()
        try {
            // PomodoroTaskListProvider is asserted explicitly because its Android
            // registration was the one that was wrong — this exact resolution would have
            // caught the Pomodoro crash before it shipped.
            assertNotNull(koin.get<PomodoroTaskListProvider>())
            assertNotNull(koin.get<PomodoroTimer>())
            assertNotNull(koin.get<SecureStoragePort>())
            assertNotNull(koin.get<NotificationPort>())
            assertNotNull(koin.get<ProfileRepository>())
            assertNotNull(koin.get<CalendarSyncRepository>())
        } finally {
            koin.close()
        }
    }

    private fun buildKoin(): Koin {
        // Read the context lazily, after Robolectric has bootstrapped the application for
        // this test.
        val appContext = RuntimeEnvironment.getApplication().applicationContext
        return koinApplication {
            androidContext(appContext)
            modules(
                platformModule(),
                *domainModule().toTypedArray(),
                // The real AppDatabase opens the bundled SQLite driver, whose native
                // library is not on the Robolectric JVM classpath. Swap in the fake so
                // the graph still wires end-to-end; everything above the DAO layer
                // resolves exactly as in production.
                module {
                    single<AppDatabase> { FakeAppDatabase() }
                },
            )
        }.koin
    }
}
