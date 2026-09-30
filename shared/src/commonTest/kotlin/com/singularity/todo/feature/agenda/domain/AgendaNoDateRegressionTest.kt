package com.singularity.todo.feature.agenda.domain

import com.singularity.todo.core.ids.UserId
import com.singularity.todo.feature.agenda.domain.logic.AgendaEvaluator
import com.singularity.todo.feature.agenda.domain.logic.AgendaPresets
import com.singularity.todo.feature.agenda.domain.model.AgendaBadge
import com.singularity.todo.feature.tasks.domain.model.Task
import com.singularity.todo.feature.tasks.domain.model.TaskId
import com.singularity.todo.feature.tasks.domain.model.TaskKind
import com.singularity.todo.feature.tasks.domain.model.TaskPriority
import kotlinx.datetime.LocalDate
import kotlinx.datetime.Month
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pins the behaviour the desktop flow suite depends on: an active task with no
 * due date appears under the Inbox preset's "No Date" section.
 *
 * ## Why this test exists separately from [AgendaEvaluatorTest]
 *
 * That suite builds every task through its own `makeTask` helper and asserts
 * against hand-assembled `AgendaDefinition`s. The desktop failure was never
 * reproduced there, so the gap between "the matcher handles NoDate" — which
 * `SelectorMatcher` does, via a dedicated `task.dueDate == null` branch — and
 * "an undated task reaches the screen" was untested end to end.
 *
 * ## What it does *not* claim
 *
 * A green test here means the domain is sound. It says nothing about whether the
 * task reaches the agenda in the running app; that is the repository and
 * ViewModel layers, covered separately. The desktop suite reported
 * `observeAll()` returning the task while the agenda showed "No tasks", so the
 * break is above this layer and this test is the control that rules the domain
 * out.
 *
 * ## startDate / endDate
 *
 * Schema v16 added start and end dates. This test pins only the `dueDate == null`
 * case, which is what the `NoDate` selector matches on. Whether a task with a
 * `startDate` but no `dueDate` counts as "No Date" is an open product question —
 * see ADR `2026-09-30-nodate-root-cause` — and is deliberately not asserted.
 */
class AgendaNoDateRegressionTest {

    private val today: LocalDate = LocalDate(2026, Month.SEPTEMBER, 30)

    private fun undatedTask(id: String, title: String): Task = Task(
        id = TaskId(id),
        userId = UserId.anonymous,
        title = title,
        description = null,
        priority = TaskPriority.None,
        kind = TaskKind.Task,
        projectId = null,
        parentTaskId = null,
        tags = emptyList(),
        dueDate = null,
        dueTime = null,
        startDate = null,
        startTime = null,
        endDate = null,
        endTime = null,
        accentColor = null,
        emoji = null,
        completedAt = null,
        someday = false,
        archivedAt = null,
        isPinned = false,
        createdAt = kotlin.time.Instant.fromEpochMilliseconds(0),
        updatedAt = kotlin.time.Instant.fromEpochMilliseconds(0),
    )

    @Test
    fun `undated task lands in the Inbox No Date section`() {
        val task = undatedTask("t1", "Buy milk")

        val sections = AgendaEvaluator.evaluate(listOf(task), AgendaPresets.Inbox, today)

        val noDate = sections.single { it.name == "No Date" }
        assertEquals(
            listOf(task.id),
            noDate.tasks.map { it.task.id },
            "an undated active task must appear under Inbox → No Date",
        )
    }

    @Test
    fun `undated task carries the NoDate badge`() {
        val task = undatedTask("t1", "Buy milk")

        val sections = AgendaEvaluator.evaluate(listOf(task), AgendaPresets.Inbox, today)

        val row = sections.single { it.name == "No Date" }.tasks.single()
        assertEquals(
            AgendaBadge.NoDate,
            row.badge,
            "the row must be badged as undated so the UI can render it",
        )
    }

    @Test
    fun `undated task is not swallowed by the dated sections`() {
        val task = undatedTask("t1", "Buy milk")

        val sections = AgendaEvaluator.evaluate(listOf(task), AgendaPresets.Inbox, today)

        val containing = sections.filter { section -> section.tasks.any { it.task.id == task.id } }
        assertEquals(
            1,
            containing.size,
            "a task must belong to exactly one Inbox section; landed in " +
                containing.map { it.name },
        )
    }

    /**
     * Pins a contract that surprised this test's first draft, and is worth
     * stating explicitly: **the Inbox preset does not filter by completion.**
     *
     * `TaskDao.watchActive` selects on `archived_at IS NULL` and nothing else, so
     * a completed task reaches the agenda and is rendered with the
     * [AgendaBadge.Completed] badge. Only *archiving* removes a row from the
     * list. Anyone reading `AgendaPresets.Inbox` as "the active list" will write
     * the opposite assertion and be wrong.
     */
    @Test
    fun `a completed undated task is listed and badged as completed`() {
        val task = undatedTask("t1", "Buy milk")
            .copy(completedAt = kotlin.time.Instant.fromEpochMilliseconds(1))

        val sections = AgendaEvaluator.evaluate(listOf(task), AgendaPresets.Inbox, today)

        val row = sections.single { it.name == "No Date" }.tasks.single()
        assertEquals(
            AgendaBadge.Completed,
            row.badge,
            "completing a task badges it; it does not remove it from the agenda",
        )
    }

    @Test
    fun `Today preset has no No Date section, so an undated task is absent there`() {
        val task = undatedTask("t1", "Buy milk")

        val sections = AgendaEvaluator.evaluate(listOf(task), AgendaPresets.Today, today)

        assertTrue(
            sections.none { section -> section.tasks.any { it.task.id == task.id } },
            "AgendaPresets.Today declares only Overdue and Today; an undated task " +
                "has nowhere to go, which is why the desktop flow must open Inbox",
        )
    }
}
