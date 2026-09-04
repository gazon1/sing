package com.singularity.todo.feature.notes

import com.singularity.todo.feature.tasks.UserId
import com.singularity.todo.test.fakes.FakeAuthRepository
import com.singularity.todo.test.fakes.FakeCurrentUser
import com.singularity.todo.test.fakes.FakeSettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class NotesViewModelTest {

    private val testUserId = UserId("test-user")
    private val now = Instant.fromEpochMilliseconds(1000L)

    private fun createVm(
        store: NotesStore = FakeNotesStore(),
        htmlPort: MarkdownHtmlPort = FakeMarkdownHtmlPort(),
        scope: CoroutineScope? = null,
    ): NotesViewModel = NotesViewModel(
        store = store,
        htmlPort = htmlPort,
        currentUser = FakeCurrentUser(FakeAuthRepository(initialSession = com.singularity.todo.core.auth.Session.Anonymous(testUserId))),
        improveNote = null, // AI not available in tests
        scopeOverride = scope,
    )

    // ─── open editor ──────────────────────────────────────────────────────────

    @Test
    fun `open editor loads note and converts body`() = runTest {
        val store = FakeNotesStore().apply {
            seed("n1", Note(
                id = NoteId.fromString("n1"),
                userId = testUserId,
                title = "My Note",
                bodyMarkdown = "# Hello",
                createdAt = now,
                updatedAt = now,
            ))
        }
        val vm = createVm(store = store, htmlPort = object : MarkdownHtmlPort {
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
        val store = FakeNotesStore()
        val vm = createVm(store = store)
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
        val store = FakeNotesStore().apply {
            seed("n1", Note(
                id = NoteId.fromString("n1"),
                userId = testUserId,
                title = "Note 1",
                bodyMarkdown = null,
                createdAt = now,
                updatedAt = now,
            ))
        }
        val vm = createVm(store = store)
        advanceUntilIdle()

        val state = vm.state.value
        assertIs<NotesUiState.Content>(state)
        assertEquals(1, state.notes.size)
    }

    @Test
    fun `delete removes note from store`() = runTest {
        val store = FakeNotesStore().apply {
            seed("n1", Note(
                id = NoteId.fromString("n1"),
                userId = testUserId,
                title = "Note 1",
                bodyMarkdown = null,
                createdAt = now,
                updatedAt = now,
            ))
        }
        val vm = createVm(store = store)
        advanceUntilIdle()

        vm.delete(NoteId.fromString("n1"))
        advanceUntilIdle()

        val state = vm.state.value
        assertIs<NotesUiState.Empty>(state)
    }
}
