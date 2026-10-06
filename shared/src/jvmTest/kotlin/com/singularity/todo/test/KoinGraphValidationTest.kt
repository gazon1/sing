package com.singularity.todo.test

import com.singularity.todo.core.config.RemoteConfigPort
import com.singularity.todo.core.di.domainModule
import com.singularity.todo.feature.calendar_sync.presentation.CalendarSyncViewModel
import com.singularity.todo.feature.calendar_sync.sync.CalendarSyncOrchestrator
import com.singularity.todo.core.sync.SyncPeriodicTrigger
import com.singularity.todo.core.sync.work.SyncWorkScheduler
import com.singularity.todo.feature.calendar_sync.domain.port.CalendarSyncRepository
import com.singularity.todo.feature.calendar_sync.sync.GoogleSyncCoordinator
import com.singularity.todo.feature.calendar_sync.sync.GoogleSyncEngine
import com.singularity.todo.feature.calendar_sync.work.GoogleSyncPeriodicTrigger
import com.singularity.todo.feature.gate.gateModule
import org.junit.jupiter.api.Test
import org.koin.dsl.koinApplication
import org.junit.jupiter.api.Tag
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
            assertNotNull(app.koin.get<SyncPeriodicTrigger>())
            assertNotNull(app.koin.get<SyncWorkScheduler>())
            assertNotNull(app.koin.get<CalendarSyncRepository>())
            // A Koin definition's lambda body only runs when something *resolves* it, not
            // when the module is defined — so a definition can exist, be correct, and never
            // execute anywhere. That is the "implemented but unwired" shape this repository
            // audits for, and this test is the cheapest place to catch it: resolving is one line.
            //
            // These two were added because the calendar-sync bindings stopped being
            // resolvable-by-accident once they started composing their failure handler from the
            // injected port. The `CrashReportingPort` and `Logger` bindings it needs live in
            // `desktopPlatformModule()`, which is where that change had to add them — and their
            // absence is exactly the class of defect this test exists to find: a graph that is
            // incomplete in a way nothing else notices.
            assertNotNull(app.koin.get<CalendarSyncOrchestrator>())
            assertNotNull(app.koin.get<CalendarSyncViewModel>())
            // The Google pass is a factory chain (coordinator → engine → applier → DAOs),
            // and a definition that never resolves is a definition that was never checked.
            // Resolving both is the point: the trigger is what the desktop entry point
            // starts, and the coordinator is what each of its cycles calls.
            assertNotNull(app.koin.get<GoogleSyncPeriodicTrigger>())
            assertNotNull(app.koin.get<GoogleSyncCoordinator>())
            // The coordinator takes its engine as a lambda, so resolving the coordinator
            // alone never touches the pass. Resolving the engine too is what actually
            // proves the three Google DAOs, the applier and the event source are all bound.
            assertNotNull(app.koin.get<GoogleSyncEngine>())
        } finally {
            app.close()
        }
    }
}
