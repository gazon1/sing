package com.singularity.todo.feature.agenda.domain

import com.singularity.todo.core.ids.UserId
import com.singularity.todo.feature.agenda.domain.logic.AgendaEvaluator
import com.singularity.todo.feature.agenda.domain.logic.AgendaPresets
import com.singularity.todo.feature.agenda.domain.selector.SelectorContext
import com.singularity.todo.feature.tasks.domain.logic.TaskSelection
import com.singularity.todo.feature.tasks.domain.logic.TaskSelectionRequest
import com.singularity.todo.feature.tasks.domain.logic.TaskSelectionResolver
import com.singularity.todo.feature.tasks.domain.model.Task
import com.singularity.todo.feature.tasks.domain.model.TaskId
import kotlinx.datetime.LocalDate
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * Pins what the agenda's Inbox tab holds, next to the selection that used to share
 * the name.
 *
 * ## Why this file exists
 *
 * Two things were called "inbox": this tab, which holds the tasks with no due date,
 * and the task filter that held every open task. The filter was renamed
 * (`TaskFilter.NotDeferred`, and the model-facing `active_not_deferred`), so the tab
 * kept the name — but the tab's own meaning is only as good as the test that holds
 * it. This file says, in one place, what the tab answers and how that answer differs
 * from the selection's.
 *
 * Without it, the tab could quietly widen to the selection's set (or the selection
 * could narrow to the tab's), and the two would agree on the name again.
 */
@Tag("fast")
class AgendaInboxTabMeaningTest {
    private val today = LocalDate(2026, 10, 14)
    private val epoch = Instant.fromEpochMilliseconds(0)

    private val context =
        SelectorContext(
            today = today,
            now = epoch,
            blockedIds = emptySet(),
        )

    @Test
    fun `the Inbox tab holds the tasks with no due date, and nothing else`() {
        val undated = task("undated")
        val dated = task("dated", dueDate = today)

        val sections = AgendaEvaluator.evaluate(listOf(undated, dated), AgendaPresets.Inbox, today, epoch)

        val inTab = sections.flatMap { it.tasks }.map { it.task.id.value }.toSet()
        assertEquals(setOf("undated"), inTab, "the Inbox tab is the tasks that are not dated yet")
        assertEquals(
            "No Date",
            sections.single { it.tasks.isNotEmpty() }.name,
            "the tab holds them under one section",
        )
    }

    @Test
    fun `the tab and the selection answer different questions`() {
        val dated = task("dated", dueDate = today)

        assertTrue(
            TaskSelectionResolver.matches(
                TaskSelectionRequest(selection = TaskSelection.ACTIVE_NOT_DEFERRED),
                dated,
                context,
            ),
            "the selection holds the dated tasks too — it answers 'what is still to do'",
        )

        val inTab =
            AgendaEvaluator
                .evaluate(listOf(dated), AgendaPresets.Inbox, today, epoch)
                .flatMap { it.tasks }
                .map { it.task.id.value }
                .toSet()
        assertFalse(
            inTab.contains("dated"),
            "the tab does not hold it — the tab answers 'what is not scheduled yet'",
        )
    }

    private fun task(
        id: String,
        dueDate: LocalDate? = null,
    ): Task =
        Task(
            id = TaskId(id),
            title = "T $id",
            dueDate = dueDate,
            createdAt = epoch,
            updatedAt = epoch,
            userId = UserId("test"),
        )
}
