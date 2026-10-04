@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.singularity.todo.feature.tasks.presentation.viewmodel.slot

import com.singularity.todo.feature.tasks.domain.model.TaskDetailDeps
import com.singularity.todo.feature.tasks.presentation.state.TaskDetailIntent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import com.singularity.todo.feature.tasks.domain.model.TaskAiAction as AiAction

/**
 * Covers the AI slot's own logic: the `isRunning` flag, the error path, and the
 * "use case not configured" contract.
 *
 * The five success paths are one-liners over use cases that already have their own
 * coverage, and each needs a full AI tool chain to construct — so they are exercised
 * through the coordinator's wiring rather than rebuilt here. What this file guards is the
 * behaviour the slot itself owns.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Tag("fast")
class TaskAiSlotTest {

    private fun slot(
        fakes: SlotFakes,
        source: TaskSource,
        scope: CoroutineScope,
        deps: TaskDetailDeps = fakes.deps(),
        onError: (String) -> Unit = {},
    ) = TaskAiSlot(
        ai = deps.ai,
        collaboration = deps.collaboration,
        context = deps.context,
        scope = testSlotScope(scope),
        taskFlow = source.state,
        onError = onError,
    ) {}

    @Test
    fun `is not running before any action`() = runTest {
        val fakes = SlotFakes()
        val slot = slot(fakes, TaskSource(task("t1")), backgroundScope)
        runCurrent()

        assertFalse(slot.state.value.isRunning)
    }

    @Test
    fun `every action reports its use case when none is configured`() = runTest {
        val fakes = SlotFakes()
        fakes.taskRepo.seed(task("t1"))
        val errors = mutableListOf<String>()
        val slot = slot(fakes, TaskSource(task("t1")), backgroundScope, onError = { errors += it })
        runCurrent()

        val actions = AiAction.entries.map { TaskDetailIntent.Domain.RunAiAction(it) }
        actions.forEach { slot.onIntent(it) }
        runCurrent()

        // A null use case must take the failure path, never the silent no-op path.
        assertTrue(errors.isNotEmpty(), "expected a missing-use-case error for each action, got none")
        assertFalse(slot.state.value.isRunning, "the flag must not stay set after a failure")
    }

    @Test
    fun `a failed action leaves the task untouched`() = runTest {
        val fakes = SlotFakes()
        fakes.taskRepo.seed(task("t1", title = "original"))
        val errors = mutableListOf<String>()
        val slot = slot(fakes, TaskSource(task("t1", title = "original")), backgroundScope, onError = { errors += it })
        runCurrent()

        slot.onIntent(
            TaskDetailIntent.Domain.RunAiAction(
                AiAction.RefineTitle,
            ),
        )
        runCurrent()

        assertTrue(errors.isNotEmpty())
        assertNull(fakes.taskRepo.tasks.value["t1"]?.description, "no field may be written on a failed action")
    }

    @Test
    fun `an action before a task arrives is ignored`() = runTest {
        val fakes = SlotFakes()
        val errors = mutableListOf<String>()
        val slot = slot(fakes, TaskSource(null), backgroundScope, onError = { errors += it })
        runCurrent()

        slot.onIntent(
            TaskDetailIntent.Domain.RunAiAction(
                AiAction.RefineTitle,
            ),
        )
        runCurrent()

        assertTrue(errors.isEmpty(), "no task means no action and no error")
    }
}
