@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.singularity.todo.feature.tasks.presentation.viewmodel.slot

import com.singularity.todo.feature.checklist.ChecklistMove
import com.singularity.todo.feature.tasks.domain.model.TaskId
import com.singularity.todo.feature.tasks.domain.state.TaskDetailIntent
import io.qameta.allure.Epic
import io.qameta.allure.Feature
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Reordering subtasks: the order the user sets is the order the list keeps.
 *
 * Subtasks are tasks; the order lives on [com.singularity.todo.feature.tasks.domain.model.Task.sortOrder]
 * and is written as one full-list renumber, the same shape as checklist reorder.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Tag("fast")
@Epic("Tasks")
@Feature("Presentation")
class TaskChildrenSlotSubtaskReorderTest {
    private fun subtask(id: String, title: String, order: Int) =
        task(id, title).copy(parentTaskId = TaskId("t1"), sortOrder = order)

    @Test
    fun `moving a subtask down renumbers the list`() =
        runTest {
            val fakes = SlotFakes()
            fakes.taskRepo.seed(
                task("t1"),
                subtask("c1", "One", 0),
                subtask("c2", "Two", 1),
                subtask("c3", "Three", 2),
            )
            val slot = slot(fakes, TaskSource(task("t1")), backgroundScope)
            runCurrent()

            slot.onIntent(
                TaskDetailIntent.Domain.MoveSubtask(
                    taskId = TaskId("c2"),
                    direction = ChecklistMove.Down,
                ),
            )
            runCurrent()

            assertEquals(
                listOf("One", "Three", "Two"),
                slot.state.value.subtasks.map { it.title },
                "the list is renumbered, not just the two rows swapped",
            )
            assertEquals(listOf(0, 1, 2), slot.state.value.subtasks.map { it.sortOrder })
        }

    @Test
    fun `moving the first subtask up is a no-op`() =
        runTest {
            val fakes = SlotFakes()
            fakes.taskRepo.seed(
                task("t1"),
                subtask("c1", "One", 0),
                subtask("c2", "Two", 1),
            )
            val slot = slot(fakes, TaskSource(task("t1")), backgroundScope)
            runCurrent()

            slot.onIntent(
                TaskDetailIntent.Domain.MoveSubtask(
                    taskId = TaskId("c1"),
                    direction = ChecklistMove.Up,
                ),
            )
            runCurrent()

            assertEquals(listOf("One", "Two"), slot.state.value.subtasks.map { it.title })
        }

    @Test
    fun `a failed subtask reorder is reported and leaves order intact`() =
        runTest {
            val fakes = SlotFakes()
            fakes.taskRepo.seed(
                task("t1"),
                subtask("c1", "One", 0),
                subtask("c2", "Two", 1),
            )
            fakes.taskRepo.reorderSubtasksOverride = Result.failure(IllegalStateException("boom"))
            val errors = mutableListOf<String>()
            val slot = slot(fakes, TaskSource(task("t1")), backgroundScope, onError = { errors += it })
            runCurrent()

            slot.onIntent(
                TaskDetailIntent.Domain.MoveSubtask(
                    taskId = TaskId("c1"),
                    direction = ChecklistMove.Down,
                ),
            )
            runCurrent()

            assertEquals(listOf("Reorder failed"), errors)
            assertEquals(listOf("One", "Two"), slot.state.value.subtasks.map { it.title })
        }
}
