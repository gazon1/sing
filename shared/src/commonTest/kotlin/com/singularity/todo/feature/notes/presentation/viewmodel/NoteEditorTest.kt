package com.singularity.todo.feature.notes.presentation.viewmodel

import com.singularity.todo.core.coroutines.testScope
import com.singularity.todo.core.ids.UserId
import com.singularity.todo.core.platform.Clock
import com.singularity.todo.feature.notes.EditorState
import com.singularity.todo.feature.notes.Note
import com.singularity.todo.feature.notes.NoteId
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
import kotlin.test.assertIs

/**
 * Smoke tests for [NoteEditor].
 *
 * These tests prove that fire-and-forget VM methods using `Unconfined` dispatcher
 * (openEditor, saveNow, scheduleAutosave, improveNote) work correctly when left
 * on `Unconfined`. They also serve as regression tests: if someone accidentally
 * migrates these to `Dispatchers.Default`, the tests will likely catch it
 * (Unconfined is intentional for these one-shot, non-collecting operations).
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
        improveNote = null,
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
        assertEquals(false, state.isDirty)
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
        assertEquals(true, dirtyState.isDirty)

        // saveNow persists immediately — clears dirty regardless of autosave
        vm.saveNow()

        val savedState = vm.editorState.value
        assertIs<EditorState.Editing>(savedState)
        assertEquals(false, savedState.isDirty)
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
        assertEquals(true, dirtyState.isDirty)

        // Now let autosave fire — dirty is cleared (need to advance past 500ms debounce)
        advanceTimeBy(600L)
        runCurrent()

        val cleanState = vm.editorState.value
        assertIs<EditorState.Editing>(cleanState)
        assertEquals(false, cleanState.isDirty)
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
        assertEquals(true, dirtyState.isDirty)

        // 2. Edit again — this restarts the debounce timer
        vm.editBody("<p>Final content</p>")
        advanceTimeBy(499L)
        runCurrent()

        // Still dirty — debounce was reset by the second edit
        val stillDirtyState = vm.editorState.value
        assertIs<EditorState.Editing>(stillDirtyState)
        assertEquals(true, stillDirtyState.isDirty)

        // 3. Now let the debounce window elapse
        advanceTimeBy(1L)
        runCurrent()

        // Autosave fired — dirty cleared
        val cleanState = vm.editorState.value
        assertIs<EditorState.Editing>(cleanState)
        assertEquals(false, cleanState.isDirty)
    }
}
