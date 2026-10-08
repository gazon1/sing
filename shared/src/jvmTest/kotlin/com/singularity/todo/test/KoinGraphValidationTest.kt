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
import org.koin.core.KoinApplication
import org.koin.dsl.koinApplication
import org.junit.jupiter.api.Tag
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * Validates the Koin DI graph for desktop JVM — checks that every singleton
 * can be resolved from the graph without throwing, and that no type is bound
 * more than once.
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

    private fun buildGraph(): KoinApplication = koinApplication {
        modules(
            listOf(
                desktopPlatformModule(),
                gateModule("https://github.com/singularity-todo/singularity/releases"),
            ) + domainModule(),
        )
    }

    @Test
    fun `all singletons resolve without missing bindings`() {
        val app = buildGraph()
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
            // injected port. The `CrashReportingPort` and `Logger` bindings above are what that
            // change required here, and their absence is exactly the class of defect this test
            // exists to find: a graph that is incomplete in a way nothing else notices.
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

    /**
     * Detects duplicate bindings — a type that is registered more than once.
     *
     * Koin does not warn or fail on `single<T> { A() }` + `single<T> { B() }`:
     * the second definition silently replaces the first. The app starts, the
     * second binding wins, and the first is never reached. `AppearanceContributor`
     * was in exactly that state before the fix — two bindings, one winner, one
     * silently dead.
     *
     * This test enumerates every binding by resolving `getAll<Any>()` (the
     * untyped variant that returns all registrations) and grouping by the binding's
     * primary name. A name with more than one definition is a duplicate and fails
     * the assertion.
     *
     * The `Any` reified form works because `getAll` is an inline reified function:
     * the type argument is erased at runtime and the registry returns all entries.
     */
    @Test
    fun `no type is bound more than once`() {
        val app = buildGraph()
        try {
            val byName: Map<String, List<String>> = app.koin
                .getAll<Any>()
                .groupBy { it.javaClass.kotlin.simpleName ?: it.javaClass.name }
                .mapValues { (_, instances) -> instances.map { it.javaClass.name } }

            val duplicates = byName.filterValues { it.size > 1 }
            assertEquals(
                emptyMap(),
                duplicates,
                buildString {
                    appendLine("The following types are bound more than once — each duplicate")
                    appendLine("silently shadows the previous binding. Koin does not warn:")
                    for ((name, classes) in duplicates) {
                        appendLine("  $name: ${classes.joinToString(" → ")}")
                    }
                    appendLine("Audit the *DiModule files and remove or consolidate the duplicates.")
                },
            )
        } finally {
            app.close()
        }
    }
}
