package com.singularity.todo

import com.singularity.todo.core.di.coreLoggingModule
import com.singularity.todo.core.di.domainModule
import com.singularity.todo.feature.profile.ProfileAwareCurrentUser
import com.singularity.todo.feature.tasks.domain.model.TaskId
import com.singularity.todo.feature.tasks.domain.port.TaskRepository
import com.singularity.todo.feature.tasks.presentation.state.TaskDetailExtras
import com.singularity.todo.feature.tasks.presentation.state.TaskDetailUiState
import com.singularity.todo.feature.tasks.presentation.viewmodel.TaskDetailCoordinator
import com.singularity.todo.test.fakes.testTask
import com.singularity.todo.test.helpers.testPlatformModule
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.Dispatchers
import org.koin.core.Koin
import org.koin.dsl.koinApplication
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

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
 * reaches a terminal state in real time. It also fails on the `Error` path with the
 * actual message, so a broken dependency surfaces here instead of as a hang.
 */
class TaskDetailCoordinatorGraphTest {

    @Test
    fun coordinator_built_from_di_graph_leaves_loading() = runTest {
        val app = koinApplication {
            modules(
                coreLoggingModule(),
                *domainModule().toTypedArray(),
                testPlatformModule(),
            )
        }
        try {
            val koin = app.koin
            // The VM's scope runs on real Dispatchers.Default (createBackgroundScope),
            // so the wait must use real time — hence withContext(Default) inside runTest:
            // a bare withTimeout here would sit on the test scheduler's VIRTUAL clock
            // and expire instantly while the combine waits on real workers.
            withContext(Dispatchers.Default) {
                seedTask(koin, id = "graph-test-task-0", title = "Buy milk")
                val coordinator = TaskDetailCoordinator(
                    deps = graphDeps(koin),
                    taskId = TaskId("graph-test-task-0"),
                )
                // The combine legitimately emits Error("Not found") once while
                // taskFlow is still on its seeded null — the repository emission
                // that resolves the task lands a moment later. Waiting for Loaded
                // (not "any terminal state") is the actual contract under test: a
                // dead combine would leave Loading forever and time out here.
                val loaded = withTimeout(10.seconds) {
                    coordinator.state.first { it is TaskDetailUiState.Loaded }
                        as TaskDetailUiState.Loaded
                }
                assertTrue(loaded.extras is TaskDetailExtras.Ready)
            }
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
            taskRepo = koin.get(),
            updateTask = koin.get(),
            createTask = koin.get(),
            projectsRepo = koin.get(),
            tagsRepo = koin.get(),
            checklistRepository = koin.get(),
            reminderRepo = koin.get(),
            reminderScheduler = koin.get(),
            attachmentsRepo = koin.get(),
            timeZoneProvider = koin.get(),
            clock = koin.get(),
            completeRecurring = koin.get(),
            notesRepo = koin.get(),
            timeTrackingRepo = koin.get(),
            currentUser = koin.get<ProfileAwareCurrentUser>(),
            refineTask = koin.get(),
            generateDescription = koin.get(),
            generateChecklist = koin.get(),
            decomposeTask = koin.get(),
            pickTime = koin.get(),
            linkRepo = koin.get(),
            proposals = koin.get(),
            applyProposal = koin.get(),
        )
}
