
@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.singularity.todo.feature.tasks.presentation.viewmodel.slot

import com.singularity.todo.feature.tasks.domain.model.Task
import com.singularity.todo.feature.tasks.domain.model.TaskId
import com.singularity.todo.feature.tasks.domain.state.ScheduleChange
import com.singularity.todo.feature.tasks.domain.state.TaskDetailIntent
import io.qameta.allure.Epic
import io.qameta.allure.Feature
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Covers the planning facts of the entity slot: the due date, its time, the deadline
 * and the `someday` bucket, and the rules about which of them a change touches.
 *
 * They are the rules a user cannot see, so they are the rules worth a test — and the
 * rules are applied in one place, so they are tested in one place.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Tag("fast")
@Epic("Tasks")
@Feature("Presentation")
class TaskEntitySlotScheduleTest {
    @Test
    fun `clear due date writes null`() =
        runTest {
            val fakes = SlotFakes()
            val source = TaskSource(task("t1").copy(dueDate = LocalDate(2026, 9, 27)))
            val slot =
                TaskEntitySlot(
                    taskId = TaskId("t1"),
                    core = fakes.core(),
                    children = fakes.children(),
                    scope = testSlotScope(backgroundScope),
                    taskFlow = source.state,
                ) {}
            runCurrent()

            slot.onIntent(
                TaskDetailIntent.Domain.SetSchedule(
                    dueDate = ScheduleChange.Value(null),
                ),
            )
            runCurrent()

            assertNull(
                fakes.taskRepo.tasks.value["t1"]
                    ?.dueDate,
            )
        }

    @Test
    fun `clearing the due date clears the time with it and keeps the deadline`() =
        runTest {
            val fakes = SlotFakes()
            fakes.taskRepo.seed(
                task("t1").copy(
                    dueDate = LocalDate(2026, 9, 27),
                    dueTime = LocalTime(14, 30),
                    endDate = LocalDate(2026, 9, 30),
                ),
            )
            val source =
                TaskSource(
                    task("t1").copy(
                        dueDate = LocalDate(2026, 9, 27),
                        dueTime = LocalTime(14, 30),
                        endDate = LocalDate(2026, 9, 30),
                    ),
                )
            val slot =
                TaskEntitySlot(
                    taskId = TaskId("t1"),
                    core = fakes.core(),
                    children = fakes.children(),
                    scope = testSlotScope(backgroundScope),
                    taskFlow = source.state,
                ) {}
            runCurrent()

            slot.onIntent(
                TaskDetailIntent.Domain.SetSchedule(
                    dueDate = ScheduleChange.Value(null),
                ),
            )
            runCurrent()

            val written = fakes.taskRepo.tasks.value["t1"]
            assertNull(written?.dueDate, "the date must be gone")
            assertNull(
                written?.dueTime,
                "a time without a date is not a moment — it goes with the date",
            )
            assertEquals(
                LocalDate(2026, 9, 30),
                written?.endDate,
                "the deadline is a bound of its own — clearing the date must not erase it",
            )
        }

    @Test
    fun `setting a date takes the task out of the someday bucket`() =
        runTest {
            val fakes = SlotFakes()
            fakes.taskRepo.seed(task("t1").copy(someday = true))
            val source = TaskSource(task("t1").copy(someday = true))
            val slot =
                TaskEntitySlot(
                    taskId = TaskId("t1"),
                    core = fakes.core(),
                    children = fakes.children(),
                    scope = testSlotScope(backgroundScope),
                    taskFlow = source.state,
                ) {}
            runCurrent()

            slot.onIntent(
                TaskDetailIntent.Domain.SetSchedule(
                    dueDate = ScheduleChange.Value(LocalDate(2026, 9, 27)),
                ),
            )
            runCurrent()

            assertEquals(
                false,
                fakes.taskRepo.tasks.value["t1"]
                    ?.someday,
                "a dated task is not a deferred one",
            )
        }

    @Test
    fun `the someday preset clears the date and the time and keeps the deadline`() =
        runTest {
            val fakes = SlotFakes()
            fakes.taskRepo.seed(
                task("t1").copy(
                    dueDate = LocalDate(2026, 9, 27),
                    dueTime = LocalTime(14, 30),
                    endDate = LocalDate(2026, 9, 30),
                ),
            )
            val source =
                TaskSource(
                    task("t1").copy(
                        dueDate = LocalDate(2026, 9, 27),
                        dueTime = LocalTime(14, 30),
                        endDate = LocalDate(2026, 9, 30),
                    ),
                )
            val slot =
                TaskEntitySlot(
                    taskId = TaskId("t1"),
                    core = fakes.core(),
                    children = fakes.children(),
                    scope = testSlotScope(backgroundScope),
                    taskFlow = source.state,
                ) {}
            runCurrent()

            slot.onIntent(
                TaskDetailIntent.Domain.SetSchedule(
                    dueDate = ScheduleChange.Value(null),
                    dueTime = ScheduleChange.Value(null),
                    someday = ScheduleChange.Value(true),
                ),
            )
            runCurrent()

            val written = fakes.taskRepo.tasks.value["t1"]
            assertEquals(true, written?.someday)
            assertNull(written?.dueDate, "`someday` means no date")
            assertNull(written?.dueTime)
            assertEquals(
                LocalDate(2026, 9, 30),
                written?.endDate,
                "the deadline is not the date — it stays",
            )
        }

    @Test
    fun `the deadline is written on its own field`() =
        runTest {
            val fakes = SlotFakes()
            fakes.taskRepo.seed(task("t1"))
            val source = TaskSource(task("t1"))
            val slot =
                TaskEntitySlot(
                    taskId = TaskId("t1"),
                    core = fakes.core(),
                    children = fakes.children(),
                    scope = testSlotScope(backgroundScope),
                    taskFlow = source.state,
                ) {}
            runCurrent()

            slot.onIntent(
                TaskDetailIntent.Domain.SetSchedule(
                    deadline = ScheduleChange.Value(LocalDate(2026, 9, 30)),
                ),
            )
            runCurrent()

            assertEquals(
                LocalDate(2026, 9, 30),
                fakes.taskRepo.tasks.value["t1"]
                    ?.endDate,
            )
        }

    @Test
    fun `clearing the deadline leaves the due date alone`() =
        runTest {
            val fakes = SlotFakes()
            fakes.taskRepo.seed(task("t1").copy(dueDate = LocalDate(2026, 9, 27)))
            val source = TaskSource(task("t1").copy(dueDate = LocalDate(2026, 9, 27)))
            val slot =
                TaskEntitySlot(
                    taskId = TaskId("t1"),
                    core = fakes.core(),
                    children = fakes.children(),
                    scope = testSlotScope(backgroundScope),
                    taskFlow = source.state,
                ) {}
            runCurrent()

            slot.onIntent(
                TaskDetailIntent.Domain.SetSchedule(
                    deadline = ScheduleChange.Value(null),
                ),
            )
            runCurrent()

            val written = fakes.taskRepo.tasks.value["t1"]
            assertNull(written?.endDate)
            assertEquals(
                LocalDate(2026, 9, 27),
                written?.dueDate,
                "the deadline and the due date are facts of their own",
            )
        }

    @Test
    fun `the whole schedule is written in one go`() =
        runTest {
            val fakes = SlotFakes()
            fakes.taskRepo.seed(task("t1"))
            val source = TaskSource(task("t1"))
            val slot =
                TaskEntitySlot(
                    taskId = TaskId("t1"),
                    core = fakes.core(),
                    children = fakes.children(),
                    scope = testSlotScope(backgroundScope),
                    taskFlow = source.state,
                ) {}
            runCurrent()

            // Every write the repository accepts lands in the store as one state.
            val writes = mutableListOf<Map<String, Task>>()
            backgroundScope.launch {
                fakes.taskRepo.tasks.collect { writes += it }
            }
            runCurrent()
            writes.clear()

            slot.onIntent(
                TaskDetailIntent.Domain.SetSchedule(
                    dueDate = ScheduleChange.Value(LocalDate(2026, 9, 27)),
                    dueTime = ScheduleChange.Value(LocalTime(14, 30)),
                    deadline = ScheduleChange.Value(LocalDate(2026, 9, 30)),
                    someday = ScheduleChange.Value(false),
                ),
            )
            runCurrent()

            assertEquals(
                1,
                writes.size,
                "four facts in one save must be one write — four writes can leave the task halfway",
            )
            val written = fakes.taskRepo.tasks.value["t1"]
            assertEquals(LocalDate(2026, 9, 27), written?.dueDate)
            assertEquals(LocalTime(14, 30), written?.dueTime)
            assertEquals(LocalDate(2026, 9, 30), written?.endDate)
            assertEquals(false, written?.someday)
        }

    @Test
    fun `a failed schedule write leaves no half-saved schedule`() =
        runTest {
            val fakes = SlotFakes()
            fakes.taskRepo.seed(task("t1").copy(dueDate = LocalDate(2026, 9, 27)))
            val source = TaskSource(task("t1").copy(dueDate = LocalDate(2026, 9, 27)))
            val errors = mutableListOf<String>()
            val slot =
                TaskEntitySlot(
                    taskId = TaskId("t1"),
                    core = fakes.core(),
                    children = fakes.children(),
                    scope = testSlotScope(backgroundScope),
                    taskFlow = source.state,
                    onUndoRemoveTag = {},
                    onError = { errors += it },
                )
            runCurrent()
            fakes.taskRepo.updateOverride = Result.failure(IllegalStateException("no"))

            slot.onIntent(
                TaskDetailIntent.Domain.SetSchedule(
                    dueDate = ScheduleChange.Value(LocalDate(2026, 10, 4)),
                    deadline = ScheduleChange.Value(LocalDate(2026, 10, 6)),
                ),
            )
            runCurrent()

            val written = fakes.taskRepo.tasks.value["t1"]
            assertEquals(
                LocalDate(2026, 9, 27),
                written?.dueDate,
                "the date must be the old one — a failed save writes nothing at all",
            )
            assertNull(
                written?.endDate,
                "the deadline must not be written when the save failed",
            )
            assertEquals(1, errors.size, "the failure is reported")
        }

    @Test
    fun `a date and the someday bucket never live together`() =
        runTest {
            val fakes = SlotFakes()
            fakes.taskRepo.seed(task("t1").copy(someday = true))
            val source = TaskSource(task("t1").copy(someday = true))
            val slot =
                TaskEntitySlot(
                    taskId = TaskId("t1"),
                    core = fakes.core(),
                    children = fakes.children(),
                    scope = testSlotScope(backgroundScope),
                    taskFlow = source.state,
                ) {}
            runCurrent()

            slot.onIntent(
                TaskDetailIntent.Domain.SetSchedule(
                    dueDate = ScheduleChange.Value(LocalDate(2026, 9, 27)),
                ),
            )
            runCurrent()

            val written = fakes.taskRepo.tasks.value["t1"]
            assertEquals(LocalDate(2026, 9, 27), written?.dueDate)
            assertEquals(false, written?.someday, "a dated task is not a deferred one")
        }

    @Test
    fun `clearing the date does not put the task into the someday bucket`() =
        runTest {
            val fakes = SlotFakes()
            fakes.taskRepo.seed(task("t1"))
            val source = TaskSource(task("t1"))
            val slot =
                TaskEntitySlot(
                    taskId = TaskId("t1"),
                    core = fakes.core(),
                    children = fakes.children(),
                    scope = testSlotScope(backgroundScope),
                    taskFlow = source.state,
                ) {}
            runCurrent()

            slot.onIntent(
                TaskDetailIntent.Domain.SetSchedule(
                    dueDate = ScheduleChange.Value(null),
                ),
            )
            runCurrent()

            val written = fakes.taskRepo.tasks.value["t1"]
            assertNull(written?.dueDate)
            assertEquals(
                false,
                written?.someday,
                "a task without a date is not a deferred task — the user did not ask for that",
            )
        }
}
