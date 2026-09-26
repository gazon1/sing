package com.singularity.todo.feature.notes.presentation.viewmodel

import co.touchlab.kermit.Logger
import com.singularity.todo.core.coroutines.AutoCloseableCoroutineScope
import com.singularity.todo.core.platform.Clock
import com.singularity.todo.feature.notes.EditorState
import com.singularity.todo.feature.notes.Note
import com.singularity.todo.feature.notes.NoteId
import com.singularity.todo.feature.notes.presentation.viewmodel.NotesEditorIntent
import com.singularity.todo.feature.notes.NotesUiEvent
import com.singularity.todo.feature.notes.domain.editor.NoteAiController
import com.singularity.todo.feature.search.InternalLinkRepository
import com.singularity.todo.feature.tasks.domain.model.Task
import com.singularity.todo.test.fakes.FakeIdGenerator
import com.singularity.todo.test.fakes.FakeNotesRepository
import com.singularity.todo.test.fakes.FakeProfileAwareCurrentUser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
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

    private val testUserId = com.singularity.todo.core.ids.UserId("test-user")

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
        override suspend fun getBacklinkTasks(taskId: String): List<Task> = emptyList()
        override suspend fun getNotesLinkingToTask(taskId: String): List<Note> = emptyList()
    }

    private fun createVm(notesRepo: FakeNotesRepository = FakeNotesRepository(), scope: CoroutineScope): NoteEditor =
        NoteEditor(
            repo = notesRepo,
            linkRepo = emptyLinkRepo,
            idGen = FakeIdGenerator("note"),
            ai = NoteAiController(improveNote = null),
            log = Logger.withTag("NoteEditor"),
            currentUser = FakeProfileAwareCurrentUser(),
            scope = AutoCloseableCoroutineScope(scope.coroutineContext),
        )

    @Test
    fun `openEditor loads Editing state`() = runTest {
        val notesRepo = FakeNotesRepository()
        notesRepo.seed(testNote)
        val vm = createVm(notesRepo = notesRepo, scope = backgroundScope)

        vm.openEditor(testNote.id.value)
        advanceTimeBy(1_000); runCurrent()

        val state = vm.state.value.draft
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
        val vm = createVm(notesRepo = notesRepo, scope = backgroundScope)

        vm.openEditor(testNote.id.value)
        advanceTimeBy(1_000); runCurrent()

        // Edit body — dirty becomes true; the debounce (500ms) hasn't fired yet
        // because advanceTimeBy(400L) stays below the debounce threshold.
        vm.onIntent(NotesEditorIntent.EditBody("<p>Updated content</p>"))
        runCurrent()
        advanceTimeBy(400L) // debounce not reached yet

        val dirtyState = vm.state.value.draft
        assertIs<EditorState.Editing>(dirtyState)
        assertTrue(dirtyState.isDirty)

        // saveNow persists immediately — clears dirty regardless of autosave
        vm.onIntent(NotesEditorIntent.SaveNow)
        runCurrent()
        advanceTimeBy(1_000); runCurrent()

        val savedState = vm.state.value.draft
        assertIs<EditorState.Editing>(savedState)
        assertFalse(savedState.isDirty)
        assertEquals("<p>Updated content</p>", savedState.html)
    }

    @Test
    fun `editBody marks dirty then autosave clears it`() = runTest {
        val notesRepo = FakeNotesRepository()
        notesRepo.seed(testNote)
        val vm = createVm(notesRepo = notesRepo, scope = backgroundScope)

        vm.openEditor(testNote.id.value)
        advanceTimeBy(1_000); runCurrent()

        // Edit body — dirty becomes true; debounce (500ms) not yet reached
        vm.onIntent(NotesEditorIntent.EditBody("<p>Updated content</p>"))
        runCurrent()
        advanceTimeBy(400L) // debounce not reached yet

        val dirtyState = vm.state.value.draft
        assertIs<EditorState.Editing>(dirtyState)
        assertTrue(dirtyState.isDirty)

        // Now let autosave fire — dirty is cleared (need to advance past 500ms debounce)
        advanceTimeBy(600L)
        runCurrent()

        val cleanState = vm.state.value.draft
        assertIs<EditorState.Editing>(cleanState)
        assertFalse(cleanState.isDirty)
    }

    @Test
    fun `autosave debounce does not fire before 500ms`() = runTest {
        val notesRepo = FakeNotesRepository()
        notesRepo.seed(testNote)
        val vm = createVm(notesRepo = notesRepo, scope = backgroundScope)

        vm.openEditor(testNote.id.value)
        advanceTimeBy(1_000); runCurrent()

        // 1. Edit body — autosave scheduled but not yet fired
        vm.onIntent(NotesEditorIntent.EditBody("<p>Preliminary content</p>"))
        runCurrent()
        advanceTimeBy(499L)
        runCurrent()

        // Dirty is true, but autosave hasn't fired yet (debounce not elapsed)
        val dirtyState = vm.state.value.draft
        assertIs<EditorState.Editing>(dirtyState)
        assertTrue(dirtyState.isDirty)

        // 2. Edit again — this restarts the debounce timer
        vm.onIntent(NotesEditorIntent.EditBody("<p>Final content</p>"))
        runCurrent()
        advanceTimeBy(499L)
        runCurrent()

        // Still dirty — debounce was reset by the second edit
        val stillDirtyState = vm.state.value.draft
        assertIs<EditorState.Editing>(stillDirtyState)
        assertTrue(stillDirtyState.isDirty)

        // 3. Now let the debounce window elapse
        advanceTimeBy(1L)
        runCurrent()

        // Autosave fired — dirty cleared
        val cleanState = vm.state.value.draft
        assertIs<EditorState.Editing>(cleanState)
        assertFalse(cleanState.isDirty)
    }

    @Test
    fun `createNote marks isNew and first save calls createWithContent`() = runTest {
        val notesRepo = FakeNotesRepository()
        val vm = createVm(notesRepo = notesRepo, scope = backgroundScope)

        val newId = vm.createNote()
        runCurrent()

        advanceTimeBy(1_000); runCurrent()

        // isNew is true, note is not yet in the repo
        val state = vm.state.value.draft
        assertIs<EditorState.Editing>(state)
        assertTrue(state.isNew)
        assertEquals(newId, state.id)
        assertTrue(notesRepo.notes.isEmpty())

        // First save — should call createWithContent (isNew=true)
        vm.onIntent(NotesEditorIntent.EditBody("<p>Content</p>"))
        runCurrent()
        advanceTimeBy(600L)
        runCurrent()

        assertFalse(notesRepo.notes.isEmpty())
        assertEquals("<p>Content</p>", notesRepo.notes[newId]?.bodyHtml)

        // After save, isNew is cleared
        val savedState = vm.state.value.draft
        assertIs<EditorState.Editing>(savedState)
        assertFalse(savedState.isNew)
    }

    @Test
    fun `closeEditor clears state without saving`() = runTest {
        val notesRepo = FakeNotesRepository()
        notesRepo.seed(testNote)
        val vm = createVm(notesRepo = notesRepo, scope = backgroundScope)

        vm.openEditor(testNote.id.value)
        advanceTimeBy(1_000); runCurrent()

        vm.onIntent(NotesEditorIntent.EditBody("<p>Unsaved changes</p>"))

        runCurrent()
        advanceTimeBy(100L) // well under debounce

        val dirtyState = vm.state.value.draft
        assertIs<EditorState.Editing>(dirtyState)
        assertTrue(dirtyState.isDirty)

        // Close without saving — draft reverts to the last opened state (discard);
        // the note in the repo must be untouched.
        vm.closeEditor()
        runCurrent()

        val restoredState = vm.state.value.draft
        assertIs<EditorState.Editing>(restoredState)
        assertEquals("<p>Hello world</p>", restoredState.html)
        assertFalse(restoredState.isDirty)
        // Original content unchanged in the repo
        assertEquals("<p>Hello world</p>", notesRepo.notes[testNote.id.value]?.bodyHtml)
    }

    @Test
    fun `saveNow emits savedPulse to UI`() = runTest {
        val notesRepo = FakeNotesRepository()
        notesRepo.seed(testNote)
        val vm = createVm(notesRepo = notesRepo, scope = backgroundScope)

        vm.openEditor(testNote.id.value)
        advanceTimeBy(1_000); runCurrent()

        val received = mutableListOf<Unit>()
        val job = launch { vm.events.filterIsInstance<NotesUiEvent.SavedPulse>().take(1).collect { received += Unit } }
        runCurrent() // ensure collector is subscribed before emit

        vm.onIntent(NotesEditorIntent.EditBody("<p>Updated content</p>"))

        runCurrent()
        vm.onIntent(NotesEditorIntent.SaveNow)
        advanceTimeBy(1_000); runCurrent()

        assertEquals(listOf(Unit), received)
    }
}
