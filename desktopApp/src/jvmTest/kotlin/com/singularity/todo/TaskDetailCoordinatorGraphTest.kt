package com.singularity.todo

import com.singularity.todo.core.coroutines.testScope
import com.singularity.todo.core.di.coreLoggingModule
import com.singularity.todo.core.di.domainModule
import com.singularity.todo.core.observability.NoOpCrashReportingPort
import com.singularity.todo.feature.profile.ProfileAwareCurrentUser
import com.singularity.todo.feature.tasks.domain.model.TaskId
import com.singularity.todo.feature.tasks.domain.port.TaskRepository
import com.singularity.todo.feature.tasks.presentation.state.TaskDetailExtras
import com.singularity.todo.feature.tasks.presentation.state.TaskDetailUiState
import com.singularity.todo.feature.tasks.presentation.viewmodel.TaskDetailCoordinator
import com.singularity.todo.test.fakes.testTask
import com.singularity.todo.test.helpers.testPlatformModule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.koin.core.Koin
import org.koin.dsl.koinApplication
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Pins the contract the flow tests exposed the hard way: a [TaskDetailCoordinator]
 * built from the real DI graph must leave `Loading` on its own.
 *
 * The failure mode this guards against is a coroutine that dies BEFORE its first
 * emission — it produces no event, no error state, just a screen on its loading
 * spinner forever. The concrete instance was `extrasState` declared after the `init`
 * block that combines over it: Kotlin initialises properties in declaration order, an
 * init block sees a not-yet-assigned property as null, and the combine died with an
 * NPE before `combineStates` ever ran. VM-level tests never caught it because the
 * property is only read from the combine launched in init; only a graph-resolved
 * construction does.
 *
 * This test constructs the coordinator through the same modules the desktop flow
 * harness uses (`domainModule()` + `testPlatformModule()`) and asserts the state
 * reaches a terminal state using virtual time. The coordinator's `scope` parameter
 * is injected with the test scope so all coroutines run on the test's virtual
 * scheduler — `runCurrent()` completes all work instantaneously regardless
 * of host load. A genuine hang (coroutine that dies before its first emission)
 * is caught immediately: the state never reaches `Loaded` and the assertion fails.
 *
 * `runCurrent()`, not `advanceUntilIdle()`: the coordinator's collectors live on
 * `backgroundScope`, and `advanceUntilIdle()` stops as soon as only background-scope
 * events remain — the state would never leave `Loading` even when everything is
 * healthy. `runCurrent()` has no such filter.
 */
@Tag("fast")
class TaskDetailCoordinatorGraphTest {

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun coordinator_built_from_di_graph_leaves_loading() = runTest {
        val app = koinApplication {
            // KOIN-W003 here is an accepted, documented tradeoff.  The ~40-binding
            // `testPlatformModule()` DSL produces the same compile-time warning as
            // the production `platformModule()` would — but the production graph is
            // already verified at the three real entry points
            // (desktopApp/main.kt, SingularityApp.kt, mcp-server/Main.kt).
            // The test's job is runtime validation via checkModules(), not
            // compile-time DI graph verification.
            // See: docs/decisions/2026-10-05-koin-w003-in-a-test-graph.md
            @Suppress("KoinCompilationForInternal")
            modules(
                listOf(coreLoggingModule()) +
                    domainModule() +
                    listOf(testPlatformModule()),
            )
        }
        try {
            val koin = app.koin
            // Pass the TestScope to the coordinator so all its coroutines run on
            // the test's virtual scheduler. runCurrent() then completes all
            // pending work instantaneously — no wall-clock budget needed.
            seedTask(koin, id = "graph-test-task-0", title = "Buy milk")
            val coordinator = TaskDetailCoordinator(
                deps = graphDeps(koin),
                taskId = TaskId("graph-test-task-0"),
                crashReporter = NoOpCrashReportingPort(),
                scope = testScope(this.backgroundScope),
            )
            runCurrent()
            val state = coordinator.state.value
            assertIs<TaskDetailUiState.Loaded>(state)
            assertTrue(state.extras is TaskDetailExtras.Ready)
        } finally {
            app.close()
        }
    }

    private suspend fun seedTask(koin: Koin, id: String, title: String) {
        // Seed under the SAME identity the coordinator's observe() uses.
        // `scopedUserId.value` is a cache seeded from `CurrentUser.userId`, which
        // itself starts at `UserId.anonymous` and is corrected by a collector on
        // Dispatchers.Default (real threads). Reading it before that collector
        // lands seeds the task under `anonymous`, while observe() derives the owner
        // from the session via `liveScopedUserId` (`TestUsers.DEFAULT`) — the two
        // disagree, observe() finds no row, and the screen flashes
        // Error("Not found"). Deriving from `liveScopedUserId` removes the
        // dependence on real-thread scheduling.
        val userId = koin.get<ProfileAwareCurrentUser>().liveScopedUserId.first()
        koin.get<TaskRepository>().upsert(
            testTask(
                id = TaskId(id),
                title = title,
                userId = userId,
            ),
        )
    }

    /** Mirrors `TasksDiModule`'s `viewModel { }` factory wiring one-for-one. */
    private fun graphDeps(koin: Koin) =
        com.singularity.todo.feature.tasks.domain.model.TaskDetailDeps(
            core = com.singularity.todo.feature.tasks.domain.model.TaskCoreDeps(
                taskRepo = koin.get(),
                updateTask = koin.get(),
                createTask = koin.get(),
                completeRecurring = koin.get(),
                completion = koin.get(),
            ),
            children = com.singularity.todo.feature.tasks.domain.model.TaskChildrenDeps(
                checklistRepository = koin.get(),
                attachmentsRepo = koin.get(),
                projectsRepo = koin.get(),
                tagsRepo = koin.get(),
            ),
            scheduling = com.singularity.todo.feature.tasks.domain.model.TaskSchedulingDeps(
                reminderRepo = koin.get(),
                reminderScheduler = koin.get(),
                timeZoneProvider = koin.get(),
            ),
            collaboration = com.singularity.todo.feature.tasks.domain.model.TaskCollaborationDeps(
                notesRepo = koin.get(),
                timeTrackingRepo = koin.get(),
                currentUser = koin.get<ProfileAwareCurrentUser>(),
                linkRepo = koin.get(),
                proposals = koin.get(),
                applyProposal = koin.get(),
            ),
            ai = com.singularity.todo.feature.tasks.domain.model.TaskAiDeps(
                refineTask = koin.get(),
                generateDescription = koin.get(),
                generateChecklist = koin.get(),
                decomposeTask = koin.get(),
                pickTime = koin.get(),
            ),
            context = com.singularity.todo.feature.tasks.domain.model.TaskContextDeps(
                clock = koin.get(),
            ),
        )
}
