
@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.singularity.todo.feature.notes.presentation.viewmodel

import co.touchlab.kermit.Logger
import com.singularity.todo.core.coroutines.AutoCloseableCoroutineScope
import com.singularity.todo.core.search.SearchMode
import com.singularity.todo.feature.notes.EditorState
import com.singularity.todo.feature.notes.Note
import com.singularity.todo.feature.notes.domain.editor.NoteAiController
import com.singularity.todo.feature.projects.domain.port.ProjectsRepository
import com.singularity.todo.feature.search.domain.port.InternalLinkRepository
import com.singularity.todo.feature.tasks.domain.model.Task
import com.singularity.todo.feature.tasks.domain.model.TaskId
import com.singularity.todo.test.fakes.FakeClock
import com.singularity.todo.test.fakes.FakeIdGenerator
import com.singularity.todo.test.fakes.FakeNotesRepository
import com.singularity.todo.test.fakes.FakeProfileAwareCurrentUser
import com.singularity.todo.test.fakes.FakeProjectsRepository
import com.singularity.todo.test.fakes.FakeProposalRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The note editor's write path for a note that belongs to a task.
 *
 * The bug this pins: a note written from a task's logbook lost its attachment on
 * the first save and vanished from the logbook. The editor wrote it with the
 * *create* primitive, and Room's `@Upsert` replaces every column — so the
 * `task_id` the note was created with was overwritten with `NULL`.
 *
 * The invariant, stated once: **the editor creates the note exactly once, already
 * attached; every later save is a targeted UPDATE that touches the content
 * columns only.** These tests check it from both ends — the create is attached,
 * and the saves after it leave the attachment alone.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Tag("fast")
class NoteEditorLogbookAttachmentTest {

    private val taskId = TaskId("task-1")

    private val emptyLinkRepo = object : InternalLinkRepository {
        override suspend fun searchNotes(query: String, mode: SearchMode): List<Note> = emptyList()
        override suspend fun searchTasks(query: String, mode: SearchMode): List<Task> = emptyList()
        override suspend fun getBacklinkNotes(noteId: String): List<Note> = emptyList()
        override suspend fun getBacklinkTasks(taskId: String): List<Task> = emptyList()
        override suspend fun getNotesLinkingToTask(taskId: String): List<Note> = emptyList()
    }

    private fun createVm(notesRepo: FakeNotesRepository = FakeNotesRepository(), scope: CoroutineScope): NoteEditor {
        val clock = FakeClock()
        val currentUser = FakeProfileAwareCurrentUser()
        val projects: ProjectsRepository = FakeProjectsRepository(currentUser)
        return NoteEditor(
            repo = notesRepo,
            linkRepo = emptyLinkRepo,
            idGen = FakeIdGenerator("note"),
            ai = NoteAiController(improveNote = null),
            proposals = FakeProposalRepository(clock),
            log = Logger.withTag("NoteEditor"),
            currentUser = currentUser,
            clock = clock,
            scope = AutoCloseableCoroutineScope(scope.coroutineContext),
        )
    }

    @Test
    fun `opening the logbook's editor writes nothing`() = runTest {
        val notesRepo = FakeNotesRepository()
        val vm = createVm(notesRepo = notesRepo, scope = backgroundScope)

        vm.createNoteForTask(taskId)
        advanceTimeBy(1_000)
        runCurrent()

        // The user has not written anything yet, so there is no note to leave behind.
        assertTrue(notesRepo.notes.isEmpty(), "an untouched draft must not be written")
    }

    @Test
    fun `the first save creates the note attached to the task`() = runTest {
        val notesRepo = FakeNotesRepository()
        val vm = createVm(notesRepo = notesRepo, scope = backgroundScope)

        vm.createNoteForTask(taskId)
        advanceTimeBy(1_000)
        runCurrent()

        vm.onIntent(NotesEditorIntent.EditBody("<p>Bought the good one</p>"))
        runCurrent()
        advanceTimeBy(600L)
        runCurrent()

        val note = notesRepo.notes.values.single()
        assertEquals(taskId, note.taskId, "the note must be created already attached")
        assertEquals("<p>Bought the good one</p>", note.bodyHtml)
    }

    @Test
    fun `a later edit does not detach the note — the note stays in the logbook`() = runTest {
        val notesRepo = FakeNotesRepository()
        val vm = createVm(notesRepo = notesRepo, scope = backgroundScope)

        vm.createNoteForTask(taskId)
        advanceTimeBy(1_000)
        runCurrent()

        vm.onIntent(NotesEditorIntent.EditBody("<p>First</p>"))
        runCurrent()
        advanceTimeBy(600L)
        runCurrent()

        // The user keeps typing — every debounced keystroke is another save.
        vm.onIntent(NotesEditorIntent.EditBody("<p>Second</p>"))
        runCurrent()
        advanceTimeBy(600L)
        runCurrent()

        vm.onIntent(NotesEditorIntent.EditBody("<p>Third</p>"))
        runCurrent()
        advanceTimeBy(600L)
        runCurrent()

        val note = notesRepo.notes.values.single()
        assertEquals(taskId, note.taskId, "a content save must not detach the note")
        assertEquals("<p>Third</p>", note.bodyHtml)
    }

    @Test
    fun `the explicit save keeps the note attached`() = runTest {
        val notesRepo = FakeNotesRepository()
        val vm = createVm(notesRepo = notesRepo, scope = backgroundScope)

        vm.createNoteForTask(taskId)
        advanceTimeBy(1_000)
        runCurrent()

        vm.onIntent(NotesEditorIntent.EditBody("<p>Bought the good one</p>"))
        vm.onIntent(NotesEditorIntent.SaveNow)
        advanceTimeBy(1_000)
        runCurrent()

        val note = notesRepo.notes.values.single()
        assertEquals(taskId, note.taskId)
        assertEquals("<p>Bought the good one</p>", note.bodyHtml)
    }

    @Test
    fun `the draft keeps the task while the note is still unwritten`() = runTest {
        val notesRepo = FakeNotesRepository()
        val vm = createVm(notesRepo = notesRepo, scope = backgroundScope)

        vm.createNoteForTask(taskId)
        advanceTimeBy(1_000)
        runCurrent()

        val state = vm.stateFlow.value.draft
        assertIs<EditorState.Editing>(state)
        assertEquals(taskId, state.taskId, "the attachment lives in the draft until the note is created")
        assertTrue(state.isNew)
    }

    @Test
    fun `a note that is not from a logbook has no task`() = runTest {
        val notesRepo = FakeNotesRepository()
        val vm = createVm(notesRepo = notesRepo, scope = backgroundScope)

        vm.createNote()
        advanceTimeBy(1_000)
        runCurrent()

        vm.onIntent(NotesEditorIntent.EditBody("<p>An ordinary note</p>"))
        runCurrent()
        advanceTimeBy(600L)
        runCurrent()

        val state = vm.stateFlow.value.draft
        assertIs<EditorState.Editing>(state)
        assertNull(state.taskId)
        assertNull(notesRepo.notes.values.single().taskId)
    }
}
