package com.singularity.todo.feature.agenda.presentation.viewmodel

import co.touchlab.kermit.Logger
import com.singularity.todo.core.coroutines.AutoCloseableCoroutineScope
import com.singularity.todo.core.ids.UserId
import com.singularity.todo.feature.agenda.domain.logic.AgendaPresets
import com.singularity.todo.feature.agenda.domain.model.AgendaUiState
import com.singularity.todo.feature.tasks.domain.model.Task
import com.singularity.todo.feature.tasks.domain.model.TaskId
import com.singularity.todo.test.fakes.FakeTaskRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Clock

/**
 * Regression tests for [AgendaViewModel]'s Loading → Loaded transition.
 *
 * The VM used to collect with `.collect { updateState { it } }`. The reducer's `it`
 * shadows the collected element, so the transform was the identity function: data
 * arrived from the repository and was discarded, and the screen rendered its
 * loading indicator forever. These tests assert the state actually advances.
 *
 * ## Why `runCurrent` and not `advanceUntilIdle`
 * `todayFlow` is an infinite `while (true) { emit; delay(untilMidnight) }` loop, so
 * advancing virtual time never lets the test scheduler drain. `runCurrent` executes
 * the coroutines that are already runnable — including the flow's first, immediate
 * emission — without moving the clock, which is all this transition needs.
 *
 * ## Why the VM scope owns its own Job
 * The same infinite loop means the VM's init coroutine never completes, so it cannot
 * be a child of the test's [TestScope] job (runTest would await it and time out).
 * A detached Job plus an explicit `close()` in `finally` keeps the loop bounded.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AgendaViewModelTest {

    private val fakeRepo = FakeTaskRepository()

    private fun task(id: String, title: String) = Task(
        id = TaskId(id),
        title = title,
        createdAt = Clock.System.now(),
        updatedAt = Clock.System.now(),
        userId = UserId("test-user"),
    )

    private fun TestScope.createVm(scope: AutoCloseableCoroutineScope) = AgendaViewModel(
        deps = AgendaDeps(taskRepo = fakeRepo, logger = Logger),
        definition = AgendaPresets.Inbox,
        scope = scope,
    )

    @Test
    fun `leaves Loading and renders the seeded tasks`() = runTest {
        fakeRepo.seed(task("t1", "Buy milk"), task("t2", "Ship release"))
        val vmScope = AutoCloseableCoroutineScope(coroutineContext + Job())
        try {
            val vm = createVm(vmScope)
            runCurrent()

            val state = assertIs<AgendaUiState.Loaded>(
                vm.state.value,
                "VM stayed on Loading — the collected state is being discarded",
            )
            val titles = state.sections.flatMap { it.tasks }.map { it.task.title }
            assertEquals(setOf("Buy milk", "Ship release"), titles.toSet())
        } finally {
            vmScope.close()
        }
    }

    @Test
    fun `emits Loaded even when the user has no tasks`() = runTest {
        val vmScope = AutoCloseableCoroutineScope(coroutineContext + Job())
        try {
            val vm = createVm(vmScope)
            runCurrent()

            // An empty list must still resolve to Loaded — otherwise the screen
            // would spin forever before it could show its empty state.
            val state = assertIs<AgendaUiState.Loaded>(vm.state.value)
            assertEquals(emptyList(), state.sections.flatMap { it.tasks })
        } finally {
            vmScope.close()
        }
    }
}
