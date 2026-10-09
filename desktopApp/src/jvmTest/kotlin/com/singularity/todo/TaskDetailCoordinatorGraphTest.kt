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
import kotlinx.coroutines.test.advanceUntilIdle
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
 * scheduler — `advanceUntilIdle()` completes all work instantaneously regardless
 * of host load. A genuine hang (coroutine that dies before its first emission)
 * is caught immediately: the state never reaches `Loaded` and the assertion fails.
 */
@Tag("fast")
class TaskDetailCoordinatorGraphTest {

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun coordinator_built_from_di_graph_leaves_loading() = runTest {
        val app = koinApplication {
            modules(
                listOf(coreLoggingModule()) +
                    domainModule() +
                    listOf(testPlatformModule()),
            )
        }
        try {
            val koin = app.koin
            // Pass the TestScope to the coordinator so all its coroutines run on
            // the test's virtual scheduler. advanceUntilIdle() then completes all
            // pending work instantaneously — no wall-clock budget needed.
            seedTask(koin, id = "graph-test-task-0", title = "Buy milk")
            val coordinator = TaskDetailCoordinator(
                deps = graphDeps(koin),
                taskId = TaskId("graph-test-task-0"),
                crashReporter = NoOpCrashReportingPort(),
                scope = testScope(this.backgroundScope),
            )
            advanceUntilIdle()
            val state = coordinator.state.value
            assertIs<TaskDetailUiState.Loaded>(state)
            assertTrue(state.extras is TaskDetailExtras.Ready)
        } finally {
            app.close()
        }
    }

    private suspend fun seedTask(koin: Koin, id: String, title: String) {
        val userId = koin.get<ProfileAwareCurrentUser>().scopedUserId.value
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
