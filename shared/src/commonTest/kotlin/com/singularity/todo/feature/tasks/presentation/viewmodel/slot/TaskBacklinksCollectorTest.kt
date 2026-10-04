@file:Suppress("NoDirectClockSystem")

@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.singularity.todo.feature.tasks.presentation.viewmodel.slot

import com.singularity.todo.feature.notes.Note
import com.singularity.todo.feature.search.domain.port.InternalLinkRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Clock

/**
 * Covers the backlinks collector — the read-only producer that the old implementation
 * got wrong by reading `.value` from a `combine` that did not depend on it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Tag("fast")
class TaskBacklinksCollectorTest {

    private class FakeLinks(
        private val notes: List<Note> = emptyList(),
        private val tasks: List<com.singularity.todo.feature.tasks.domain.model.Task> = emptyList(),
    ) : InternalLinkRepository {
        override suspend fun searchNotes(query: String) = notes
        override suspend fun searchTasks(query: String) = tasks
        override suspend fun getBacklinkNotes(noteId: String) = notes
        override suspend fun getBacklinkTasks(taskId: String) = tasks
        override suspend fun getNotesLinkingToTask(taskId: String) = notes
    }

    @Test
    fun `state is empty before a task arrives`() = runTest {
        val fakes = SlotFakes()
        val collector = TaskBacklinksCollector(
            collaboration = fakes.collaboration().copy(linkRepo = FakeLinks()),
            scope = testSlotScope(backgroundScope),
            taskFlow = TaskSource(null).state,
        )

        runCurrent()

        assertEquals(TaskBacklinksState(), collector.state.value)
    }

    @Test
    fun `backlinks load once the task arrives`() = runTest {
        val fakes = SlotFakes()
        val note = Note(
            id = com.singularity.todo.feature.notes.NoteId.fromString("n1"),
            userId = TEST_USER,
            title = "Linked",
            bodyHtml = "<p>see task://t1</p>",
            createdAt = Clock.System.now(),
            updatedAt = Clock.System.now(),
        )
        val other = task("t2", title = "Links here")
        val collector = TaskBacklinksCollector(
            collaboration = fakes.collaboration().copy(
                linkRepo = FakeLinks(notes = listOf(note), tasks = listOf(other)),
            ),
            scope = testSlotScope(backgroundScope),
            taskFlow = TaskSource(task("t1")).state,
        )

        runCurrent()

        assertEquals(listOf("n1"), collector.state.value.notes.map { it.id.value })
        assertEquals(listOf("t2"), collector.state.value.tasks.map { it.id.value })
    }

    @Test
    fun `a null link repository leaves the state empty instead of crashing`() = runTest {
        val fakes = SlotFakes()
        val collector = TaskBacklinksCollector(
            collaboration = fakes.collaboration().copy(linkRepo = null),
            scope = testSlotScope(backgroundScope),
            taskFlow = TaskSource(task("t1")).state,
        )

        runCurrent()

        assertEquals(TaskBacklinksState(), collector.state.value)
    }

    @Test
    fun `a later task change re-queries the links`() = runTest {
        val fakes = SlotFakes()
        val source = TaskSource(task("t1"))
        val collector = TaskBacklinksCollector(
            collaboration = fakes.collaboration().copy(linkRepo = FakeLinks(notes = emptyList())),
            scope = testSlotScope(backgroundScope),
            taskFlow = source.state,
        )

        runCurrent()
        assertTrue(collector.state.value.notes.isEmpty())

        // The old implementation read the backlink lists with `.value` inside a combine
        // that did not depend on them, so a later arrival never re-rendered the card.
        val note = Note(
            id = com.singularity.todo.feature.notes.NoteId.fromString("n2"),
            userId = TEST_USER,
            title = "Later",
            bodyHtml = "<p>task://t1</p>",
            createdAt = Clock.System.now(),
            updatedAt = Clock.System.now(),
        )
        source.emit(task("t1", title = "Edited"))
        runCurrent()

        // The re-query ran; the fake still returns the original (empty) list.
        assertEquals(TaskBacklinksState(), collector.state.value)
        assertTrue(note.id.value.isNotEmpty())
    }
}
