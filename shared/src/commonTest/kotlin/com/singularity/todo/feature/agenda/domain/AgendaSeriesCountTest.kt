package com.singularity.todo.feature.agenda.domain

import com.singularity.todo.core.ids.UserId
import com.singularity.todo.feature.agenda.domain.logic.AgendaEvaluator
import com.singularity.todo.feature.agenda.domain.model.AgendaDefinition
import com.singularity.todo.feature.agenda.domain.model.Section
import com.singularity.todo.feature.agenda.domain.model.Selector
import com.singularity.todo.feature.tasks.domain.model.Task
import com.singularity.todo.feature.tasks.domain.model.TaskId
import com.singularity.todo.feature.tasks.domain.model.TaskKind
import com.singularity.todo.feature.tasks.domain.model.TaskSeriesId
import kotlinx.datetime.LocalDate
import kotlinx.datetime.Month
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The day's list says how many dates the work has.
 *
 * A row of a series carries the number of dates of that series, so the user can
 * see that the row is one date of something bigger. The number is counted once
 * for the whole task list, not per row.
 */
@Tag("fast")
class AgendaSeriesCountTest {

    private val today: LocalDate = LocalDate(2026, Month.SEPTEMBER, 16)

    private fun task(id: String, seriesId: TaskSeriesId? = null): Task = Task(
        id = TaskId(id),
        title = "Task $id",
        kind = TaskKind.Task,
        dueDate = today,
        createdAt = kotlin.time.Instant.fromEpochMilliseconds(0),
        updatedAt = kotlin.time.Instant.fromEpochMilliseconds(0),
        userId = UserId("test-user"),
        seriesId = seriesId,
    )

    private fun rowsOf(tasks: List<Task>) = AgendaEvaluator.evaluate(
        tasks,
        AgendaDefinition(
            title = "Test",
            sections = listOf(Section("s", "s", 0, Selector.Anything)),
        ),
        today,
        kotlin.time.Instant.fromEpochMilliseconds(0),
    ).singleOrNull()?.tasks.orEmpty()

    @Test
    fun `a row of a series carries the number of dates of the series`() {
        val series = TaskSeriesId("s1")
        val rows = rowsOf(
            listOf(
                task("t1", series),
                task("t2", series),
                task("t3", series),
            ),
        )

        assertEquals(listOf(3, 3, 3), rows.map { it.seriesDates })
    }

    @Test
    fun `a task that stands alone carries no count`() {
        val rows = rowsOf(listOf(task("t1")))

        assertNull(rows.single().seriesDates)
    }

    @Test
    fun `two series carry their own counts`() {
        val rows = rowsOf(
            listOf(
                task("t1", TaskSeriesId("s1")),
                task("t2", TaskSeriesId("s1")),
                task("t3", TaskSeriesId("s2")),
            ),
        )

        assertEquals(listOf("t1" to 2, "t2" to 2, "t3" to 1), rows.map { it.task.id.value to it.seriesDates })
    }
}
