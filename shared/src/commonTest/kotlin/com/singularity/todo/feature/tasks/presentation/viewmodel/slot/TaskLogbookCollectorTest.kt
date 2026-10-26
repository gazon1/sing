@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.singularity.todo.feature.tasks.presentation.viewmodel.slot

import com.singularity.todo.feature.notes.NoteId
import com.singularity.todo.feature.tasks.domain.model.TaskId
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * What the task logbook shows.
 *
 * The logbook is a feed of the notes *attached* to a task, not of the notes that
 * mention it: a note whose body links to the task has no attachment, and it stays
 * in the task's "Linked" card. A logbook that quietly widened to include those
 * would be confidently wrong, so the boundary is pinned here.
 *
 * The notes are created through the repository rather than seeded as raw domain
 * objects: the repository stamps the identity the reader is scoped by, so the
 * test says nothing about *whose* notes it looks at — only about which notes
 * belong to the task.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Tag("fast")
class TaskLogbookCollectorTest {

    @Test
    fun `an attached note is in the logbook`() = runTest {
        val fakes = SlotFakes()
        fakes.notesRepo.createForTask(
            id = NoteId("n1"),
            taskId = TaskId("t1"),
            title = "Bought the good one",
            bodyMarkdown = null,
            bodyHtml = "<p>Bought the good one</p>",
        )

        val collector = TaskLogbookCollector(
            notesRepo = fakes.notesRepo,
            timeTrackingRepo = fakes.timeTrackingRepo,
            scope = testSlotScope(backgroundScope),
            taskFlow = TaskSource(task("t1")).state,
        )

        runCurrent()

        val state = collector.state.value
        assertIs<TaskLogbookState.Loaded>(state)
        val entries = state.entries.filterIsInstance<LogbookEntry.NoteEntry>()
        assertEquals(1, entries.size)
        assertEquals("Bought the good one", entries.first().note.title)
    }

    @Test
    fun `a note of another task is not in this task's logbook`() = runTest {
        val fakes = SlotFakes()
        fakes.notesRepo.createForTask(
            id = NoteId("n1"),
            taskId = TaskId("t2"),
            title = "Somewhere else",
            bodyMarkdown = null,
            bodyHtml = "<p>Somewhere else</p>",
        )

        val collector = TaskLogbookCollector(
            notesRepo = fakes.notesRepo,
            timeTrackingRepo = fakes.timeTrackingRepo,
            scope = testSlotScope(backgroundScope),
            taskFlow = TaskSource(task("t1")).state,
        )

        runCurrent()

        assertEquals(TaskLogbookState.Empty, collector.state.value)
    }

    @Test
    fun `a note that only links to the task is not in the logbook`() = runTest {
        val fakes = SlotFakes()
        // A `task://` link in the body — the note is *about* the task, not attached to it.
        fakes.notesRepo.createWithContent(
            id = NoteId("n1"),
            title = "Mentions the task",
            bodyMarkdown = "",
            bodyHtml = "<p>Mentions the task</p>",
        )
        fakes.notesRepo.setOutgoingLinks(NoteId("n1"), listOf("task://t1"))

        val collector = TaskLogbookCollector(
            notesRepo = fakes.notesRepo,
            timeTrackingRepo = fakes.timeTrackingRepo,
            scope = testSlotScope(backgroundScope),
            taskFlow = TaskSource(task("t1")).state,
        )

        runCurrent()

        assertEquals(TaskLogbookState.Empty, collector.state.value)
    }

    @Test
    fun `the logbook is empty for a task with nothing attached`() = runTest {
        val fakes = SlotFakes()

        val collector = TaskLogbookCollector(
            notesRepo = fakes.notesRepo,
            timeTrackingRepo = fakes.timeTrackingRepo,
            scope = testSlotScope(backgroundScope),
            taskFlow = TaskSource(task("t1")).state,
        )

        runCurrent()

        assertEquals(TaskLogbookState.Empty, collector.state.value)
    }

    @Test
    fun `a note attached later shows up without a refresh`() = runTest {
        val fakes = SlotFakes()

        val collector = TaskLogbookCollector(
            notesRepo = fakes.notesRepo,
            timeTrackingRepo = fakes.timeTrackingRepo,
            scope = testSlotScope(backgroundScope),
            taskFlow = TaskSource(task("t1")).state,
        )

        runCurrent()
        assertEquals(TaskLogbookState.Empty, collector.state.value)

        // The user writes a note from the logbook and it lands in the feed.
        fakes.notesRepo.createForTask(
            id = NoteId("n1"),
            taskId = TaskId("t1"),
            title = "Bought the good one",
            bodyMarkdown = null,
            bodyHtml = "<p>Bought the good one</p>",
        )
        runCurrent()

        val state = collector.state.value
        assertIs<TaskLogbookState.Loaded>(state)
        assertEquals(1, state.entries.filterIsInstance<LogbookEntry.NoteEntry>().size)
    }
}
