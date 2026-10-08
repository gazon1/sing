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
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.Dispatchers
import org.koin.core.Koin
import org.koin.dsl.koinApplication
import org.junit.jupiter.api.Tag
import java.util.Collections
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

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
@Tag("fast")
class TaskDetailCoordinatorGraphTest {

    private companion object {
        /**
         * Hang detector, not a latency budget — see the comment at the wait.
         *
         * 5 minutes: large enough that a machine under full parallel compilation load
         * still passes (before the fix this test failed ~1 run in 3 at 10s), while
         * a genuine hang is caught within the same CI run that introduced it. A combine
         * that dies before its first emission hangs forever; the budget only needs to
         * exceed the worst-case real completion time, not approach it.
         */
        val HANG_BUDGET = 5.minutes
    }

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
            // The coordinator's scope runs on real Dispatchers.Default
            // (createBackgroundScope), so withContext(Default) keeps the wait on real
            // time while the coordinator's flows execute on real threads.  A bare
            // withTimeout here would use the test scheduler's virtual clock and expire
            // instantly while the combine waits on real workers.
            //
            // The budget is a HANG detector, not a speed assertion.  A combine that
            // dies before its first emission hangs forever, so a generous bound still
            // catches it — it just costs real seconds instead of virtual ones when
            // the code is broken.  The test ran at 10 s budget and failed ~1 run in 3
            // under full parallel compilation; 5 minutes makes it a coin-flip at
            // nothing.
            withContext(Dispatchers.Default) {
                seedTask(koin, id = "graph-test-task-0", title = "Buy milk")
                val coordinator = TaskDetailCoordinator(
                    deps = graphDeps(koin),
                    taskId = TaskId("graph-test-task-0"),
                )
                val seen = Collections.synchronizedList(mutableListOf<TaskDetailUiState>())
                val loaded = withTimeoutOrNull(HANG_BUDGET) {
                    coordinator.state
                        .onEach { seen += it }
                        .first { it is TaskDetailUiState.Loaded }
                        as TaskDetailUiState.Loaded
                }
                assertNotNull(
                    loaded,
                    "coordinator never reached Loaded within $HANG_BUDGET; " +
                        "states observed: ${seen.joinToString(" -> ")}",
                )
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
