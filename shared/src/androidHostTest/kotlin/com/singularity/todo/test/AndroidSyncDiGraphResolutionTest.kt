package com.singularity.todo.test

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.singularity.todo.core.di.domainModule
import com.singularity.todo.core.di.platformModule
import com.singularity.todo.core.sync.SyncDocumentWriter
import com.singularity.todo.core.sync.SyncEngine
import com.singularity.todo.core.sync.SyncRepository
import com.singularity.todo.core.sync.work.SyncWorkScheduler
import com.singularity.todo.feature.calendar_sync.domain.port.CalendarProviderPort
import com.singularity.todo.feature.calendar_sync.sync.GoogleSyncCoordinator
import com.singularity.todo.feature.calendar_sync.sync.GoogleSyncEngine
import com.singularity.todo.feature.gate.gateModule
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.koinApplication
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertNotNull

/**
 * The Android Koin graph resolves for real, under a real [Context].
 *
 * ## Why this exists (#227)
 *
 * `SyncDiGraphResolutionTest` covers the JVM graph. The Android half had **no
 * equivalent**, and `shared/src/androidHostTest/` held nothing but a manifest, so
 * `testAndroidHostTest` ran zero tests of its own while reporting green.
 *
 * That gap is not academic. ADR
 * `2026-10-06-the-sync-engine-needs-the-repositories-and-the-repositories-need-the-engine`
 * records a resolution cycle that shipped as a `StackOverflowError` at app start — and
 * `koin-compiler-plugin` did not catch it. A DI cycle is not a compile error; it is a
 * runtime stack overflow whose deepest application frame names an arbitrary line. On the
 * platform where the app actually ships, that is the most expensive class of bug in the
 * repository and it had no test at all.
 *
 * `PlatformModuleMirrorTest` cannot cover this either, and that is why the gap survived:
 * it compares declared binding *names* between the two platform modules and resolves
 * nothing. It cannot see a body that resolves a type nobody binds on Android, a cycle
 * through any other type, or a `databaseBuilder` handed the wrong context under a harness.
 *
 * ## Why Robolectric rather than an emulator
 *
 * The graph needs a real `Context`, because `platformModule()` calls `get<Context>()`
 * inside several `single` bodies and `androidContext()` is what supplies it. An
 * instrumented test would need a device; this runs on the JVM in seconds, which is the
 * whole reason it can sit in the ordinary gate loop.
 *
 * ## What is deliberately NOT asserted
 *
 * This resolves definitions. It does not exercise them: no database is opened, no network
 * call is made, no alarm is scheduled. A `single` is lazy, so *resolving* it runs its
 * factory body — which is where a cycle, a missing binding, and a wrong-context
 * `databaseBuilder` all fail — while the code those objects wrap is untouched. That is the
 * intended scope, and it is the same scope `SyncDiGraphResolutionTest` has on the JVM: if
 * this file ever grows to assert behaviour, it has stopped being a resolution test.
 */
@RunWith(RobolectricTestRunner::class)
class AndroidSyncDiGraphResolutionTest {

    @Test
    fun `the sync chain resolves from the composed android graph`() {
        val app = koinApplication {
            // `androidContext()` before `platformModule()`, mirroring
            // `SingularityApp.onCreate`: the platform module's `single` bodies call
            // `get<Context>()`, and Koin resolves lazily, so order matters only at first
            // get — but registering the context afterwards would make the *first* test to
            // touch a database fail for a reason that has nothing to do with DI.
            androidContext(ApplicationProvider.getApplicationContext<Context>())
            modules(
                listOf(
                    platformModule(),
                    gateModule("https://github.com/singularity-todo/singularity/releases"),
                ) + domainModule(),
            )
        }
        try {
            // Leaf first, then the chain upward — the same order and the same reasoning
            // as the JVM twin: `SyncEngine` takes `() -> SyncDocumentWriter`, so the
            // cycle closes only when the writer is asked for.
            assertNotNull(app.koin.get<SyncDocumentWriter>())
            assertNotNull(app.koin.get<SyncRepository>())
            assertNotNull(app.koin.get<SyncEngine>())
            assertNotNull(app.koin.get<SyncWorkScheduler>())

            // The calendar-sync half, because that is the graph the cycle shipped in and
            // because these two are the definitions that only exist on the Android side:
            // `CalendarProviderPort` is bound to `AndroidCalendarProvider` (a
            // ContentResolver), whose JVM counterpart is `NoopCalendarProvider`. Resolving
            // it here is what proves the Android binding actually resolves and is not
            // merely *named* the same as the JVM one.
            assertNotNull(app.koin.get<CalendarProviderPort>())

            // `GoogleSyncEngine` is the definition that could not be built without four
            // DAOs and a client, and `GoogleSyncCoordinator` is a `factory` wrapping it —
            // so this is the pair that `SyncDiGraphResolutionTest` cannot reach and that
            // `koin-compiler-plugin` missed.
            assertNotNull(app.koin.get<GoogleSyncEngine>())
            assertNotNull(app.koin.get<GoogleSyncCoordinator>())
        } finally {
            app.close()
        }
    }
}
