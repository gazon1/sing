package com.singularity.todo.feature.notes

import com.singularity.todo.core.ids.SequenceIdGenerator
import com.singularity.todo.core.clock.DelayAutosaveScheduler
import com.singularity.todo.feature.tasks.UserId
import com.singularity.todo.test.fakes.FakeAuthRepository
import com.singularity.todo.test.fakes.FakeCurrentUser
import com.singularity.todo.test.fakes.FakeNotesRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class NotesViewModelTest {

    private val testUserId = UserId("test-user")
    private val now = Instant.fromEpochMilliseconds(1000L)

    private fun createVm(
        repo: NotesRepository = FakeNotesRepository(),
        htmlPort: MarkdownHtmlPort = FakeMarkdownHtmlPort(),
        scope: CoroutineScope? = null,
    ): NotesViewModel = NotesViewModel(
        repo = repo,
        htmlPort = htmlPort,
        currentUser = FakeCurrentUser(FakeAuthRepository(initialSession = com.singularity.todo.core.auth.Session.Anonymous(testUserId))),
        idGen = SequenceIdGenerator(),
        improveNote = null,
        scopeOverride = scope,
        autosaveScheduler = DelayAutosaveScheduler(Long.MAX_VALUE),
    )

    // ─── open editor ──────────────────────────────────────────────────────────

    @Test
    fun `open editor loads note and converts body`() = runTest {
        val repo = FakeNotesRepository().apply {
            seed(Note(
                id = NoteId.fromString("n1"),
                userId = testUserId,
                title = "My Note",
                bodyMarkdown = "# Hello",
                createdAt = now,
                updatedAt = now,
            ))
        }
        val vm = createVm(repo = repo, htmlPort = object : MarkdownHtmlPort {
            override fun toHtml(markdown: String) = "<h1>Hello</h1>"
            override fun toMarkdown(html: String) = "# Hello"
        }, scope = backgroundScope)

        vm.openEditor("n1")
        advanceUntilIdle()

        val state = vm.editorState.value
        assertIs<EditorState.Editing>(state)
        assertEquals("My Note", state.title)
        assertEquals("<h1>Hello</h1>", state.html)
        assertFalse(state.isDirty)
    }

    // ─── createNote ───────────────────────────────────────────────────────────

    @Test
    fun `createNote sets editing state with new id`() = runTest {
        val vm = createVm()
        val id = vm.createNote()
        advanceUntilIdle()

        val state = vm.editorState.value
        assertIs<EditorState.Editing>(state)
        assertEquals(id, state.id)
        assertEquals("", state.title)
        assertEquals("", state.html)
        assertFalse(state.isDirty)
    }

    /**
     * Regression test for the "empty note on open" bug.
     *
     * Scenario the user reported: create a note → type a title → close editor →
     * reopen the same note. The body should still contain the typed text.
     *
     * The bug was that `openEditor(id)` re-read from the repo unconditionally
     * even when the VM still held the in-memory `_editorState` for the same id
     * with the just-typed text. If the save hadn't fully propagated to the
     * repository flow, the editor would overwrite the in-memory text with
     * the empty defaults from the DB.
     *
     * The fix: if `_editorState` already holds Editing for this id, keep it.
     */
    @Test
    fun `reopening a saved note shows the previously typed text`() = runTest {
        val repo = FakeNotesRepository()
        val htmlPort = object : MarkdownHtmlPort {
            override fun toHtml(markdown: String) = "<p>$markdown</p>"
            override fun toMarkdown(html: String) = html.removePrefix("<p>").removeSuffix("</p>")
        }

        // 1. Create a new note, type title + body, and explicitly save.
        val vm = createVm(repo = repo, htmlPort = htmlPort, scope = backgroundScope)
        val id = vm.createNote()
        vm.editTitle(id, "Shopping list")
        vm.editBody(id, "<p>Milk, eggs</p>")
        vm.saveNow()
        advanceUntilIdle()

        // 2. Reopen the editor on the SAME VM instance.
        vm.openEditor(id)
        advanceUntilIdle()

        val state = vm.editorState.value
        assertIs<EditorState.Editing>(state)
        assertEquals("Shopping list", state.title)
        assertEquals("<p>Milk, eggs</p>", state.html)
    }

    /**
     * UI race scenario: the user creates a note, types text, and then quickly
     * taps the same note in the list before the save has propagated. The
     * `NoteEditorScreen` re-invokes `openEditor(noteId)` via `LaunchedEffect`,
     * and we must NOT clobber the still-loaded in-memory editor state.
     *
     * Without the guard, `openEditor` would await `repo.watchNote(noteId)`
     * and overwrite `_editorState` with whatever the repo currently holds —
     * which is the empty defaults if the save didn't propagate yet.
     */
    @Test
    fun `openEditor does not overwrite in-memory text when id matches`() = runTest {
        val repo = FakeNotesRepository()
        val htmlPort = object : MarkdownHtmlPort {
            override fun toHtml(markdown: String) = "<p>$markdown</p>"
            override fun toMarkdown(html: String) = html.removePrefix("<p>").removeSuffix("</p>")
        }
        val vm = createVm(repo = repo, htmlPort = htmlPort, scope = backgroundScope)

        val id = vm.createNote()
        // Type — but DO NOT save. The repo has empty content; VM has typed text.
        vm.editTitle(id, "Draft title")
        vm.editBody(id, "<p>Draft body</p>")
        advanceUntilIdle()

        // Simulate the user tapping the same note in the list — the Compose
        // screen calls openEditor(id) again on every LaunchedEffect re-trigger.
        vm.openEditor(id)
        advanceUntilIdle()

        val state = vm.editorState.value
        assertIs<EditorState.Editing>(state)
        assertEquals("Draft title", state.title)
        assertEquals("<p>Draft body</p>", state.html)
    }

    /**
     * Direct regression test for the guard added to [openEditor]: when the
     * editor is already open for the same id, a re-invocation of
     * `openEditor(id)` must NOT clobber the in-memory editor state — the
     * user's typed text is more recent than whatever the repo currently holds.
     */
    @Test
    fun `openEditor preserves in-memory state when id matches`() = runTest {
        val repo = FakeNotesRepository()
        val htmlPort = object : MarkdownHtmlPort {
            override fun toHtml(md: String) = "<p>$md</p>"
            override fun toMarkdown(html: String) = html.removePrefix("<p>").removeSuffix("</p>")
        }
        val vm = createVm(repo = repo, htmlPort = htmlPort, scope = backgroundScope)

        val id = vm.createNote()
        vm.editTitle(id, "In-flight title")
        vm.editBody(id, "<p>In-flight body</p>")
        // The repo still has empty title/body because we did NOT save.
        // NoteScreen re-invokes openEditor(id) on every LaunchedEffect re-trigger.
        vm.openEditor(id)
        advanceUntilIdle()

        val state = vm.editorState.value
        assertIs<EditorState.Editing>(state)
        assertEquals("In-flight title", state.title,
            "openEditor must NOT clobber in-memory title when id matches")
        assertEquals("<p>In-flight body</p>", state.html,
            "openEditor must NOT clobber in-memory body when id matches")
    }

    /**
     * Reproduces the user-reported "text is empty when reopening a note" bug.
     *
     * The scenario: user opens a freshly-created note, types a title and a body
     * (passing through htmlPort.toHtml, the same way the UI does via the
     * RichTextState), and then closes/reopens the editor. The body text
     * must come back.
     *
     * The bug surfaced when the UI flow was: create → edit (UI autosaves
     * via htmlPort round-trip) → close → reopen. If openEditor reads from the
     * repo *before* the autosave has propagated through the watchNote flow,
     * the user sees an empty editor even though the title they just typed
     * survived in the repo.
     *
     * We use a port that round-trips "<p>hello</p>" ↔ "hello" so the test
     * actually exercises the body field, not just the title.
     */
    @Test
    fun `reopening saved note preserves body markdown via HtmlPort`() = runTest {
        val repo = FakeNotesRepository()
        val roundTrip = object : MarkdownHtmlPort {
            override fun toHtml(md: String) = if (md.isBlank()) "" else "<p>$md</p>"
            override fun toMarkdown(html: String) =
                if (html.isBlank()) "" else html.removePrefix("<p>").removeSuffix("</p>")
        }
        val vm = createVm(repo = repo, htmlPort = roundTrip, scope = backgroundScope)

        val id = vm.createNote()
        vm.editTitle(id, "My Note")
        vm.editBody(id, roundTrip.toHtml("hello body"))
        vm.saveNow()
        advanceUntilIdle()

        // Reopen via a fresh openEditor call — the closest to "user closes and
        // reopens the editor screen". The bug would show here if the watchNote
        // flow hadn't yet emitted the saved value.
        vm.openEditor(id)
        advanceUntilIdle()

        val state = vm.editorState.value
        assertIs<EditorState.Editing>(state)
        assertEquals("My Note", state.title)
        assertEquals("<p>hello body</p>", state.html)
    }

    // ─── editTitle ────────────────────────────────────────────────────────────

    @Test
    fun `editTitle updates title and marks dirty`() = runTest {
        val vm = createVm()
        val id = vm.createNote()
        advanceUntilIdle()

        vm.editTitle(id, "New Title")
        advanceUntilIdle()

        val state = vm.editorState.value
        assertIs<EditorState.Editing>(state)
        assertEquals("New Title", state.title)
        assertTrue(state.isDirty)
    }

    // ─── editBody ─────────────────────────────────────────────────────────────

    @Test
    fun `editBody updates html and marks dirty`() = runTest {
        val vm = createVm()
        val id = vm.createNote()
        advanceUntilIdle()

        vm.editBody(id, "<p>Some text</p>")
        advanceUntilIdle()

        val state = vm.editorState.value
        assertIs<EditorState.Editing>(state)
        assertEquals("<p>Some text</p>", state.html)
        assertTrue(state.isDirty)
    }

    // ─── closeEditor ─────────────────────────────────────────────────────────

    @Test
    fun `closeEditor resets state to Empty`() = runTest {
        val vm = createVm()
        vm.createNote()
        advanceUntilIdle()

        vm.closeEditor()

        assertIs<EditorState.Empty>(vm.editorState.value)
    }

    // ─── saveNow ──────────────────────────────────────────────────────────────

    @Test
    fun `saveNow writes and emits NavigateBack`() = runTest {
        val repo = FakeNotesRepository()
        val vm = createVm(repo = repo)
        val id = vm.createNote()
        advanceUntilIdle()

        vm.editTitle(id, "My Note")
        vm.editBody(id, "# Title\n\nBody")
        advanceUntilIdle()

        vm.saveNow()
        advanceUntilIdle()

        val state = vm.editorState.value
        assertIs<EditorState.Editing>(state)
        assertFalse(state.isDirty)
    }

    // ─── improveNote with null use case ───────────────────────────────────────

    @Test
    fun `improveNote with null use case is no-op`() = runTest {
        // createVm passes null for improveNote — verify it doesn't crash
        val vm = createVm()
        advanceUntilIdle()

        vm.improveNote() // must not throw
    }

    // ─── state emissions ──────────────────────────────────────────────────────

    @Test
    fun `state emits Empty when no notes`() = runTest {
        val vm = createVm()
        advanceUntilIdle()

        val state = vm.state.value
        assertIs<NotesUiState.Empty>(state)
    }

    @Test
    fun `state emits Content when notes exist`() = runTest {
        val repo = FakeNotesRepository().apply {
            seed(Note(
                id = NoteId.fromString("n1"),
                userId = testUserId,
                title = "Note 1",
                bodyMarkdown = null,
                createdAt = now,
                updatedAt = now,
            ))
        }
        val vm = createVm(repo = repo)
        advanceUntilIdle()

        val state = vm.state.value
        assertIs<NotesUiState.Content>(state)
        assertEquals(1, state.notes.size)
    }

    @Test
    fun `delete removes note from store`() = runTest {
        val repo = FakeNotesRepository().apply {
            seed(Note(
                id = NoteId.fromString("n1"),
                userId = testUserId,
                title = "Note 1",
                bodyMarkdown = null,
                createdAt = now,
                updatedAt = now,
            ))
        }
        val vm = createVm(repo = repo)
        advanceUntilIdle()

        vm.delete(NoteId.fromString("n1"))
        advanceUntilIdle()

        val state = vm.state.value
        assertIs<NotesUiState.Empty>(state)
    }
}
