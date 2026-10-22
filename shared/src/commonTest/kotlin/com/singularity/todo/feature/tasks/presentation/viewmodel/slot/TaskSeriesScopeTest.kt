@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.singularity.todo.feature.tasks.presentation.viewmodel.slot

import com.singularity.todo.feature.tasks.domain.model.TaskPriority
import com.singularity.todo.feature.tasks.domain.model.TaskSeriesId
import com.singularity.todo.feature.tasks.presentation.state.TaskDetailIntent
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Covers the scope rule of a series: a field that describes the *work* is written to
 * every member, and a field that describes the *occurrence* is written to that member
 * alone.
 *
 * The rule itself lives in one place — [sharedFieldOf] — and both slots ask it. These
 * tests pin the behaviour of the two slots that use it, so that a field added to
 * [com.singularity.todo.feature.tasks.domain.model.TaskSharedField] cannot end up written
 * to one occurrence by accident.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Tag("fast")
class TaskSeriesScopeTest {

    private val seriesId = TaskSeriesId("series-1")

    /** Three members of one series, each on its own date. */
    private fun seedSeries(fakes: SlotFakes): List<com.singularity.todo.feature.tasks.domain.model.Task> {
        val members = listOf(
            task("s1", title = "Pay the rent").copy(
                seriesId = seriesId,
                dueDate = LocalDate(2026, 10, 5),
            ),
            task("s2", title = "Pay the rent").copy(
                seriesId = seriesId,
                dueDate = LocalDate(2026, 10, 12),
            ),
            task("s3", title = "Pay the rent").copy(
                seriesId = seriesId,
                dueDate = LocalDate(2026, 10, 19),
            ),
        )
        fakes.taskRepo.seed(*members.toTypedArray())
        return members
    }

    // ─── A shared field reaches every member ───────────────────────────────

    @Test
    fun `a shared field is written to every member of the series`() = runTest {
        val fakes = SlotFakes()
        seedSeries(fakes)
        val source = TaskSource(task("s1", title = "Pay the rent").copy(seriesId = seriesId))
        val slot = TaskEntitySlot(
            taskId = com.singularity.todo.feature.tasks.domain.model.TaskId("s1"),
            core = fakes.core(),
            children = fakes.children(),
            scope = testSlotScope(backgroundScope),
            taskFlow = source.state,
        ) {}
        runCurrent()

        slot.onIntent(TaskDetailIntent.Domain.SetPriority(TaskPriority.High))
        runCurrent()

        val priorities = fakes.taskRepo.tasks.value.values.map { it.priority }.toSet()
        assertEquals(
            setOf(TaskPriority.High),
            priorities,
            "the priority describes the work, so every member carries it",
        )
    }

    // ─── An occurrence field stays with its occurrence ─────────────────────

    @Test
    fun `an occurrence field is written to that member alone`() = runTest {
        val fakes = SlotFakes()
        seedSeries(fakes)
        val source = TaskSource(task("s1", title = "Pay the rent").copy(seriesId = seriesId))
        val slot = TaskEntitySlot(
            taskId = com.singularity.todo.feature.tasks.domain.model.TaskId("s1"),
            core = fakes.core(),
            children = fakes.children(),
            scope = testSlotScope(backgroundScope),
            taskFlow = source.state,
        ) {}
        runCurrent()

        slot.onIntent(TaskDetailIntent.Domain.SetDueDate(LocalDate(2026, 10, 6)))
        runCurrent()

        val dates = fakes.taskRepo.tasks.value.values.associate { it.id.value to it.dueDate }
        assertEquals(
            LocalDate(2026, 10, 6),
            dates["s1"],
            "the date of an occurrence is its own",
        )
        assertEquals(
            LocalDate(2026, 10, 12),
            dates["s2"],
            "the other occurrences keep their dates",
        )
    }

    // ─── The title of the work reaches every member ───────────────────────

    @Test
    fun `the title reaches every member of the series`() = runTest {
        val fakes = SlotFakes()
        seedSeries(fakes)
        val source = TaskSource(task("s1", title = "Pay the rent").copy(seriesId = seriesId))
        val slot = TaskDraftSlot(
            core = fakes.core(),
            context = fakes.context(),
            scope = testSlotScope(backgroundScope),
            taskFlow = source.state,
        ) {}
        runCurrent()

        slot.onIntent(TaskDetailIntent.Domain.TitleChanged("Pay the rent in full"))
        // The write is debounced; the debounce is crossed with virtual time.
        advanceTimeBy(1_000)
        runCurrent()

        val titles = fakes.taskRepo.tasks.value.values.map { it.title }.toSet()
        assertEquals(
            setOf("Pay the rent in full"),
            titles,
            "a series whose rows carry three titles says three things about one job",
        )
    }

    // ─── A task that stands alone is untouched by the rule ────────────────

    @Test
    fun `a task that stands alone is written alone`() = runTest {
        val fakes = SlotFakes()
        fakes.taskRepo.seed(task("solo", title = "Solo"))
        val source = TaskSource(task("solo", title = "Solo"))
        val slot = TaskEntitySlot(
            taskId = com.singularity.todo.feature.tasks.domain.model.TaskId("solo"),
            core = fakes.core(),
            children = fakes.children(),
            scope = testSlotScope(backgroundScope),
            taskFlow = source.state,
        ) {}
        runCurrent()

        slot.onIntent(TaskDetailIntent.Domain.SetPriority(TaskPriority.Low))
        runCurrent()

        assertEquals(TaskPriority.Low, fakes.taskRepo.tasks.value.values.first().priority)
        assertEquals(1, fakes.taskRepo.tasks.value.size)
    }
}
