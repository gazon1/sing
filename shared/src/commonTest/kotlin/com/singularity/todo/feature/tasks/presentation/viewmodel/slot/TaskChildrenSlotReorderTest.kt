@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.singularity.todo.feature.tasks.presentation.viewmodel.slot

import com.singularity.todo.feature.checklist.ChecklistItemId
import com.singularity.todo.feature.checklist.ChecklistMove
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
 * Reordering the checklist: the order the user sets is the order the list keeps.
 *
 * Its own class because the reorder behaviour is its own story — the list is
 * renumbered, not merely the two rows swapped — and the child-slot class had
 * grown past the size a test class is allowed to be.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Tag("fast")
@Epic("Tasks")
@Feature("Presentation")
class TaskChildrenSlotReorderTest {
    @Test
    fun `moving an item down renumbers the list`() =
        runTest {
            val fakes = SlotFakes()
            fakes.checklistRepo.seed(
                checklistItem("c1", "One", 0),
                checklistItem("c2", "Two", 1),
                checklistItem("c3", "Three", 2),
            )
            val slot = slot(fakes, TaskSource(task("t1")), backgroundScope)
            runCurrent()
            assertEquals(
                listOf("One", "Two", "Three"),
                slot.state.value.checklist
                    .map { it.title },
            )

            slot.onIntent(
                TaskDetailIntent.Domain.MoveChecklistItem(
                    itemId = ChecklistItemId.fromString("c2"),
                    direction = ChecklistMove.Down,
                ),
            )
            runCurrent()

            assertEquals(
                listOf("One", "Three", "Two"),
                slot.state.value.checklist
                    .map { it.title },
                "the list is renumbered, not just the two rows swapped",
            )
            assertEquals(
                listOf(0, 1, 2),
                slot.state.value.checklist
                    .map { it.sortOrder },
            )
        }

    @Test
    fun `moving the first item up is a no-op`() =
        runTest {
            val fakes = SlotFakes()
            fakes.checklistRepo.seed(
                checklistItem("c1", "One", 0),
                checklistItem("c2", "Two", 1),
            )
            val slot = slot(fakes, TaskSource(task("t1")), backgroundScope)
            runCurrent()

            slot.onIntent(
                TaskDetailIntent.Domain.MoveChecklistItem(
                    itemId = ChecklistItemId.fromString("c1"),
                    direction = ChecklistMove.Up,
                ),
            )
            runCurrent()

            assertEquals(
                listOf("One", "Two"),
                slot.state.value.checklist
                    .map { it.title },
            )
        }

    @Test
    fun `a failed reorder is reported`() =
        runTest {
            val fakes = SlotFakes()
            fakes.checklistRepo.seed(
                checklistItem("c1", "One", 0),
                checklistItem("c2", "Two", 1),
            )
            fakes.checklistRepo.reorderOverride = Result.failure(IllegalStateException("boom"))
            val errors = mutableListOf<String>()
            val slot = slot(fakes, TaskSource(task("t1")), backgroundScope, onError = { errors += it })
            runCurrent()

            slot.onIntent(
                TaskDetailIntent.Domain.MoveChecklistItem(
                    itemId = ChecklistItemId.fromString("c1"),
                    direction = ChecklistMove.Down,
                ),
            )
            runCurrent()

            assertEquals(listOf("Reorder failed"), errors)
            assertEquals(
                listOf("One", "Two"),
                slot.state.value.checklist
                    .map { it.title },
            )
        }
}
