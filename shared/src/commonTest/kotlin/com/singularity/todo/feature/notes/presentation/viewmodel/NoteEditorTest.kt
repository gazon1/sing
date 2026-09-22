package com.singularity.todo.feature.notes.presentation.viewmodel

import co.touchlab.kermit.Logger
import com.singularity.todo.core.coroutines.testScope
import com.singularity.todo.core.ids.UserId
import com.singularity.todo.core.platform.Clock
import com.singularity.todo.feature.notes.EditorState
import com.singularity.todo.feature.notes.Note
import com.singularity.todo.feature.notes.NoteId
import com.singularity.todo.feature.notes.domain.editor.NoteAiController
import com.singularity.todo.feature.search.InternalLinkRepository
import com.singularity.todo.feature.tasks.domain.model.Task
import com.singularity.todo.test.fakes.FakeIdGenerator
import com.singularity.todo.test.fakes.FakeNotesRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Smoke tests for [NoteEditor].
 *
 * Tests prove that openEditor / editBody / editTitle / saveNow / closeEditor
 * work correctly with the canonical VM shape.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NoteEditorTest {

    private val testUserId = UserId("test-user")

    private val testNote = Note(
        id = NoteId("note-1"),
        userId = testUserId,
        title = "Test Note",
        bodyHtml = "<p>Hello world</p>",
        wordCount = 2,
        charCount = 11,
        createdAt = Clock.now(),
        updatedAt = Clock.now(),
    )

    private val emptyLinkRepo = object : InternalLinkRepository {
        override suspend fun searchNotes(query: String): List<Note> = emptyList()
        override suspend fun searchTasks(query: String): List<Task> = emptyList()
        override suspend fun getBacklinkNotes(noteId: String): List<Note> = emptyList()
    }

    private fun createVm(
        notesRepo: FakeNotesRepository = FakeNotesRepository(),
        scope: CoroutineScope,
    ): NoteEditor = NoteEditor(
        repo = notesRepo,
        linkRepo = emptyLinkRepo,
        idGen = FakeIdGenerator("note"),
        ai = NoteAiController(improveNote = null),
        log = Logger.withTag("NoteEditor"),
        scope = testScope(scope),
    )

    @Test
    fun `openEditor loads Editing state`() = runTest {
        val notesRepo = FakeNotesRepository()
        notesRepo.seed(testNote)
        val vm = createVm(notesRepo = notesRepo, scope = this)

        vm.openEditor(testNote.id.value)
        advanceUntilIdle()

        val state = vm.editorState.value
        assertIs<EditorState.Editing>(state)
        assertEquals(testNote.id.value, state.id)
        assertEquals("Test Note", state.title)
        assertEquals("<p>Hello world</p>", state.html)
        assertFalse(state.isDirty)
        assertFalse(state.isNew)
    }

    @Test
    fun `saveNow persists and clears dirty`() = runTest {
        val notesRepo = FakeNotesRepository()
        notesRepo.seed(testNote)
        val vm = createVm(notesRepo = notesRepo, scope = this)

        vm.openEditor(testNote.id.value)
        advanceUntilIdle()

        // Edit body — dirty becomes true; the debounce (500ms) hasn't fired yet
        // because advanceTimeBy(400L) stays below the debounce threshold.
        vm.editBody("<p>Updated content</p>")
        advanceTimeBy(400L) // debounce not reached yet

        val dirtyState = vm.editorState.value
        assertIs<EditorState.Editing>(dirtyState)
        assertTrue(dirtyState.isDirty)

        // saveNow persists immediately — clears dirty regardless of autosave
        vm.saveNow()
        advanceUntilIdle()

        val savedState = vm.editorState.value
        assertIs<EditorState.Editing>(savedState)
        assertFalse(savedState.isDirty)
        assertEquals("<p>Updated content</p>", savedState.html)
    }

    @Test
    fun `editBody marks dirty then autosave clears it`() = runTest {
        val notesRepo = FakeNotesRepository()
        notesRepo.seed(testNote)
        val vm = createVm(notesRepo = notesRepo, scope = this)

        vm.openEditor(testNote.id.value)
        advanceUntilIdle()

        // Edit body — dirty becomes true; debounce (500ms) not yet reached
        vm.editBody("<p>Updated content</p>")
        advanceTimeBy(400L) // debounce not reached yet

        val dirtyState = vm.editorState.value
        assertIs<EditorState.Editing>(dirtyState)
        assertTrue(dirtyState.isDirty)

        // Now let autosave fire — dirty is cleared (need to advance past 500ms debounce)
        advanceTimeBy(600L)
        runCurrent()

        val cleanState = vm.editorState.value
        assertIs<EditorState.Editing>(cleanState)
        assertFalse(cleanState.isDirty)
    }

    @Test
    fun `autosave debounce does not fire before 500ms`() = runTest {
        val notesRepo = FakeNotesRepository()
        notesRepo.seed(testNote)
        val vm = createVm(notesRepo = notesRepo, scope = this)

        vm.openEditor(testNote.id.value)
        advanceUntilIdle()

        // 1. Edit body — autosave scheduled but not yet fired
        vm.editBody("<p>Preliminary content</p>")
        advanceTimeBy(499L)
        runCurrent()

        // Dirty is true, but autosave hasn't fired yet (debounce not elapsed)
        val dirtyState = vm.editorState.value
        assertIs<EditorState.Editing>(dirtyState)
        assertTrue(dirtyState.isDirty)

        // 2. Edit again — this restarts the debounce timer
        vm.editBody("<p>Final content</p>")
        advanceTimeBy(499L)
        runCurrent()

        // Still dirty — debounce was reset by the second edit
        val stillDirtyState = vm.editorState.value
        assertIs<EditorState.Editing>(stillDirtyState)
        assertTrue(stillDirtyState.isDirty)

        // 3. Now let the debounce window elapse
        advanceTimeBy(1L)
        runCurrent()

        // Autosave fired — dirty cleared
        val cleanState = vm.editorState.value
        assertIs<EditorState.Editing>(cleanState)
        assertFalse(cleanState.isDirty)
    }

    @Test
    fun `createNote marks isNew and first save calls createWithContent`() = runTest {
        val notesRepo = FakeNotesRepository()
        val vm = createVm(notesRepo = notesRepo, scope = this)

        val newId = vm.createNote()
        advanceUntilIdle()

        // isNew is true, note is not yet in the repo
        val state = vm.editorState.value
        assertIs<EditorState.Editing>(state)
        assertTrue(state.isNew)
        assertEquals(newId, state.id)
        assertTrue(notesRepo.notes.isEmpty())

        // First save — should call createWithContent (isNew=true)
        vm.editBody("<p>Content</p>")
        advanceTimeBy(600L)
        runCurrent()

        assertFalse(notesRepo.notes.isEmpty())
        assertEquals("<p>Content</p>", notesRepo.notes[newId]?.bodyHtml)

        // After save, isNew is cleared
        val savedState = vm.editorState.value
        assertIs<EditorState.Editing>(savedState)
        assertFalse(savedState.isNew)
    }

    @Test
    fun `closeEditor clears state without saving`() = runTest {
        val notesRepo = FakeNotesRepository()
        notesRepo.seed(testNote)
        val vm = createVm(notesRepo = notesRepo, scope = this)

        vm.openEditor(testNote.id.value)
        advanceUntilIdle()

        vm.editBody("<p>Unsaved changes</p>")
        advanceTimeBy(100L) // well under debounce

        val dirtyState = vm.editorState.value
        assertIs<EditorState.Editing>(dirtyState)
        assertTrue(dirtyState.isDirty)

        // Close without saving — note should NOT be updated in repo
        vm.closeEditor()

        val emptyState = vm.editorState.value
        assertIs<EditorState.Empty>(emptyState)
        // Original content unchanged
        assertEquals("<p>Hello world</p>", notesRepo.notes[testNote.id.value]?.bodyHtml)
    }
}
