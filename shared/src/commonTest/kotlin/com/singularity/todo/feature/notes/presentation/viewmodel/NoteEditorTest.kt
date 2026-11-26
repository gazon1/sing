
@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.singularity.todo.feature.notes.presentation.viewmodel

import co.touchlab.kermit.Logger
import com.singularity.todo.core.coroutines.AutoCloseableCoroutineScope
import com.singularity.todo.core.search.SearchMode
import com.singularity.todo.feature.notes.EditorState
import com.singularity.todo.feature.notes.Note
import com.singularity.todo.feature.notes.NoteId
import com.singularity.todo.feature.notes.NotesUiEvent
import com.singularity.todo.feature.notes.domain.editor.NoteAiController
import com.singularity.todo.feature.notes.presentation.viewmodel.NotesEditorIntent
import com.singularity.todo.feature.projects.domain.port.ProjectsRepository
import com.singularity.todo.feature.search.domain.port.InternalLinkRepository
import com.singularity.todo.feature.tasks.domain.model.Task
import com.singularity.todo.test.fakes.FakeClock
import com.singularity.todo.test.fakes.FakeIdGenerator
import com.singularity.todo.test.fakes.FakeNotesRepository
import com.singularity.todo.test.fakes.FakeProfileAwareCurrentUser
import com.singularity.todo.test.fakes.FakeProjectsRepository
import com.singularity.todo.test.fakes.FakeProposalRepository
import com.singularity.todo.test.fakes.FakeTaskRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Clock

/**
 * Smoke tests for [NoteEditor].
 *
 * Tests prove that openEditor / editBody / editTitle / saveNow / closeEditor
 * work correctly with the canonical VM shape.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Tag("fast")
class NoteEditorTest {
    private val testUserId =
        com.singularity.todo.core.ids
            .UserId("test-user")

    private val testNote =
        Note(
            id = NoteId("note-1"),
            userId = testUserId,
            title = "Test Note",
            bodyHtml = "<p>Hello world</p>",
            wordCount = 2,
            charCount = 11,
            createdAt = Clock.System.now(),
            updatedAt = Clock.System.now(),
        )

    private val emptyLinkRepo =
        object : InternalLinkRepository {
            override suspend fun searchNotes(
                query: String,
                mode: SearchMode,
            ): List<Note> = emptyList()

            override suspend fun searchTasks(
                query: String,
                mode: SearchMode,
            ): List<Task> = emptyList()

            override suspend fun getBacklinkNotes(noteId: String): List<Note> = emptyList()

            override suspend fun getBacklinkTasks(taskId: String): List<Task> = emptyList()

            override suspend fun getNotesLinkingToTask(taskId: String): List<Note> = emptyList()
        }

    private fun createVm(
        notesRepo: FakeNotesRepository = FakeNotesRepository(),
        scope: CoroutineScope,
    ): NoteEditor {
        val clock = FakeClock()
        val currentUser = FakeProfileAwareCurrentUser()
        val proposals = FakeProposalRepository(clock)
        val tasks = FakeTaskRepository()
        val notes = notesRepo
        val projects: ProjectsRepository = FakeProjectsRepository(currentUser)
        return NoteEditor(
            repo = notes,
            linkRepo = emptyLinkRepo,
            idGen = FakeIdGenerator("note"),
            ai = NoteAiController(improveNote = null),
            proposals = proposals,
            log = Logger.withTag("NoteEditor"),
            currentUser = currentUser,
            // The same fixed clock the proposal fixture uses, so an AI proposal's
            // `modified` stamp is a value this test can assert (#91).
            clock = clock,
            scope = AutoCloseableCoroutineScope(scope.coroutineContext),
        )
    }

    @Test
    fun `openEditor loads Editing state`() =
        runTest {
            val notesRepo = FakeNotesRepository()
            notesRepo.seed(testNote)
            val vm = createVm(notesRepo = notesRepo, scope = backgroundScope)

            vm.openEditor(testNote.id.value)
            advanceTimeBy(1_000)
            runCurrent()

            val state = vm.stateFlow.value.draft
            assertIs<EditorState.Editing>(state)
            assertEquals(testNote.id.value, state.id)
            assertEquals("Test Note", state.title.value)
            assertEquals("<p>Hello world</p>", state.html.value)
            assertFalse(state.isDirty)
            assertFalse(state.isNew)
        }

    @Test
    fun `saveNow persists and clears dirty`() =
        runTest {
            val notesRepo = FakeNotesRepository()
            notesRepo.seed(testNote)
            val vm = createVm(notesRepo = notesRepo, scope = backgroundScope)

            vm.openEditor(testNote.id.value)
            advanceTimeBy(1_000)
            runCurrent()

            // Edit body — dirty becomes true; the debounce (500ms) hasn't fired yet
            // because advanceTimeBy(400L) stays below the debounce threshold.
            vm.onIntent(NotesEditorIntent.EditBody("<p>Updated content</p>"))
            runCurrent()
            advanceTimeBy(400L) // debounce not reached yet

            val dirtyState = vm.stateFlow.value.draft
            assertIs<EditorState.Editing>(dirtyState)
            assertTrue(dirtyState.isDirty)

            // saveNow persists immediately — clears dirty regardless of autosave
            vm.onIntent(NotesEditorIntent.SaveNow)
            runCurrent()
            advanceTimeBy(1_000)
            runCurrent()

            val savedState = vm.stateFlow.value.draft
            assertIs<EditorState.Editing>(savedState)
            assertFalse(savedState.isDirty)
            assertEquals("<p>Updated content</p>", savedState.html.value)
        }

    @Test
    fun `an explicit save clears dirty before the autosave debounce fires`() =
        runTest {
            val notesRepo = FakeNotesRepository()
            notesRepo.seed(testNote)
            val vm = createVm(notesRepo = notesRepo, scope = backgroundScope)

            vm.openEditor(testNote.id.value)
            advanceTimeBy(1_000)
            runCurrent()

            vm.onIntent(NotesEditorIntent.EditBody("<p>Updated content</p>"))
            runCurrent()

            vm.onIntent(NotesEditorIntent.SaveNow)
            runCurrent()

            // No advanceTimeBy past the 500ms debounce: the save itself has to
            // clear the flag. Only the autosave used to, so a back press right
            // after an explicit save asked about text that was already stored.
            val savedState = vm.stateFlow.value.draft
            assertIs<EditorState.Editing>(savedState)
            assertFalse(savedState.isDirty)
        }

    @Test
    fun `editBody marks dirty then autosave clears it`() =
        runTest {
            val notesRepo = FakeNotesRepository()
            notesRepo.seed(testNote)
            val vm = createVm(notesRepo = notesRepo, scope = backgroundScope)

            vm.openEditor(testNote.id.value)
            advanceTimeBy(1_000)
            runCurrent()

            // Edit body — dirty becomes true; debounce (500ms) not yet reached
            vm.onIntent(NotesEditorIntent.EditBody("<p>Updated content</p>"))
            runCurrent()
            advanceTimeBy(400L) // debounce not reached yet

            val dirtyState = vm.stateFlow.value.draft
            assertIs<EditorState.Editing>(dirtyState)
            assertTrue(dirtyState.isDirty)

            // Now let autosave fire — dirty is cleared (need to advance past 500ms debounce)
            advanceTimeBy(600L)
            runCurrent()

            val cleanState = vm.stateFlow.value.draft
            assertIs<EditorState.Editing>(cleanState)
            assertFalse(cleanState.isDirty)
        }

    @Test
    fun `autosave debounce does not fire before 500ms`() =
        runTest {
            val notesRepo = FakeNotesRepository()
            notesRepo.seed(testNote)
            val vm = createVm(notesRepo = notesRepo, scope = backgroundScope)

            vm.openEditor(testNote.id.value)
            advanceTimeBy(1_000)
            runCurrent()

            // 1. Edit body — autosave scheduled but not yet fired
            vm.onIntent(NotesEditorIntent.EditBody("<p>Preliminary content</p>"))
            runCurrent()
            advanceTimeBy(499L)
            runCurrent()

            // Dirty is true, but autosave hasn't fired yet (debounce not elapsed)
            val dirtyState = vm.stateFlow.value.draft
            assertIs<EditorState.Editing>(dirtyState)
            assertTrue(dirtyState.isDirty)

            // 2. Edit again — this restarts the debounce timer
            vm.onIntent(NotesEditorIntent.EditBody("<p>Final content</p>"))
            runCurrent()
            advanceTimeBy(499L)
            runCurrent()

            // Still dirty — debounce was reset by the second edit
            val stillDirtyState = vm.stateFlow.value.draft
            assertIs<EditorState.Editing>(stillDirtyState)
            assertTrue(stillDirtyState.isDirty)

            // 3. Now let the debounce window elapse
            advanceTimeBy(1L)
            runCurrent()

            // Autosave fired — dirty cleared
            val cleanState = vm.stateFlow.value.draft
            assertIs<EditorState.Editing>(cleanState)
            assertFalse(cleanState.isDirty)
        }

    @Test
    fun `createNote marks isNew and first save calls createWithContent`() =
        runTest {
            val notesRepo = FakeNotesRepository()
            val vm = createVm(notesRepo = notesRepo, scope = backgroundScope)

            val newId = vm.createNote()
            runCurrent()

            advanceTimeBy(1_000)
            runCurrent()

            // isNew is true, note is not yet in the repo
            val state = vm.stateFlow.value.draft
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
            val savedState = vm.stateFlow.value.draft
            assertIs<EditorState.Editing>(savedState)
            assertFalse(savedState.isNew)
        }

    @Test
    fun `closeEditor clears state without saving`() =
        runTest {
            val notesRepo = FakeNotesRepository()
            notesRepo.seed(testNote)
            val vm = createVm(notesRepo = notesRepo, scope = backgroundScope)

            vm.openEditor(testNote.id.value)
            advanceTimeBy(1_000)
            runCurrent()

            vm.onIntent(NotesEditorIntent.EditBody("<p>Unsaved changes</p>"))

            runCurrent()
            advanceTimeBy(100L) // well under debounce

            val dirtyState = vm.stateFlow.value.draft
            assertIs<EditorState.Editing>(dirtyState)
            assertTrue(dirtyState.isDirty)

            // Close without saving — draft reverts to the last opened state (discard);
            // the note in the repo must be untouched.
            vm.closeEditor()
            runCurrent()

            val restoredState = vm.stateFlow.value.draft
            assertIs<EditorState.Editing>(restoredState)
            assertEquals("<p>Hello world</p>", restoredState.html.value)
            assertFalse(restoredState.isDirty)
            // Original content unchanged in the repo
            assertEquals("<p>Hello world</p>", notesRepo.notes[testNote.id.value]?.bodyHtml)
        }

    @Test
    fun `saveNow emits savedPulse to UI`() =
        runTest {
            val notesRepo = FakeNotesRepository()
            notesRepo.seed(testNote)
            val vm = createVm(notesRepo = notesRepo, scope = backgroundScope)

            vm.openEditor(testNote.id.value)
            advanceTimeBy(1_000)
            runCurrent()

            val received = mutableListOf<Unit>()
            val job =
                launch {
                    vm.events
                        .filterIsInstance<NotesUiEvent.SavedPulse>()
                        .take(1)
                        .collect { received += Unit }
                }
            runCurrent() // ensure collector is subscribed before emit

            vm.onIntent(NotesEditorIntent.EditBody("<p>Updated content</p>"))

            runCurrent()
            vm.onIntent(NotesEditorIntent.SaveNow)
            advanceTimeBy(1_000)
            runCurrent()

            assertEquals(listOf(Unit), received)
        }

    // ─── The note rewritten from outside the editor ──────────────────────────
    //
    // The editor owns the content it shows, but the note is not only written by
    // the editor: a proposal applied to it (an AI rewrite the user accepted), a
    // sync from another device. Without the editor noticing, the next keystroke
    // dispatches the *old* body over the new one — the rewrite is undone by the
    // user's next word, silently.

    @Test
    fun `a note rewritten from outside replaces the editor's content`() =
        runTest {
            val notesRepo = FakeNotesRepository()
            notesRepo.seed(testNote)
            val vm = createVm(notesRepo = notesRepo, scope = backgroundScope)

            vm.openEditor(testNote.id.value)
            advanceTimeBy(1_000)
            runCurrent()

            // The note is rewritten from outside the editor.
            notesRepo.seed(testNote.copy(title = "Rewritten", bodyHtml = "<p>Rewritten body</p>"))
            runCurrent()

            val state = vm.stateFlow.value.draft
            assertIs<EditorState.Editing>(state)
            assertEquals("Rewritten", state.title.value)
            assertEquals("<p>Rewritten body</p>", state.html.value)
            // The revision is how the fields learn the content was replaced, not typed.
            // Each field counts its own replacements.
            assertEquals(1L, state.title.revision)
            assertEquals(1L, state.html.revision)
            assertFalse(state.isDirty)
        }

    @Test
    fun `a rewrite of the body alone leaves the title's count where it was`() =
        runTest {
            val notesRepo = FakeNotesRepository()
            notesRepo.seed(testNote)
            val vm = createVm(notesRepo = notesRepo, scope = backgroundScope)

            vm.openEditor(testNote.id.value)
            advanceTimeBy(1_000)
            runCurrent()

            // The body is rewritten; the title is not.
            notesRepo.seed(testNote.copy(bodyHtml = "<p>Rewritten body</p>"))
            runCurrent()

            val state = vm.stateFlow.value.draft
            assertIs<EditorState.Editing>(state)
            assertEquals("Test Note", state.title.value)
            assertEquals("<p>Rewritten body</p>", state.html.value)
            // The body was replaced, so the body says so.
            assertEquals(1L, state.html.revision)
            // The title was not replaced, so the title says nothing: a count that moved
            // for another field's rewrite would tell the title's field that its value was
            // rewritten from outside, which did not happen.
            assertEquals(0L, state.title.revision)
        }

    @Test
    fun `a rewrite of the title alone leaves the body's count where it was`() =
        runTest {
            val notesRepo = FakeNotesRepository()
            notesRepo.seed(testNote)
            val vm = createVm(notesRepo = notesRepo, scope = backgroundScope)

            vm.openEditor(testNote.id.value)
            advanceTimeBy(1_000)
            runCurrent()

            // The title is rewritten; the body is not.
            notesRepo.seed(testNote.copy(title = "Rewritten"))
            runCurrent()

            val state = vm.stateFlow.value.draft
            assertIs<EditorState.Editing>(state)
            assertEquals("Rewritten", state.title.value)
            assertEquals("<p>Hello world</p>", state.html.value)
            assertEquals(1L, state.title.revision)
            // The body was not replaced, so the editor must not re-seed it: the count is
            // what says so.
            assertEquals(0L, state.html.revision)
        }

    @Test
    fun `the user's unsaved edits are not replaced by a rewrite from outside`() =
        runTest {
            val notesRepo = FakeNotesRepository()
            notesRepo.seed(testNote)
            val vm = createVm(notesRepo = notesRepo, scope = backgroundScope)

            vm.openEditor(testNote.id.value)
            advanceTimeBy(1_000)
            runCurrent()

            // The user is typing: their text is not saved yet.
            vm.onIntent(NotesEditorIntent.EditBody("<p>Mine</p>"))
            runCurrent()

            notesRepo.seed(testNote.copy(bodyHtml = "<p>Theirs</p>"))
            runCurrent()

            val state = vm.stateFlow.value.draft
            assertIs<EditorState.Editing>(state)
            assertEquals("<p>Mine</p>", state.html.value)
            // No revision: nothing was replaced, so no field is told otherwise.
            assertEquals(0L, state.title.revision)
            assertEquals(0L, state.html.revision)
            assertTrue(state.isDirty)
        }

    @Test
    fun `the editor's own save is not counted as a rewrite from outside`() =
        runTest {
            val notesRepo = FakeNotesRepository()
            notesRepo.seed(testNote)
            val vm = createVm(notesRepo = notesRepo, scope = backgroundScope)

            vm.openEditor(testNote.id.value)
            advanceTimeBy(1_000)
            runCurrent()

            vm.onIntent(NotesEditorIntent.EditBody("<p>Updated content</p>"))
            runCurrent()
            advanceTimeBy(600L) // the debounce elapses and the autosave writes
            runCurrent()

            val state = vm.stateFlow.value.draft
            assertIs<EditorState.Editing>(state)
            assertFalse(state.isDirty)
            // The autosave's echo of our own content is not a rewrite: the editor did
            // not learn anything it did not already know.
            assertEquals(0L, state.title.revision)
        }

    /**
     * The production shape of the checkpoint race, in the editor that actually runs it.
     *
     * `NoteEditor.onSaved` clears `Editing.isDirty`, and `NoteEditorScreen.guardedBack`
     * reads *that* field — not `DraftUiState.isDirty`. The editor stays editable while
     * `persist()` suspends, so text typed during the write used to have its dirty flag
     * wiped by the hook: Back navigated away without asking, and the keystrokes were
     * lost. The base class's own checkpoint cannot prevent that — the flag lives in the
     * draft, and only the subclass writes it.
     */
    @Test
    fun `a save does not clear the dirty flag of text typed while it was writing`() =
        runTest {
            val writeStarted = CompletableDeferred<Unit>()
            val releaseWrite = CompletableDeferred<Unit>()
            // A write that stays open: the editor has to be mid-save for the window this
            // test is about to exist at all. Subclassed rather than driven through
            // `updateContentOverride`, because that field holds a finished `Result` and
            // would run the gate at assignment instead of when the store is written.
            val notesRepo =
                object : FakeNotesRepository() {
                    override suspend fun updateContent(
                        id: NoteId,
                        title: String,
                        bodyMarkdown: String,
                        bodyHtml: String,
                    ): Result<Unit> {
                        writeStarted.complete(Unit)
                        releaseWrite.await()
                        return super.updateContent(id, title, bodyMarkdown, bodyHtml)
                    }
                }
            notesRepo.seed(testNote)
            val vm = createVm(notesRepo = notesRepo, scope = backgroundScope)

            vm.openEditor(testNote.id.value)
            advanceTimeBy(1_000)
            runCurrent()

            vm.onIntent(NotesEditorIntent.EditBody("<p>saved</p>"))
            runCurrent()
            vm.onIntent(NotesEditorIntent.SaveNow)
            runCurrent()
            writeStarted.await()

            // The user keeps typing while the write is in flight.
            vm.onIntent(NotesEditorIntent.EditBody("<p>typed during the save</p>"))
            releaseWrite.complete(Unit)
            // Past the write, but *not* past the autosave debounce: letting the debounce
            // fire would autosave the newer text, which clears the flag legitimately and
            // would make the assertion below prove nothing.
            advanceTimeBy(100)
            runCurrent()

            val state = vm.stateFlow.value.draft
            assertIs<EditorState.Editing>(state)
            assertEquals("<p>typed during the save</p>", state.html.value)
            assertTrue(
                state.isDirty,
                "the back guard reads this flag: clearing it for unstored text navigates away " +
                    "without asking, and the note loses the keystrokes",
            )
        }

    // ─── The note editor's rows of the draft behavior matrix ─────────────────
    //
    // The base class owns the contract; the editor overrides three extension
    // points — `onAutosaved`, `onSaved` and `onAutosaveError` — and each
    // override has to keep its promise.

    /**
     * A failed autosave must reach the user. The base class only logs it; a note
     * whose write failed looks exactly like a note that saved, so the editor
     * overrides the hook and emits an event.
     */
    @Test
    fun `a failed autosave tells the user`() =
        runTest {
            val notesRepo = FakeNotesRepository()
            notesRepo.seed(testNote)
            val vm = createVm(notesRepo = notesRepo, scope = backgroundScope)

            val failures = mutableListOf<NotesUiEvent.SaveFailed>()
            val job =
                launch {
                    vm.events
                        .filterIsInstance<NotesUiEvent.SaveFailed>()
                        .collect { failures += it }
                }
            runCurrent()

            vm.openEditor(testNote.id.value)
            advanceTimeBy(1_000)
            runCurrent()

            notesRepo.updateContentOverride = Result.failure(IllegalStateException("db is down"))
            vm.onIntent(NotesEditorIntent.EditBody("<p>Updated content</p>"))
            advanceTimeBy(600L)
            runCurrent()

            assertEquals(1, failures.size, "a failed autosave must reach the user")
            assertEquals("db is down", failures.single().message, "the editor learns what went wrong")

            // The failure must not cost the user their text: the editor keeps
            // what was typed, the draft stays dirty (the write never landed),
            // and the stored note still holds the old body.
            val state = vm.stateFlow.value.draft
            assertIs<EditorState.Editing>(state)
            assertEquals("<p>Updated content</p>", state.html.value, "a failed autosave keeps the user's text")
            assertTrue(state.isDirty, "a failed autosave leaves the draft dirty")
            assertEquals(
                "<p>Hello world</p>",
                notesRepo.notes[testNote.id.value]?.bodyHtml,
                "a failed autosave writes nothing to the repository",
            )
            job.cancel()
        }

    /**
     * An explicit save is the checkpoint. Closing the editor discards back to it —
     * so the text that was saved stays, and the text the editor was opened with
     * does not come back over it.
     */
    @Test
    fun `after an explicit save, closing the editor keeps the saved text`() =
        runTest {
            val notesRepo = FakeNotesRepository()
            notesRepo.seed(testNote)
            val vm = createVm(notesRepo = notesRepo, scope = backgroundScope)

            vm.openEditor(testNote.id.value)
            advanceTimeBy(1_000)
            runCurrent()

            vm.onIntent(NotesEditorIntent.EditBody("<p>Saved content</p>"))
            runCurrent()

            val edited = vm.stateFlow.value.draft
            assertIs<EditorState.Editing>(edited)
            assertTrue(edited.isDirty, "the edit makes the draft dirty")

            vm.onIntent(NotesEditorIntent.SaveNow)
            runCurrent()

            // The explicit save has to have written the note by itself. The
            // autosave debounce is still 500 ms away, so this assertion cannot
            // be satisfied by the autosave that would follow it — an explicit
            // save that persisted nothing would still leave the text on disk
            // a moment later, and the test would pass.
            assertEquals(
                "<p>Saved content</p>",
                notesRepo.notes[testNote.id.value]?.bodyHtml,
                "an explicit save writes the note",
            )

            // The editor's own flag: after an explicit save the text is on
            // disk, so the draft is no longer dirty. Without this the editor
            // would still ask to discard changes for text that was already
            // written. (The "new" flag of a note the editor created itself is
            // a separate row of the matrix — the test below.)
            val saved = vm.stateFlow.value.draft
            assertIs<EditorState.Editing>(saved)
            assertFalse(saved.isDirty, "an explicit save clears the editor's dirty flag")

            // The debounce that follows the explicit save writes the same text
            // again; the checkpoint the explicit save left behind is what
            // survives the close.
            advanceTimeBy(1_000)
            runCurrent()

            vm.closeEditor()
            runCurrent()

            val state = vm.stateFlow.value.draft
            assertIs<EditorState.Editing>(state)
            assertEquals("<p>Saved content</p>", state.html.value)
            assertEquals("<p>Saved content</p>", notesRepo.notes[testNote.id.value]?.bodyHtml)
        }

    /**
     * A note the editor created itself starts "new" and is written by its first
     * save — and by that save only.
     *
     * The "new" flag is what tells the writer to *create* the row instead of
     * updating it, so the save has to clear it: a note left "new" would be
     * created a second time, and the second create would write the note over
     * itself instead of leaving it alone.
     */
    @Test
    fun `an explicit save creates a new note exactly once`() =
        runTest {
            val notesRepo = CountingNotesRepository()
            val vm = createVm(notesRepo = notesRepo, scope = backgroundScope)

            val id = vm.createNote()
            val created = vm.stateFlow.value.draft
            assertIs<EditorState.Editing>(created)
            assertTrue(created.isNew, "a note the editor created starts as new")
            assertEquals(0, notesRepo.notes.size, "a new note is not written before the first save")

            vm.onIntent(NotesEditorIntent.EditBody("<p>Fresh note</p>"))
            runCurrent()

            vm.onIntent(NotesEditorIntent.SaveNow)
            runCurrent()

            // The assertions come before the autosave debounce (500 ms) has had a
            // chance to fire: only the explicit save can have written the row by
            // now. If the autosave were allowed to run first, it would create the
            // note on its own and the test would pass even if the explicit save
            // stopped persisting anything.
            val saved = vm.stateFlow.value.draft
            assertIs<EditorState.Editing>(saved)
            assertFalse(saved.isNew, "an explicit save turns the new note into an existing one")
            assertFalse(saved.isDirty, "an explicit save clears the editor's dirty flag")
            assertEquals(1, notesRepo.createCalls, "the explicit save creates the note")
            assertEquals("<p>Fresh note</p>", notesRepo.notes[id]?.bodyHtml)

            // Now the debounce fires — the note is written, not created a second
            // time.
            advanceTimeBy(1_000)
            runCurrent()

            assertEquals(1, notesRepo.createCalls, "the note is created once, not twice")
        }

/**
     * A save that lands while the user is still typing must still mark the note as
     * *existing* — `isNew` is what `writeNote` keys the create/update branch on, so
     * leaving it set makes the next save CREATE the note a second time.
     *
     * This is the trap in `onSaved`'s condition: the two flags answer different
     * questions. `isDirty` depends on whether the live text is the text that was
     * written; `isNew` depends only on whether the write happened, and it did.
     */
    @Test
    fun `a save during which the user types does not leave the note new`() =
        runTest {
            val writeStarted = CompletableDeferred<Unit>()
            val releaseWrite = CompletableDeferred<Unit>()
            val notesRepo =
                object : CountingNotesRepository() {
                    var holds = false

                    override suspend fun createWithContent(
                        id: NoteId,
                        title: String,
                        bodyMarkdown: String,
                        bodyHtml: String,
                    ): Result<NoteId> {
                        if (holds) {
                            writeStarted.complete(Unit)
                            releaseWrite.await()
                        }
                        return super.createWithContent(id, title, bodyMarkdown, bodyHtml)
                    }
                }
            val vm = createVm(notesRepo = notesRepo, scope = backgroundScope)

            vm.createNote()
            vm.onIntent(NotesEditorIntent.EditBody("<p>first</p>"))
            runCurrent()

            notesRepo.holds = true
            vm.onIntent(NotesEditorIntent.SaveNow)
            runCurrent()
            writeStarted.await()

            // The user keeps typing while the create is in flight.
            vm.onIntent(NotesEditorIntent.EditBody("<p>second</p>"))
            releaseWrite.complete(Unit)
            advanceTimeBy(100)
            runCurrent()

            val saved = vm.stateFlow.value.draft
            assertIs<EditorState.Editing>(saved)
            assertFalse(
                saved.isNew,
                "the note now exists: leaving it new makes the next save create it again",
            )
            assertEquals(1, notesRepo.createCalls)

            notesRepo.holds = false
            vm.onIntent(NotesEditorIntent.SaveNow)
            advanceTimeBy(1_000)
            runCurrent()

            assertEquals(
                1,
                notesRepo.createCalls,
                "the second save updates the note — it must not create it a second time",
            )
        }
}

/**
 * A notes repository that counts how often a note was *created*.
 *
 * The count is what tells "the note was written once" from "the note was
 * written twice under the same id" — the store itself would not show the
 * difference, because the second write would land on the same row.
 */
private open class CountingNotesRepository : FakeNotesRepository() {
    var createCalls = 0

    override suspend fun createWithContent(
        id: NoteId,
        title: String,
        bodyMarkdown: String,
        bodyHtml: String,
    ): Result<NoteId> {
        createCalls += 1
        return super.createWithContent(id, title, bodyMarkdown, bodyHtml)
    }
}
